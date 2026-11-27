// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.library

import java.util.Locale

/**
 * Reads a title's own name and icon from a disc image or XEX, without booting
 * it, through the emulator library (kiwi_title_info_android.cc).
 */
object NativeTitleInfo {
    private val available: Boolean = try {
        System.loadLibrary("xenia-app")
        true
    } catch (_: Throwable) {
        false
    }

    /** The title name in the device's language, or null; the icon PNG goes to [iconPath]. */
    fun read(path: String, iconPath: String): String? {
        if (!available) return null
        return try {
            read(path, Locale.getDefault().toLanguageTag(), iconPath).ifBlank { null }
        } catch (_: Throwable) {
            null
        }
    }

    @JvmStatic
    private external fun read(path: String, locale: String, iconPath: String): String
}
