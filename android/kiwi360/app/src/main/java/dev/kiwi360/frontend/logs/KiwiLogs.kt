// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.logs

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import dev.kiwi360.frontend.drivers.GpuDriverStore
import dev.kiwi360.frontend.library.Library
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The emulator's logs on disk and the package the user shares to report a
 * problem.
 *
 * Every game session writes its own log (files/logs/kiwi360_<time>.log, plus a
 * ".1" part when it grows past half of kiwi_log_file_max_mb), so the log of a
 * crash survives the process. Sharing puts in one zip: the recent session
 * logs and crash reports (see [recent]), why the last processes ended (from
 * Android, with a summary of the native crash tombstones), a description of
 * the device with the GPU driver each game uses, and the emulator settings.
 */
object KiwiLogs {
    /** The newest sessions are always kept, and shared. */
    private const val SESSIONS_KEPT = 8
    /** Crash reports (the overlay's crash screen) shared beyond those of the last [RECENT_MS]. */
    private const val CRASH_REPORTS_SHARED = 8
    /** Anything this recent is kept and shared too, within the byte limits below. */
    private const val RECENT_MS = 48L * 60 * 60 * 1000
    /** Bytes (before compression) of logs and crash reports a shared package takes at most. */
    private const val SHARED_BYTES = 20L * 1024 * 1024
    /** Bytes the sessions beyond the newest [SESSIONS_KEPT] may take on disk. */
    private const val EXTRA_SESSIONS_DISK_BYTES = 128L * 1024 * 1024
    /** Crashes and other abnormal exits are all shared; routine exits only the latest. */
    private const val ROUTINE_EXITS_SHARED = 6

    // ApplicationExitInfo.REASON_PACKAGE_UPDATED, API 33; the app targets 30+.
    private const val REASON_PACKAGE_UPDATED = 16

    private val STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun logsDir(context: Context): File? =
        context.getExternalFilesDir(null)?.let { File(it, "logs") }

    /**
     * A new session log path. Removes the sessions beyond the newest kept ones (this
     * one included), except those from the last [RECENT_MS] that fit in
     * [EXTRA_SESSIONS_DISK_BYTES]: sharing takes them too.
     */
    @JvmStatic
    fun newSessionLog(context: Context): String? {
        val dir = logsDir(context) ?: return null
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val now = System.currentTimeMillis()
        var extraBytes = 0L
        for (old in sessionLogs(dir).drop(SESSIONS_KEPT - 1)) {
            val parts = sessionParts(old)
            if (parts.isEmpty()) continue
            extraBytes += parts.sumOf { it.length() }
            val recent = now - parts.maxOf { it.lastModified() } < RECENT_MS
            if (!recent || extraBytes > EXTRA_SESSIONS_DISK_BYTES) parts.forEach { it.delete() }
        }
        return File(dir, "kiwi360_${STAMP.format(Date())}.log").absolutePath
    }

    /** Session logs, newest first. */
    private fun sessionLogs(dir: File): List<File> =
        dir.listFiles { f -> f.name.startsWith("kiwi360_") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.name } ?: emptyList()

    /** A session's files, newest part first: the log, then the ".1" part it rotated out. */
    private fun sessionParts(log: File): List<File> = listOf(log, File(log.path + ".1")).filter { it.isFile }

    /**
     * What a package takes of a series of items (each one the files of a session or a
     * report, newest item first): the newest [count] and any others from the last
     * [RECENT_MS], newest first, while they fit in [budget] bytes; the oldest are left
     * out. The first file always goes in, however large: it is the one most likely
     * asked for. Returns the files and the bytes they take.
     */
    private fun recent(items: List<List<File>>, count: Int, budget: Long, now: Long): Pair<List<File>, Long> {
        val chosen = ArrayList<File>()
        var bytes = 0L
        for ((index, parts) in items.withIndex()) {
            if (parts.isEmpty()) continue
            if (index >= count && now - parts.maxOf { it.lastModified() } >= RECENT_MS) break
            for (part in parts) {
                val size = part.length()
                if (chosen.isNotEmpty() && bytes + size > budget) return chosen to bytes
                chosen += part
                bytes += size
            }
        }
        return chosen to bytes
    }

