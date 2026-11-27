// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package jp.xenia.emulator;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Performance HUD: guest FPS, frame time and worst frame, an FPS graph and, in
 * the full mode, CPU / GPU / RAM / battery readings. Tap it to cycle
 * full -> FPS only -> off (off leaves a small tappable area to bring it back).
 * Sensors the device doesn't expose are simply left out.
 */
public class KiwiPerfOverlay extends View {
    public enum Mode { FULL, FPS, OFF }

    private static final long SAMPLE_INTERVAL_MS = 500;
    private static final int HISTORY = 60;
    private static final float TARGET_FPS = 60f;

    private static final int COLOR_BACKGROUND = 0xB3000000;
    private static final int COLOR_LABEL = 0xFF8A93A6;
    private static final int COLOR_TEXT = 0xFFE8ECF2;
    private static final int COLOR_GOOD = 0xFF7BD88F;
    private static final int COLOR_WARN = 0xFFFFC857;
    private static final int COLOR_BAD = 0xFFFF6B6B;

    private static native void nativeGetFrameStats(long[] out);

    /** Frames the game has presented since it started (0 before the emulator runs). */
    public static long presentedFrames() {
        final long[] stats = new long[3];
        try {
            nativeGetFrameStats(stats);
        } catch (UnsatisfiedLinkError e) {
            return 0;
        }
        return stats[0];
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint graphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF backgroundRect = new RectF();
    private final Path graphPath = new Path();
    private static final String FPS_LOG_TAG = "KiwiFps";
    private final float density;
    // The two words in the HUD; the rest are units (FPS, ms, GHz).
    private final String labelMax;
    private final String labelBattery;

    private Mode mode = Mode.FULL;
    private final List<Float> fpsHistory = new ArrayList<>();
    private final long[] frameStats = new long[3];
    private long lastGenerated = -1;
    // Frames shown per second with frame generation (game frames + generated); 0 without it.
    private float totalFps;
    private long lastFrames = -1;
    private long lastSampleNanos;
    private long lastCpuTotal;
    private long lastCpuIdle;

    // Last sample.
    private float fps;
    private float frameTimeMs;
    private float worstFrameMs;
    private Float cpuPercent;
    private final List<Integer> cpuClusterMhz = new ArrayList<>();
    private Float cpuTempC;
    private Float gpuPercent;
    private Integer gpuMhz;
    private Float gpuTempC;
    private int appRamMb;
    private int deviceRamUsedMb;
    private int deviceRamTotalMb;
    private Float batteryTempC;
    private Float powerWatts;

    // Probed once.
    private final List<File> cpuFrequencyFiles = new ArrayList<>();
    private final List<File> cpuTemperatureFiles = new ArrayList<>();
    private File gpuBusyFile;
    private File gpuClockFile;
    private File gpuTemperatureFile;

    private final Runnable sampler = new Runnable() {
        @Override
        public void run() {
            sample();
            // The text width changes with the values (and sensors only show
            // up after the first samples), so measure again.
            requestLayout();
            invalidate();
            handler.postDelayed(this, SAMPLE_INTERVAL_MS);
        }
    };

    public KiwiPerfOverlay(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        labelMax = context.getString(R.string.hud_max);
        labelBattery = context.getString(R.string.hud_battery);
        textPaint.setTypeface(Typeface.MONOSPACE);
        backgroundPaint.setColor(COLOR_BACKGROUND);
        graphPaint.setStyle(Paint.Style.STROKE);
        graphPaint.setStrokeWidth(1.5f * density);
        probeSensors();
        setOnClickListener(v -> {
            mode = Mode.values()[(mode.ordinal() + 1) % Mode.values().length];
            requestLayout();
            invalidate();
        });
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(final Mode mode) {
        this.mode = mode;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.post(sampler);
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(sampler);
        super.onDetachedFromWindow();
    }

    // Sampling.

    private void probeSensors() {
        File[] policies = new File("/sys/devices/system/cpu/cpufreq").listFiles(
                f -> f.getName().startsWith("policy"));
        if (policies != null) {
            java.util.Arrays.sort(policies, (a, b) -> Integer.compare(
                    parseIntOr(a.getName().substring(6), 0),
                    parseIntOr(b.getName().substring(6), 0)));
            for (File policy : policies) {
                File frequency = new File(policy, "scaling_cur_freq");
                if (frequency.canRead()) {
                    cpuFrequencyFiles.add(frequency);
                }
            }
        }
        for (int i = 0; i < 128; ++i) {
            File zone = new File("/sys/class/thermal/thermal_zone" + i);
            String type = readFirstLine(new File(zone, "type"));
            if (type == null) {
                continue;
            }
            if (type.startsWith("cpu") || type.startsWith("cpuss")) {
                File temperature = new File(zone, "temp");
                if (temperature.canRead()) {
                    cpuTemperatureFiles.add(temperature);
                }
            }
        }
        gpuBusyFile = readable("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage");
        gpuClockFile = readable("/sys/class/kgsl/kgsl-3d0/gpuclk");
        if (gpuClockFile == null) {
            gpuClockFile = readable("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq");
        }
        gpuTemperatureFile = readable("/sys/class/kgsl/kgsl-3d0/temp");
    }

    private void sample() {
        long now = SystemClock.elapsedRealtimeNanos();
        try {
            nativeGetFrameStats(frameStats);
        } catch (UnsatisfiedLinkError e) {
            return;
        }
        long frames = frameStats[0];
        long generated = frameStats[2];
        if (lastFrames >= 0 && now > lastSampleNanos) {
            fps = Math.max(0, frames - lastFrames) * 1e9f / (now - lastSampleNanos);
            float generatedFps = lastGenerated >= 0
                    ? Math.max(0, generated - lastGenerated) * 1e9f / (now - lastSampleNanos) : 0f;
            totalFps = generatedFps > 0.5f ? fps + generatedFps : 0f;
            frameTimeMs = fps > 0f ? 1000f / fps : 0f;
            fpsHistory.add(fps);
            if (fpsHistory.size() > HISTORY) {
                fpsHistory.remove(0);
            }
        }
        lastFrames = frames;
        lastGenerated = generated;
        lastSampleNanos = now;
        worstFrameMs = frameStats[1] / 1e6f;
        // For A/B measurements: adb shell setprop log.tag.KiwiFps DEBUG, then
        // logcat -s KiwiFps (one line a second). Off unless that tag is enabled.
        if (Log.isLoggable(FPS_LOG_TAG, Log.DEBUG)) {
            Log.d(FPS_LOG_TAG, String.format(Locale.US, "fps %.1f worst_ms %.1f", fps, worstFrameMs));
        }

        if (mode != Mode.FULL) {
            return;
        }
        cpuPercent = sampleCpuPercent();
        cpuClusterMhz.clear();
        for (File file : cpuFrequencyFiles) {
            Long khz = readLong(file);
            if (khz != null) {
                cpuClusterMhz.add((int) (khz / 1000));
            }
        }
        Float hottest = null;
        for (File file : cpuTemperatureFiles) {
            Long value = readLong(file);
            if (value != null) {
                float c = milliToCelsius(value);
                hottest = hottest == null ? c : Math.max(hottest, c);
            }
        }
        cpuTempC = hottest;
        Long busy = readLong(gpuBusyFile);
        gpuPercent = busy != null ? busy.floatValue() : null;
        Long clock = readLong(gpuClockFile);
        gpuMhz = clock != null ? (int) (clock / 1_000_000) : null;
        Long gpuTemp = readLong(gpuTemperatureFile);
        gpuTempC = gpuTemp != null ? milliToCelsius(gpuTemp) : null;

        appRamMb = readAppRssMb();
        ActivityManager activityManager =
                (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memory);
        deviceRamUsedMb = (int) ((memory.totalMem - memory.availMem) / (1024 * 1024));
        deviceRamTotalMb = (int) (memory.totalMem / (1024 * 1024));

        Intent battery = getContext().registerReceiver(
                null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        batteryTempC = null;
        powerWatts = null;
        if (battery != null) {
            int temperature = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,
                    Integer.MIN_VALUE);
            if (temperature != Integer.MIN_VALUE) {
                batteryTempC = temperature / 10f;
            }
            int voltageMv = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            BatteryManager batteryManager =
                    (BatteryManager) getContext().getSystemService(Context.BATTERY_SERVICE);
            long currentUa = batteryManager.getLongProperty(
                    BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (voltageMv > 0 && currentUa != Long.MIN_VALUE && currentUa != 0) {
                // The sign differs between vendors; the magnitude is the draw.
                powerWatts = Math.abs(currentUa / 1e6f * voltageMv / 1e3f);
            }
        }
    }

    private Float sampleCpuPercent() {
        String line = readFirstLine(new File("/proc/stat"));
        if (line == null) {
            return null;
        }
        String[] fields = line.trim().split("\\s+");
        if (fields.length < 6) {
            return null;
        }
        long total = 0;
        for (int i = 1; i < fields.length; ++i) {
            total += parseLongOr(fields[i], 0);
        }
        long idle = parseLongOr(fields[4], 0) + parseLongOr(fields[5], 0);
        long deltaTotal = total - lastCpuTotal;
        long deltaIdle = idle - lastCpuIdle;
        boolean first = lastCpuTotal == 0;
        lastCpuTotal = total;
        lastCpuIdle = idle;
        if (first || deltaTotal <= 0) {
            return null;
        }
        return Math.max(0f, Math.min(100f, 100f * (deltaTotal - deltaIdle) / deltaTotal));
    }

    private static int readAppRssMb() {
        String statm = readFirstLine(new File("/proc/self/statm"));
        if (statm == null) {
            return 0;
        }
        String[] fields = statm.split(" ");
        if (fields.length < 2) {
            return 0;
        }
        long pageBytes = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE);
        return (int) (parseLongOr(fields[1], 0) * pageBytes / (1024 * 1024));
    }

    // Drawing.

    private float sp(final float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                getResources().getDisplayMetrics());
    }

    private static int fpsColor(final float fps) {
        return fps >= 55f ? COLOR_GOOD : (fps >= 28f ? COLOR_WARN : COLOR_BAD);
    }

    private static int temperatureColor(final Float c) {
        if (c == null) {
            return COLOR_TEXT;
        }
        return c >= 85f ? COLOR_BAD : (c >= 70f ? COLOR_WARN : COLOR_TEXT);
    }

    /** Runs of text drawn left to right in one line. */
    private final class Line {
        private final List<String> texts = new ArrayList<>();
        private final List<Integer> colors = new ArrayList<>();
        private final List<Boolean> bold = new ArrayList<>();

        Line add(final String text, final int color) {
            return add(text, color, false);
        }

        Line add(final String text, final int color, final boolean isBold) {
            texts.add(text);
            colors.add(color);
            bold.add(isBold);
            return this;
        }

        float width(final Paint paint) {
            float width = 0;
            for (int i = 0; i < texts.size(); ++i) {
                paint.setFakeBoldText(bold.get(i));
                width += paint.measureText(texts.get(i));
            }
            paint.setFakeBoldText(false);
            return width;
        }

        float draw(final Canvas canvas, final Paint paint, float x, final float y) {
            for (int i = 0; i < texts.size(); ++i) {
                paint.setColor(colors.get(i));
                paint.setFakeBoldText(bold.get(i));
                canvas.drawText(texts.get(i), x, y, paint);
                x += paint.measureText(texts.get(i));
            }
            paint.setFakeBoldText(false);
            return x;
        }
    }

    private Line fpsLine() {
        Line line = new Line()
                .add(String.format(Locale.US, "%.0f", fps), fpsColor(fps), true)
                .add(" FPS  ", COLOR_LABEL);
        if (totalFps > 0f) {
            // Frame generation: the game's own rate first, then what is shown.
            line.add(String.format(Locale.US, "%.0f", totalFps), fpsColor(totalFps), true)
                    .add(" FG  ", COLOR_LABEL);
        }
        line
                .add(String.format(Locale.US, "%.1f", frameTimeMs), COLOR_TEXT)
                .add(" ms", COLOR_LABEL);
        if (mode == Mode.FULL) {
            line.add("  " + labelMax + " ", COLOR_LABEL)
                    .add(String.format(Locale.US, "%.1f", worstFrameMs),
                            worstFrameMs > 50f ? COLOR_WARN : COLOR_TEXT);
        }
        return line;
    }

    private Line sensorLine() {
        Line line = new Line();
        line.add("CPU ", COLOR_LABEL).add(cpuPercent != null
                ? String.format(Locale.US, "%.0f%%", cpuPercent) : "-", COLOR_TEXT);
        if (!cpuClusterMhz.isEmpty()) {
            StringBuilder clusters = new StringBuilder(" ");
            for (int i = 0; i < cpuClusterMhz.size(); ++i) {
                if (i > 0) {
                    clusters.append('/');
                }
                clusters.append(String.format(Locale.US, "%.1f", cpuClusterMhz.get(i) / 1000f));
            }
            line.add(clusters.toString(), COLOR_TEXT).add("GHz", COLOR_LABEL);
        }
        if (cpuTempC != null) {
            line.add(String.format(Locale.US, " %.0f°", cpuTempC), temperatureColor(cpuTempC));
        }
        line.add("  |  ", COLOR_LABEL);
        line.add("GPU ", COLOR_LABEL).add(gpuPercent != null
                ? String.format(Locale.US, "%.0f%%", gpuPercent) : "-", COLOR_TEXT);
        if (gpuMhz != null) {
            line.add(" " + gpuMhz, COLOR_TEXT).add("MHz", COLOR_LABEL);
        }
        if (gpuTempC != null) {
            line.add(String.format(Locale.US, " %.0f°", gpuTempC), temperatureColor(gpuTempC));
        }
        line.add("  |  ", COLOR_LABEL);
        line.add("RAM ", COLOR_LABEL)
                .add(String.format(Locale.US, "%.1f", appRamMb / 1024f), COLOR_TEXT)
                .add(String.format(Locale.US, " / %.1f/%.1fGB", deviceRamUsedMb / 1024f,
                        deviceRamTotalMb / 1024f), COLOR_LABEL);
        if (powerWatts != null || batteryTempC != null) {
            line.add("  |  ", COLOR_LABEL).add(labelBattery + " ", COLOR_LABEL).add(powerWatts != null
                    ? String.format(Locale.US, "%.1fW", powerWatts) : "-", COLOR_TEXT);
            if (batteryTempC != null) {
                line.add(String.format(Locale.US, " %.0f°", batteryTempC),
                        temperatureColor(batteryTempC));
            }
        }
        return line;
    }

    private float padX() { return 10 * density; }
    private float padY() { return 6 * density; }
    private float graphWidth() { return 96 * density; }
    private float gap() { return 12 * density; }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        if (mode == Mode.OFF) {
            // Small invisible area that still takes the tap to show the HUD again.
            int size = (int) (32 * density);
            setMeasuredDimension(size, size);
            return;
        }
        textPaint.setTextSize(sp(12));
        float width = fpsLine().width(textPaint);
        float height = textPaint.getFontSpacing();
        if (mode == Mode.FULL) {
            width += gap() + graphWidth();
            textPaint.setTextSize(sp(11));
            width = Math.max(width, sensorLine().width(textPaint));
            height += 3 * density + textPaint.getFontSpacing();
        }
        setMeasuredDimension((int) Math.ceil(width + 2 * padX()),
                (int) Math.ceil(height + 2 * padY()));
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        if (mode == Mode.OFF) {
            return;
        }
        backgroundRect.set(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(backgroundRect, 8 * density, 8 * density, backgroundPaint);

        textPaint.setTextSize(sp(12));
        float y = padY() - textPaint.ascent();
        float x = fpsLine().draw(canvas, textPaint, padX(), y);
        if (mode == Mode.FULL) {
            float graphTop = padY() + 2 * density;
            float graphHeight = textPaint.getFontSpacing() - 4 * density;
            drawGraph(canvas, x + gap(), graphTop, graphWidth(), graphHeight);
            float firstLineHeight = textPaint.getFontSpacing();
            textPaint.setTextSize(sp(11));
            float y2 = padY() + firstLineHeight + 3 * density - textPaint.ascent();
            sensorLine().draw(canvas, textPaint, padX(), y2);
        }
    }

    private void drawGraph(final Canvas canvas, final float left, final float top,
            final float width, final float height) {
        graphPaint.setColor(0x33FFFFFF);
        graphPaint.setStrokeWidth(1f);
        canvas.drawLine(left, top, left + width, top, graphPaint);
        if (fpsHistory.size() < 2) {
            return;
        }
        graphPath.reset();
        float step = width / (HISTORY - 1);
        float x = left + width - step * (fpsHistory.size() - 1);
        for (int i = 0; i < fpsHistory.size(); ++i) {
            float value = Math.max(0f, Math.min(1f, fpsHistory.get(i) / TARGET_FPS));
            float y = top + height * (1f - value);
            if (i == 0) {
                graphPath.moveTo(x, y);
            } else {
                graphPath.lineTo(x, y);
            }
            x += step;
        }
        graphPaint.setColor(fpsColor(fpsHistory.get(fpsHistory.size() - 1)));
        graphPaint.setStrokeWidth(1.5f * density);
        canvas.drawPath(graphPath, graphPaint);
    }

    // File helpers.

    private static File readable(final String path) {
        File file = new File(path);
        return file.canRead() ? file : null;
    }

    private static String readFirstLine(final File file) {
        if (file == null) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            return reader.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    private static Long readLong(final File file) {
        String line = readFirstLine(file);
        if (line == null) {
            return null;
        }
        String first = line.trim().split("[ \t]")[0];
        try {
            return Long.parseLong(first);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static float milliToCelsius(final long value) {
        return value > 1000 ? value / 1000f : value;
    }

    private static int parseIntOr(final String text, final int fallback) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLongOr(final String text, final long fallback) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
