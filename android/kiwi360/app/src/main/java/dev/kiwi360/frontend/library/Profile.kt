// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.library

import android.content.Context
import java.io.File

/**
 * Where Xenia keeps the console profile on storage (content_root =
 * <external files>/content):
 *   <XUID>/FFFE07D1/00010000/<XUID>/   the profile: one GPD per title played
 *   <XUID>/<TITLEID>/00000001/          the title's saved games
 *   <XUID>/<TITLEID>/Headers/00000001/  their content headers
 */
internal object Profile {
    fun contentRoot(context: Context): File? =
        context.getExternalFilesDir(null)?.let { File(it, "content") }

    /** The profile in use: the one whose dashboard GPD changed last. */
    fun activeDir(context: Context): File? =
        contentRoot(context)?.listFiles()?.filter { dashboardGpd(it).isFile }
            ?.maxByOrNull { dashboardGpd(it).lastModified() }

    fun gpdDir(profile: File) = File(profile, "FFFE07D1/00010000/${profile.name}")

    fun dashboardGpd(profile: File) = File(gpdDir(profile), "FFFE07D1.gpd")

    /** A title's folder in the profile, built from the number only. */
    fun titleDir(profile: File, titleId: Long) = File(profile, "%08X".format(titleId))
}