    /**
     * Builds the zip to share; returns it, or null if it could not be written.
     * Runs file I/O, so call it off the main thread.
     */
    /**
     * [sessionDriver]: from a running game, the driver it runs on. The default driver
     * and every game's own choice are listed anyway.
     */
    fun buildPackage(context: Context, sessionDriver: String? = null): File? = try {
        val outDir = File(context.cacheDir, "shared_logs").apply { mkdirs() }
        outDir.listFiles()?.forEach { it.delete() }
        val stamp = STAMP.format(Date())
        val zipFile = File(outDir, "kiwi360_logs_$stamp.zip")
        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            fun text(name: String, value: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
            fun file(name: String, source: File) {
                if (!source.isFile) return
                zip.putNextEntry(ZipEntry(name).apply { time = source.lastModified() })
                source.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }

            text("device.txt", deviceDescription(context, sessionDriver))
            val now = System.currentTimeMillis()
            val files = context.getExternalFilesDir(null)
            // Crash reports first: small, and each one a crash the logs alone may not explain.
            val reports = files?.let { File(it, "crash_reports") }
                ?.listFiles { f -> f.isFile && f.name.startsWith("crash_") && f.name.endsWith(".txt") }
                ?.sortedByDescending { it.name }.orEmpty()
            val (sharedReports, reportBytes) =
                recent(reports.map { listOf(it) }, CRASH_REPORTS_SHARED, SHARED_BYTES, now)
            sharedReports.forEach { file("crash_reports/${it.name}", it) }
            logsDir(context)?.let { dir ->
                val sessions = sessionLogs(dir).map { sessionParts(it) }
                val budget = (SHARED_BYTES - reportBytes).coerceAtLeast(0)
                recent(sessions, SESSIONS_KEPT, budget, now).first.forEach { file("logs/${it.name}", it) }
            }
            files?.let { file("kiwi360.config.toml", File(it, "kiwi360.config.toml")) }
            exitReasons(context, ::text)
        }
        zipFile
    } catch (_: Exception) {
        null
    }

    /** The Android share sheet for a package built by [buildPackage]. */
    fun shareIntent(context: Context, zip: File, title: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", zip)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, zip.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, title)
    }

    private fun deviceDescription(context: Context, sessionDriver: String?): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return buildString {
            appendLine("Kiwi360 ${info.versionName} (${info.longVersionCode})")
            appendLine("Generated: ${Date()}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                appendLine("SoC: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
            }
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Build: ${Build.FINGERPRINT}")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
            val memory = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
            appendLine("RAM: ${memory.totalMem / (1024 * 1024)} MB")
            appendDrivers(context, sessionDriver)
        }
    }

    /**
     * The default GPU driver and every game that overrides it. The session logs say
     * which game each session ran (its title ID, which is the key of most games).
     */
    private fun StringBuilder.appendDrivers(context: Context, sessionDriver: String?) {
        val drivers = runCatching { GpuDriverStore(context) }.getOrNull()
        if (drivers != null) {
            appendLine("GPU driver (default): ${drivers.label(drivers.find(drivers.defaultDriverId))}")
        }
        sessionDriver?.let { appendLine("GPU driver (this game): $it") }
        if (drivers == null) return
        val overrides = drivers.gameOverrides()
        if (overrides.isEmpty()) {
            appendLine("Per-game drivers: none")
            return
        }
        appendLine("Per-game drivers:")
        val names = runCatching { Library(context).cachedGames().associate { it.key to it.name } }
            .getOrDefault(emptyMap())
        overrides.entries.sortedBy { it.key }.forEach { (key, id) ->
            val name = names[key]?.let { " ($it)" } ?: ""
            appendLine("  $key$name: ${drivers.overrideLabel(id)}")
        }
    }

    /** Why the app's last processes ended, with the trace Android kept for crashes. */
    private fun exitReasons(context: Context, text: (String, String) -> Unit) {
        val manager = context.getSystemService(ActivityManager::class.java)
        val all = try {
            manager.getHistoricalProcessExitReasons(null, 0, 0)
        } catch (_: Exception) {
            return
        }
        val routine = setOf(
            ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE,
            REASON_PACKAGE_UPDATED,
        )
        // Abnormal exits first (newest first), then the latest routine ones.
        val abnormal = all.filter { it.reason !in routine }
        val exits = abnormal + all.filter { it.reason in routine }.take(ROUTINE_EXITS_SHARED)
        val summary = StringBuilder()
        summary.appendLine("${all.size} exits recorded by Android, ${abnormal.size} abnormal (listed first).")
        summary.appendLine()
        exits.forEachIndexed { index, exit ->
            summary.appendLine("#$index ${Date(exit.timestamp)} ${exit.processName} pid ${exit.pid}")
            summary.appendLine("  reason: ${reasonName(exit.reason)}, status ${exit.status}, " +
                "importance ${exit.importance}, pss ${exit.pss / 1024} MB, rss ${exit.rss / 1024} MB")
            exit.description?.let { summary.appendLine("  description: $it") }
            val trace = try {
                exit.traceInputStream?.use { it.readBytes() }
            } catch (_: Exception) {
                null
            }
            if (trace != null) {
                if (exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    // A tombstone in protobuf form; the summary is enough to read it.
                    val tombstone = TombstoneSummary.decode(trace)
                    if (tombstone != null) {
                        summary.append(tombstone.prependIndent("  "))
                        summary.appendLine()
                    }
                } else {
                    text("exit_reasons/${index}_trace.txt", String(trace))
                    summary.appendLine("  trace: exit_reasons/${index}_trace.txt")
                }
            }
            summary.appendLine()
        }
        text("exit_reasons.txt", summary.toString())
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit self"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        ApplicationExitInfo.REASON_CRASH -> "crash (Java)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash (native)"
        ApplicationExitInfo.REASON_ANR -> "not responding"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource usage"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
        ApplicationExitInfo.REASON_OTHER -> "other"
        REASON_PACKAGE_UPDATED -> "package updated"
        else -> "unknown ($reason)"
    }
}

