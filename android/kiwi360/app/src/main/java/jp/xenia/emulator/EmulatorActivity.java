// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package jp.xenia.emulator;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;

import dev.kiwi360.frontend.GameLauncher;
import dev.kiwi360.frontend.aiconfig.AiConfigStore;
import dev.kiwi360.frontend.library.PlayTimeTracker;
import dev.kiwi360.frontend.logs.KiwiLogs;
import dev.kiwi360.frontend.overlay.GameMenuHost;
import dev.kiwi360.frontend.overlay.KiwiOverlay;
import dev.kiwi360.frontend.overlay.VirtualPadState;

/**
 * Kiwi360: runs Xenia Edge's "xenia" windowed app for one game.
 *
 * Cvars come from intent extras named "cvar.NAME", so the library and adb can
 * set them. The extra type must match the cvar type, because the native side
 * reads it with the typed Bundle getter (--es string/path, --ez bool, --ei int,
 * --el long, --ef/--ed double):
 *   am start -n dev.kiwi360.proto/jp.xenia.emulator.EmulatorActivity \
 *     --es cvar.target /sdcard/Android/data/dev.kiwi360.proto/files/game.iso \
 *     --ez cvar.kiwi_texture_upload_blit false
 * The performance HUD is chosen with --es kiwi.hud full|fps|off and cycled by
 * tapping it.
 * Defaults: storage under the app's external files dir, audio through AAudio, input from
 * the Android gamepad (hid=android). Gamepad buttons can also be injected:
 *   adb shell input gamepad keyevent KEYCODE_BUTTON_START
 *
 * It runs in the ":emu" process, started by the library (LibraryActivity).
 * The emulator's windows (system dialogs, notifications, the crash report, the
 * quit prompt) are drawn by KiwiOverlay in Compose; while one is up it takes
 * the gamepad. Back, or Select and Start together, asks to quit the game;
 * quitting ends the process, because the emulator's native state can't be set
 * up twice in one process.
 */
public class EmulatorActivity extends WindowedAppActivity {
    private static final String CVAR_PREFIX = "cvar.";

    // How long the native teardown may take before the process is ended anyway.
    private static final long EXIT_WATCHDOG_MS = 3000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<EmulatorActivity> current = new WeakReference<>(null);

