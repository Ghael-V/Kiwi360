// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Whether this device's CPU can run the emulator. The native library is built
 * for ARMv8.2 with LSE atomics: on an ARMv8.0 CPU (Cortex-A53/A73 cores, as in
 * a Snapdragon 680) the emulator process dies with SIGILL at its first atomic.
 *
 * The kernel lists each core's features in /proc/cpuinfo; LSE shows as the
 * "atomics" flag. A big.LITTLE CPU has one "Features" line per core (or per
 * cluster), and the emulator's threads can land on any of them, so every line
 * must have the flag.
 */
object CpuSupport {
    class Result(
        val supported: Boolean,
        /** The SoC as the system names it, for the message the user sees. */
        val cpuName: String,
    )

    private const val CPUINFO = "/proc/cpuinfo"
    private const val PREFS = "kiwi_cpu"
    private const val KEY_NOTICE_SHOWN = "unsupported_notice_shown"

    /** Read once per process: the CPU doesn't change. */
    val result: Result by lazy { check(runCatching { File(CPUINFO).readText() }.getOrNull()) }

    val supported: Boolean get() = result.supported

    /** [cpuinfo] null (unreadable) counts as supported: the check must never block a CPU that works. */
    internal fun check(cpuinfo: String?): Result = Result(
        supported = cpuinfo == null || hasAtomicsOnEveryCore(cpuinfo),
        cpuName = cpuName(cpuinfo),
    )

    /** True too when no Features line is found: nothing says the CPU lacks LSE. */
    internal fun hasAtomicsOnEveryCore(cpuinfo: String): Boolean =
        field(cpuinfo, "Features").all { features -> features.split(' ', '\t').any { it == "atomics" } }

    private fun cpuName(cpuinfo: String?): String {
        // Older kernels name the SoC here ("Qualcomm Technologies, Inc SM6225").
        cpuinfo?.let { field(it, "Hardware").firstOrNull { name -> name.isNotBlank() } }?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val model = Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
            if (model != null) {
                val maker = Build.SOC_MANUFACTURER.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
                return listOfNotNull(maker, model).joinToString(" ")
            }
        }
        return Build.HARDWARE.ifBlank { Build.UNKNOWN }
    }

    /** The values of every "name : value" line with that name. */
    private fun field(cpuinfo: String, name: String): List<String> =
        cpuinfo.lineSequence()
            .map { it.split(':', limit = 2) }
            .filter { it.size == 2 && it[0].trim() == name }
            .map { it[1].trim() }
            .toList()

    /** The library tells an unsupported device once, at its first start. */
    fun shouldShowNotice(context: Context): Boolean =
        !supported && !prefs(context).getBoolean(KEY_NOTICE_SHOWN, false)

    fun markNoticeShown(context: Context) = prefs(context).edit().putBoolean(KEY_NOTICE_SHOWN, true).apply()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