/**
 * Reads the parts of an Android tombstone (debuggerd's tombstone.proto) that
 * say what crashed: the process, the signal, the abort message and the
 * crashing thread's backtrace. Fields not listed here are skipped.
 */
internal object TombstoneSummary {
    private class Reader(val data: ByteArray, var pos: Int = 0, val end: Int = data.size) {
        fun more() = pos < end
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (pos < end) {
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) break
            }
            throw IllegalStateException("bad varint")
        }
        /** Returns (field, wire type); the value is read with the methods below. */
        fun tag(): Pair<Int, Int> = varint().let { (it ushr 3).toInt() to (it and 7).toInt() }
        fun bytes(): Reader {
            val length = varint().toInt()
            require(length >= 0 && pos + length <= end)
            return Reader(data, pos, pos + length).also { pos += length }
        }
        fun string() = bytes().let { String(it.data, it.pos, it.end - it.pos) }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> bytes()
                5 -> pos += 4
                else -> throw IllegalStateException("wire type $wire")
            }
        }
    }

    private class Frame(var pc: Long = 0, var function: String = "", var offset: Long = 0, var file: String = "")

    private class Thread(var id: Long = 0, var name: String = "", val frames: MutableList<Frame> = mutableListOf())

    fun decode(data: ByteArray): String? = try {
        val r = Reader(data)
        var pid = 0L
        var tid = 0L
        var abort = ""
        var signal = ""
        var uptime = 0L
        val commandLine = mutableListOf<String>()
        val threads = mutableListOf<Thread>()
        while (r.more()) {
            val (field, wire) = r.tag()
            when {
                field == 5 && wire == 0 -> pid = r.varint()
                field == 6 && wire == 0 -> tid = r.varint()
                field == 9 && wire == 2 -> commandLine += r.string()
                field == 10 && wire == 2 -> signal = signal(r.bytes())
                field == 14 && wire == 2 -> abort = r.string()
                field == 16 && wire == 2 -> threadEntry(r.bytes())?.let { threads += it }
                field == 20 && wire == 0 -> uptime = r.varint()
                else -> r.skip(wire)
            }
        }
        buildString {
            appendLine("tombstone: ${commandLine.joinToString(" ")} pid $pid tid $tid, uptime ${uptime} s")
            if (signal.isNotEmpty()) appendLine("signal: $signal")
            if (abort.isNotEmpty()) appendLine("abort message: $abort")
            threads.find { it.id == tid }?.let { thread ->
                appendLine("backtrace of thread \"${thread.name}\":")
                thread.frames.forEachIndexed { i, f ->
                    val function = if (f.function.isNotEmpty()) " (${f.function}+${f.offset})" else ""
                    appendLine("  #%02d pc %016x  %s%s".format(i, f.pc, f.file, function))
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    /** Signal { int32 number = 1; string name = 2; int32 code = 3; string code_name = 4; } */
    private fun signal(r: Reader): String {
        var number = 0L
        var name = ""
        var codeName = ""
        while (r.more()) {
            val (field, wire) = r.tag()
            when {
                field == 1 && wire == 0 -> number = r.varint()
                field == 2 && wire == 2 -> name = r.string()
                field == 4 && wire == 2 -> codeName = r.string()
                else -> r.skip(wire)
            }
        }
        return "$number ($name) $codeName".trim()
    }

    /** map<uint32, Thread> entry: key = 1, value = 2. */
    private fun threadEntry(r: Reader): Thread? {
        var thread: Thread? = null
        while (r.more()) {
            val (field, wire) = r.tag()
            if (field == 2 && wire == 2) thread = thread(r.bytes()) else r.skip(wire)
        }
        return thread
    }

    /** Thread { int32 id = 1; string name = 2; repeated BacktraceFrame current_backtrace = 4; } */
    private fun thread(r: Reader): Thread {
        val thread = Thread()
        while (r.more()) {
            val (field, wire) = r.tag()
            when {
                field == 1 && wire == 0 -> thread.id = r.varint()
                field == 2 && wire == 2 -> thread.name = r.string()
                field == 4 && wire == 2 -> thread.frames += frame(r.bytes())
                else -> r.skip(wire)
            }
        }
        return thread
    }

    /** BacktraceFrame { uint64 rel_pc = 1; string function_name = 4; uint64 function_offset = 5; string file_name = 6; } */
    private fun frame(r: Reader): Frame {
        val frame = Frame()
        while (r.more()) {
            val (field, wire) = r.tag()
            when {
                field == 1 && wire == 0 -> frame.pc = r.varint()
                field == 4 && wire == 2 -> frame.function = r.string()
                field == 5 && wire == 0 -> frame.offset = r.varint()
                field == 6 && wire == 2 -> frame.file = r.string()
                else -> r.skip(wire)
            }
        }
        return frame
    }
}