    private KiwiOverlay overlay;
    /** Time played, for the library and other apps; null for a launch without a game key. */
    private PlayTimeTracker playTime;
    /** Kiwi's own request to leave the game (GameLauncher.closeRunningGame). */
    private final BroadcastReceiver closeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            quitGame();
        }
    };
    private boolean exitComboArmed = true;

    @Override
    protected String getWindowedAppIdentifier() {
        return "xenia";
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        final Intent intent = getIntent();
        Bundle cvars = intent.getBundleExtra(EXTRA_CVARS);
        if (cvars == null) {
            cvars = new Bundle();
        }
        final Bundle extras = intent.getExtras();
        if (extras != null) {
            for (final String key : extras.keySet()) {
                if (key.startsWith(CVAR_PREFIX)) {
                    putTypedCvar(cvars, key.substring(CVAR_PREFIX.length()),
                            extras.get(key));
                }
            }
        }
        final File files = getExternalFilesDir(null);
        if (files != null && !cvars.containsKey("storage_root")) {
            cvars.putString("storage_root", files.getAbsolutePath());
        }
        if (!cvars.containsKey("content_root_secondary")) {
            // Content the user moved to the SD card (settings, installed content).
            final File sd = dev.kiwi360.frontend.library.ContentStorage.sdRoot(this);
            if (sd != null) {
                cvars.putString("content_root_secondary", sd.getAbsolutePath());
            }
        }
        final String target = cvars.getString("target");
        if (!cvars.containsKey("content_loose_dirs") && target != null) {
            // Title updates and DLC packages kept beside the game are used in place.
            final File folder = new File(target).getParentFile();
            if (folder != null && folder.isDirectory()) {
                cvars.putString("content_loose_dirs", folder.getAbsolutePath());
            }
        }
        if (!cvars.containsKey("log_file")) {
            // One log file per session, kept for sharing after a crash.
            final String log = KiwiLogs.newSessionLog(this);
            if (log != null) {
                cvars.putString("log_file", log);
            }
        }
        if (!cvars.containsKey("vulkan_driver_hook_dir")) {
            // libadrenotools hooks, for custom Vulkan drivers.
            cvars.putString("vulkan_driver_hook_dir",
                    getApplicationInfo().nativeLibraryDir);
        }
        if (!cvars.containsKey("apu")) {
            // Android output through AAudio; "nop" discards the audio.
            cvars.putString("apu", "aaudio");
        }
        if (!cvars.containsKey("hid")) {
            cvars.putString("hid", "android");
        }
        if (!cvars.containsKey("gpu")) {
            cvars.putString("gpu", "vulkan");
        }
        intent.putExtra(EXTRA_CVARS, cvars);
        setIntent(intent);

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_emulator);
        setWindowSurfaceView(findViewById(R.id.emulator_surface_view));

        // Performance HUD: --es kiwi.hud full|fps|off (default full). Tap cycles.
        final KiwiPerfOverlay perfOverlay = findViewById(R.id.perf_overlay);
        final String hud = intent.getStringExtra("kiwi.hud");
        if ("fps".equals(hud)) {
            perfOverlay.setMode(KiwiPerfOverlay.Mode.FPS);
        } else if ("off".equals(hud)) {
            perfOverlay.setMode(KiwiPerfOverlay.Mode.OFF);
        }

        overlay = new KiwiOverlay(this, findViewById(android.R.id.content),
                (id, json) -> {
                    nativeUiRespond(id, json.getBytes(StandardCharsets.UTF_8));
                    return kotlin.Unit.INSTANCE;
                },
                () -> {
                    quitGame();
                    return kotlin.Unit.INSTANCE;
                },
                cvars.getString("target"),
                new GameMenuHost() {
                    @Override
                    public String getCvar(final String name) {
                        return nativeGetCvar(name);
                    }

                    @Override
                    public boolean setCvar(final String name, final String value) {
                        return nativeSetCvar(name, value);
                    }

                    @Override
                    public String getHudMode() {
                        switch (perfOverlay.getMode()) {
                            case FPS: return "fps";
                            case OFF: return "off";
                            default: return "full";
                        }
                    }

                    @Override
                    public void setVirtualPad(final VirtualPadState state) {
                        virtualButtons = state.getButtons();
                        virtualLeftTrigger = state.getLeftTrigger();
                        virtualRightTrigger = state.getRightTrigger();
                        virtualThumbLX = state.getLeftX();
                        virtualThumbLY = state.getLeftY();
                        virtualThumbRX = state.getRightX();
                        virtualThumbRY = state.getRightY();
                        pushGamepadState();
                    }

                    @Override
                    public void setHudMode(final String mode) {
                        perfOverlay.setMode("fps".equals(mode) ? KiwiPerfOverlay.Mode.FPS
                                : "off".equals(mode) ? KiwiPerfOverlay.Mode.OFF
                                : KiwiPerfOverlay.Mode.FULL);
                    }
                },
                intent.getStringExtra("kiwi.game_key"),
                intent.getStringExtra("kiwi.touch"));
        // Lowest priority: the overlay's dialogs handle Back first while shown.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                openMenu();
            }
        });
        current = new WeakReference<>(this);
        nativeSetUiHost(true);
        final String gameKey = intent.getStringExtra("kiwi.game_key");
        if (gameKey != null && !gameKey.isEmpty()) {
            playTime = new PlayTimeTracker(this, gameKey);
            playTime.startSession();
        }
        // On unconfirmed recommended settings, watch that the game starts and keeps running.
        AiConfigStore.onGameStarted(this, gameKey, KiwiPerfOverlay::presentedFrames);
        ContextCompat.registerReceiver(this, closeReceiver,
                new IntentFilter(GameLauncher.closeGameAction(this)), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (playTime != null) {
            playTime.resume();
        }
    }

    @Override
    protected void onPause() {
        if (playTime != null) {
            playTime.pause();
        }
        super.onPause();
    }

    // Debug: opens one of the emulator's windows over the running game
    // (app/kiwi_ui_selftest_android.cc lists the types):
    //   am start --activity-single-top -n dev.kiwi360.proto/jp.xenia.emulator.EmulatorActivity \
    //       --es kiwi.ui_test message
    // (single top, so it reaches the running instance instead of creating another).
    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        final String test = intent.getStringExtra("kiwi.ui_test");
        if (test != null) {
            nativeUiSelfTest(test);
        }
    }

    private static native void nativeUiSelfTest(String type);

    // The native UI bridge (xenia/ui/kiwi_ui_bridge_android.cc).
    private static native void nativeSetUiHost(boolean enabled);

    private static native void nativeUiRespond(long id, byte[] json);

    /** From native code, on any thread. */
    @SuppressWarnings("unused")
    private static void onNativeUiRequest(final long id, final byte[] json) {
        MAIN.post(() -> {
            final EmulatorActivity activity = current.get();
            if (activity != null && activity.overlay != null && !activity.isFinishing()) {
                activity.overlay.onNativeRequest(id, json);
            } else {
                nativeUiRespond(id, "{\"cancelled\":true}".getBytes(StandardCharsets.UTF_8));
            }
        });
    }

    // X_INPUT_GAMEPAD button bits.
    private static final int PAD_DPAD_UP = 0x0001;
    private static final int PAD_DPAD_DOWN = 0x0002;
    private static final int PAD_DPAD_LEFT = 0x0004;
    private static final int PAD_DPAD_RIGHT = 0x0008;
    private static final int PAD_START = 0x0010;
    private static final int PAD_BACK = 0x0020;
    private static final int PAD_LEFT_THUMB = 0x0040;
    private static final int PAD_RIGHT_THUMB = 0x0080;
    private static final int PAD_LEFT_SHOULDER = 0x0100;
    private static final int PAD_RIGHT_SHOULDER = 0x0200;
    private static final int PAD_GUIDE = 0x0400;
    private static final int PAD_A = 0x1000;
    private static final int PAD_B = 0x2000;
    private static final int PAD_X = 0x4000;
    private static final int PAD_Y = 0x8000;

    private int padKeyButtons;   // From key events.
    private int padHatButtons;   // From the hat axes.
    private int padKeyLeftTrigger;
    private int padKeyRightTrigger;
    private int padAxisLeftTrigger;
    private int padAxisRightTrigger;
    private int padThumbLX;
    private int padThumbLY;
    private int padThumbRX;
    private int padThumbRY;
    // The on-screen gamepad, merged with the physical one.
    private int virtualButtons;
    private int virtualLeftTrigger;
    private int virtualRightTrigger;
    private int virtualThumbLX;
    private int virtualThumbLY;
    private int virtualThumbRX;
    private int virtualThumbRY;

    private static native void nativeSetGamepadState(int buttons, int leftTrigger,
            int rightTrigger, int thumbLX, int thumbLY, int thumbRX, int thumbRY);

    private static boolean isGamepadSource(final int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    private static int buttonForKeyCode(final int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return PAD_A;
            case KeyEvent.KEYCODE_BUTTON_B: return PAD_B;
            case KeyEvent.KEYCODE_BUTTON_X: return PAD_X;
            case KeyEvent.KEYCODE_BUTTON_Y: return PAD_Y;
            case KeyEvent.KEYCODE_BUTTON_L1: return PAD_LEFT_SHOULDER;
            case KeyEvent.KEYCODE_BUTTON_R1: return PAD_RIGHT_SHOULDER;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return PAD_LEFT_THUMB;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return PAD_RIGHT_THUMB;
            case KeyEvent.KEYCODE_BUTTON_START: return PAD_START;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return PAD_BACK;
            case KeyEvent.KEYCODE_BUTTON_MODE: return PAD_GUIDE;
            case KeyEvent.KEYCODE_DPAD_UP: return PAD_DPAD_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return PAD_DPAD_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return PAD_DPAD_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return PAD_DPAD_RIGHT;
            default: return 0;
        }
    }

    private void pushGamepadState() {
        // A stick the physical pad leaves centred takes the on-screen one.
        nativeSetGamepadState(padKeyButtons | padHatButtons | virtualButtons,
                Math.max(Math.max(padKeyLeftTrigger, padAxisLeftTrigger), virtualLeftTrigger),
                Math.max(Math.max(padKeyRightTrigger, padAxisRightTrigger), virtualRightTrigger),
                padThumbLX != 0 ? padThumbLX : virtualThumbLX,
                padThumbLY != 0 ? padThumbLY : virtualThumbLY,
                padThumbRX != 0 ? padThumbRX : virtualThumbRX,
                padThumbRY != 0 ? padThumbRY : virtualThumbRY);
    }

    /** The game sees nothing held while the overlay has the gamepad. */
    private void releaseGamepad() {
        if ((padKeyButtons | padHatButtons | padKeyLeftTrigger | padKeyRightTrigger
                | padAxisLeftTrigger | padAxisRightTrigger | padThumbLX | padThumbLY
                | padThumbRX | padThumbRY) == 0) {
            return;
        }
        padKeyButtons = 0;
        padHatButtons = 0;
        padKeyLeftTrigger = 0;
        padKeyRightTrigger = 0;
        padAxisLeftTrigger = 0;
        padAxisRightTrigger = 0;
        padThumbLX = 0;
        padThumbLY = 0;
        padThumbRX = 0;
        padThumbRY = 0;
        pushGamepadState();
    }

    @Override
    public boolean dispatchKeyEvent(final KeyEvent event) {
        final int keyCode = event.getKeyCode();
        final boolean gamepad =
                isGamepadSource(event.getSource()) || KeyEvent.isGamepadButton(keyCode);
        if (gamepad && overlay != null && overlay.getCapturesInput()) {
            releaseGamepad();
            exitComboArmed = true;
            if (keyCode == KeyEvent.KEYCODE_BUTTON_A) {
                // A selects like the d-pad centre, which Compose's tiles understand.
                return super.dispatchKeyEvent(new KeyEvent(event.getDownTime(),
                        event.getEventTime(), event.getAction(), KeyEvent.KEYCODE_DPAD_CENTER,
                        event.getRepeatCount(), event.getMetaState(), event.getDeviceId(),
                        event.getScanCode(), event.getFlags(), event.getSource()));
            }
            if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    getOnBackPressedDispatcher().onBackPressed();
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        if (gamepad) {
            final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            final boolean up = event.getAction() == KeyEvent.ACTION_UP;
            if (down || up) {
                if (keyCode == KeyEvent.KEYCODE_BUTTON_L2) {
                    padKeyLeftTrigger = down ? 255 : 0;
                    pushGamepadState();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
                    padKeyRightTrigger = down ? 255 : 0;
                    pushGamepadState();
                    return true;
                }
                final int button = buttonForKeyCode(keyCode);
                if (button != 0) {
                    if (down) {
                        padKeyButtons |= button;
                    } else {
                        padKeyButtons &= ~button;
                    }
                    final int combo = PAD_START | PAD_BACK;
                    if ((padKeyButtons & combo) == combo && exitComboArmed) {
                        exitComboArmed = false;
                        openMenu();
                        return true;
                    }
                    pushGamepadState();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onWindowFocusChanged(final boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // The game gets the whole screen; the bars come back with a swipe.
            final android.view.WindowInsetsController controller =
                    getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(android.view.WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    // Edge swipe: a touch that starts this close to the left or right edge and goes this far
    // toward the centre, more sideways than up or down, opens the menu instead of reaching
    // the game.
    private static final float EDGE_ZONE_DP = 24f;
    private static final float EDGE_TRAVEL_DP = 72f;
    private boolean edgeTracking;
    /** The rest of a touch that opened the menu: nothing of it goes to the game. */
    private boolean edgeSwallowing;
    private boolean edgeFromLeft;
    private float edgeStartX;
    private float edgeStartY;

    @Override
    public boolean dispatchTouchEvent(final MotionEvent event) {
        final float density = getResources().getDisplayMetrics().density;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                final float zone = EDGE_ZONE_DP * density;
                final int width = getWindow().getDecorView().getWidth();
                edgeSwallowing = false;
                edgeFromLeft = event.getX() <= zone;
                edgeTracking = !overlay.getCapturesInput()
                        && (edgeFromLeft || event.getX() >= width - zone);
                edgeStartX = event.getX();
                edgeStartY = event.getY();
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (edgeSwallowing) {
                    return true;
                }
                if (!edgeTracking) {
                    break;
                }
                final float travel = (event.getX() - edgeStartX) * (edgeFromLeft ? 1 : -1);
                final float drift = Math.abs(event.getY() - edgeStartY);
                final float needed = EDGE_TRAVEL_DP * density;
                if (travel >= needed && drift < travel) {
                    edgeTracking = false;
                    edgeSwallowing = true;
                    // Whatever the touch pressed so far (a virtual button) is let go.
                    final MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                    openMenu();
                    return true;
                }
                if (drift > needed) {
                    // Up or down along the edge: a touch for the game.
                    edgeTracking = false;
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                edgeTracking = false;
                if (edgeSwallowing) {
                    edgeSwallowing = false;
                    return true;
                }
                break;
            }
            default:
                if (edgeSwallowing) {
                    return true;
                }
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    /** Select + Start, Back or an edge swipe: the in-game menu (settings, leaving the game). */
    private void openMenu() {
        // The game shouldn't see the buttons that opened the menu.
        releaseGamepad();
        overlay.openMenu();
    }

    /** Sets a cvar for the running game (see kiwi_live_settings_android.cc). */
    private static native boolean nativeSetCvar(String name, String value);

    private static native String nativeGetCvar(String name);

    private void quitGame() {
        AiConfigStore.onGameExit(this);
        // If the native teardown hangs, end the process anyway.
        final Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(EXIT_WATCHDOG_MS);
            } catch (InterruptedException ignored) {
            }
            Process.killProcess(Process.myPid());
        }, "Kiwi360 exit watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        finish();
    }

    @Override
    protected void onDestroy() {
        final boolean finishing = isFinishing();
        try {
            unregisterReceiver(closeReceiver);
        } catch (IllegalArgumentException ignored) {
            // onCreate returned before registering it.
        }
        nativeSetUiHost(false);
        super.onDestroy();
        if (finishing) {
            AiConfigStore.onGameExit(this);
            // A fresh process for the next game.
            Process.killProcess(Process.myPid());
        }
    }

    private static int stickAxis(final MotionEvent event, final int axis, final boolean invert) {
        float value = event.getAxisValue(axis);
        if (invert) {
            value = -value;
        }
        return Math.round(Math.max(-1.0f, Math.min(1.0f, value)) * 32767.0f);
    }

    private static int triggerAxis(final MotionEvent event, final int axis, final int altAxis) {
        final float value = Math.max(event.getAxisValue(axis), event.getAxisValue(altAxis));
        return Math.round(Math.max(0.0f, Math.min(1.0f, value)) * 255.0f);
    }

    @Override
    public boolean dispatchGenericMotionEvent(final MotionEvent event) {
        if (isGamepadSource(event.getSource()) && overlay != null
                && overlay.getCapturesInput()) {
            // Sticks and the hat navigate the overlay (the framework turns them
            // into d-pad keys when nothing handles the motion).
            releaseGamepad();
            return super.dispatchGenericMotionEvent(event);
        }
        if (isGamepadSource(event.getSource())
                && event.getAction() == MotionEvent.ACTION_MOVE) {
            padThumbLX = stickAxis(event, MotionEvent.AXIS_X, false);
            padThumbLY = stickAxis(event, MotionEvent.AXIS_Y, true);
            padThumbRX = stickAxis(event, MotionEvent.AXIS_Z, false);
            padThumbRY = stickAxis(event, MotionEvent.AXIS_RZ, true);
            padAxisLeftTrigger = triggerAxis(event, MotionEvent.AXIS_LTRIGGER,
                    MotionEvent.AXIS_BRAKE);
            padAxisRightTrigger = triggerAxis(event, MotionEvent.AXIS_RTRIGGER,
                    MotionEvent.AXIS_GAS);
            final float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            final float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            padHatButtons = (hatX < -0.5f ? PAD_DPAD_LEFT : 0)
                    | (hatX > 0.5f ? PAD_DPAD_RIGHT : 0)
                    | (hatY < -0.5f ? PAD_DPAD_UP : 0)
                    | (hatY > 0.5f ? PAD_DPAD_DOWN : 0);
            pushGamepadState();
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private static void putTypedCvar(final Bundle cvars, final String name, final Object value) {
        if (value instanceof Boolean) {
            cvars.putBoolean(name, (Boolean) value);
        } else if (value instanceof Integer) {
            cvars.putInt(name, (Integer) value);
        } else if (value instanceof Long) {
            cvars.putLong(name, (Long) value);
        } else if (value instanceof Float || value instanceof Double) {
            cvars.putDouble(name, ((Number) value).doubleValue());
        } else if (value != null) {
            cvars.putString(name, value.toString());
        }
    }
}
