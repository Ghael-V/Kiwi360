// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.library

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Read-only: tells other apps (frontends like Bramble) what Kiwi knows about a
 * game file they already know the path of.
 *
 *   query content://<applicationId>.library/title?path=<absolute path>
 *         one row (TITLE_COLUMNS) for an Xbox 360 game Kiwi can read, else none
 *   query .../achievements?path=…   one row per achievement (ACHIEVEMENT_COLUMNS)
 *   query .../saves?path=…          one row (SAVE_COLUMNS)
 *   query .../screenshots?path=…    always empty: Kiwi takes no screenshots
 *   openFile .../icon/<TITLEID>                     the title's PNG
 *   openFile .../achievement_icon/<TITLEID>/<id>    an achievement's PNG
 *   openFile .../save_zip/<TITLEID>                 the title's saves as a ZIP
 * Files open "r" only.
 *
 * It never lists the library, writes nothing and exposes no settings, logs or
 * internal paths. Files are found by validated Title ID (and achievement number)
 * in Kiwi's own folders only. Saves are written back only by ImportSavesActivity,
 * after the user confirms.
 */
class TitleProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val context = context!!
        val segments = uri.pathSegments
        val columns = when (segments.singleOrNull()) {
            PATH_TITLE -> TITLE_COLUMNS
            PATH_ACHIEVEMENTS -> ACHIEVEMENT_COLUMNS
            PATH_SAVES -> SAVE_COLUMNS
            PATH_SCREENSHOTS -> SCREENSHOT_COLUMNS
            else -> TITLE_COLUMNS
        }
        val cursor = MatrixCursor(columns)
        cursor.setNotificationUri(context.contentResolver, titleUri(context))
        val info = gameAt(context, uri) ?: return cursor
        val titleId = "%08X".format(info.titleId)
        try {
            when (segments.single()) {
                PATH_TITLE -> cursor.addRow(titleRow(context, info, titleId))
                PATH_ACHIEVEMENTS -> AchievementStore(context).achievements(info.titleId).forEach {
                    cursor.addRow(achievementRow(context, titleId, it))
                }
                PATH_SAVES -> {
                    val saves = SaveStore(context).info(info.titleId)
                    cursor.addRow(
                        arrayOf<Any?>(
                            if (saves != null) 1 else 0,
                            saves?.sizeBytes ?: 0L,
                            saves?.updatedAt,
                            saves?.let { fileUri(context, PATH_SAVE_ZIP, titleId).toString() },
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            return MatrixCursor(columns)
        }
        return cursor
    }

    /** The game at the URI's "path", or null (bad URI, not a game, or no file access). */
    private fun gameAt(context: Context, uri: Uri): TitleInfo? {
        if (uri.pathSegments.singleOrNull() !in QUERY_PATHS) return null
        val path = uri.getQueryParameter("path")?.takeIf { it.startsWith("/") } ?: return null
        // Without all-files access Kiwi can't read the file either.
        if (!Environment.isExternalStorageManager()) return null
        return try {
            Library(context).titleInfo(path)
        } catch (_: Exception) {
            null
        }
    }

    private fun titleRow(context: Context, info: TitleInfo, titleId: String): Array<Any?> {
        val playTime = PlayTime.forGame(context, titleId).read()
        val lastPlayed = maxOf(Library(context).lastLaunched(titleId), playTime?.lastPlayed ?: 0L)
        val achievements = AchievementStore(context).achievements(info.titleId)
        return arrayOf(
            titleId,
            info.name,
            info.icon?.let { fileUri(context, PATH_ICON, titleId).toString() },
            info.disc,
            info.discCount,
            info.updatedAt,
            playTime?.seconds,
            lastPlayed.takeIf { it > 0 },
            playTime?.sessions,
            achievements.size.takeIf { it > 0 },
            achievements.count { it.unlocked }.takeIf { achievements.isNotEmpty() },
        )
    }

    private fun achievementRow(context: Context, titleId: String, a: Achievement): Array<Any?> {
        val secret = a.hidden && !a.unlocked
        return arrayOf(
            a.id.toString(),
            a.name,
            when {
                secret -> null
                a.unlocked -> a.description
                else -> a.lockedDescription
            },
            if (a.unlocked) 1 else 0,
            a.unlockedAt,
            null,
            a.points,
            if (a.hidden) 1 else 0,
            if (a.hasIcon) fileUri(context, PATH_ACHIEVEMENT_ICON, titleId, a.id.toString()).toString() else null,
        )
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read only")
        val context = context!!
        val segments = uri.pathSegments
        // Everything below is built from the validated Title ID (and number) only.
        val titleIdText = segments.getOrNull(1)?.takeIf { TITLE_ID.matches(it) }?.uppercase()
            ?: throw FileNotFoundException("not a title")
        val titleId = titleIdText.toLong(16)
        val file: File = when {
            segments.size == 2 && segments[0] == PATH_ICON ->
                Library(context).iconFile(titleIdText)
            segments.size == 3 && segments[0] == PATH_ACHIEVEMENT_ICON -> {
                val id = segments[2].takeIf { NUMBER.matches(it) }?.toInt() ?: throw FileNotFoundException("not an achievement")
                AchievementStore(context).achievementIcon(titleId, id)?.let { png ->
                    File(cacheDir(context, "achievement_icons"), "${titleIdText}_$id.png").apply { writeBytes(png) }
                }
            }
            segments.size == 2 && segments[0] == PATH_SAVE_ZIP -> {
                val zip = File(cacheDir(context, "save_zip"), "$titleIdText.zip")
                if (zip.outputStream().use { SaveStore(context).export(titleId, it) }) zip else null
            }
            else -> null
        } ?: throw FileNotFoundException("not found")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun cacheDir(context: Context, name: String) = File(context.cacheDir, name).apply { mkdirs() }

    override fun getType(uri: Uri): String? = when (uri.pathSegments.firstOrNull()) {
        PATH_TITLE, PATH_SAVES -> "vnd.android.cursor.item/vnd.dev.kiwi360.$PATH_TITLE"
        PATH_ACHIEVEMENTS, PATH_SCREENSHOTS -> "vnd.android.cursor.dir/vnd.dev.kiwi360.${uri.pathSegments[0]}"
        PATH_ICON, PATH_ACHIEVEMENT_ICON -> "image/png"
        PATH_SAVE_ZIP -> "application/zip"
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("read only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read only")

    companion object {
        val TITLE_COLUMNS = arrayOf(
            "title_id", "name", "icon_uri", "disc", "disc_count", "updated_at",
            "play_seconds", "last_played", "sessions", "achievements_total", "achievements_unlocked",
        )
        val ACHIEVEMENT_COLUMNS = arrayOf(
            "id", "name", "description", "unlocked", "unlocked_at", "grade", "points", "hidden", "icon_uri",
        )
        val SAVE_COLUMNS = arrayOf("has_saves", "size_bytes", "updated_at", "zip_uri")
        val SCREENSHOT_COLUMNS = arrayOf("uri", "taken_at", "width", "height")

        private const val PATH_TITLE = "title"
        private const val PATH_ACHIEVEMENTS = "achievements"
        private const val PATH_SAVES = "saves"
        private const val PATH_SCREENSHOTS = "screenshots"
        private const val PATH_ICON = "icon"
        private const val PATH_ACHIEVEMENT_ICON = "achievement_icon"
        private const val PATH_SAVE_ZIP = "save_zip"
        private val QUERY_PATHS = setOf(PATH_TITLE, PATH_ACHIEVEMENTS, PATH_SAVES)
        private val TITLE_ID = Regex("^[0-9A-Fa-f]{8}$")
        private val NUMBER = Regex("^[0-9]{1,9}$")

        private fun authority(context: Context) = "${context.packageName}.library"

        fun titleUri(context: Context): Uri = Uri.parse("content://${authority(context)}/$PATH_TITLE")

        private fun fileUri(context: Context, vararg segments: String): Uri =
            Uri.parse("content://${authority(context)}/" + segments.joinToString("/"))
    }
}
