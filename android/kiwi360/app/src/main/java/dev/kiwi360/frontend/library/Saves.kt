// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.library

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A title's saved games in the active profile. */
data class SaveInfo(val sizeBytes: Long, val updatedAt: Long, val files: Int)

/**
 * Saved games of a title, exported and imported as a ZIP:
 *   <TITLEID>/00000001/...           the saves (content type 1)
 *   <TITLEID>/Headers/00000001/...   their content headers
 * Nothing else goes in or comes out. Imports keep a backup ZIP of what they replace.
 */
class SaveStore(private val context: Context) {
    private val profileDir: File? by lazy { Profile.activeDir(context) }

    private fun saveDirs(titleId: Long): List<File> = profileDir?.let { profile ->
        val title = Profile.titleDir(profile, titleId)
        SUBTREES.map { File(title, it) }
    } ?: emptyList()

    /** Null if the title has no saves. */
    fun info(titleId: Long): SaveInfo? {
        val (saves, headers) = saveDirs(titleId).map { dir -> dir.walkTopDown().filter { it.isFile }.toList() }
            .let { (it.getOrNull(0) ?: emptyList()) to (it.getOrNull(1) ?: emptyList()) }
        if (saves.isEmpty()) return null
        val files = saves + headers
        return SaveInfo(files.sumOf { it.length() }, files.maxOf { it.lastModified() }, files.size)
    }

    /** Writes the title's saves as a ZIP to [out]; false if it has none. */
    fun export(titleId: Long, out: OutputStream): Boolean {
        val profile = profileDir ?: return false
        info(titleId) ?: return false
        val title = Profile.titleDir(profile, titleId)
        val root = "%08X".format(titleId)
        ZipOutputStream(out.buffered()).use { zip ->
            for (subtree in SUBTREES) {
                val dir = File(title, subtree)
                dir.walkTopDown().filter { it.isFile }.forEach { file ->
                    val name = "$root/" + file.relativeTo(title).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        return true
    }

    /**
     * The Title ID of a saves ZIP, or null if it isn't one: every entry must sit
     * under one <TITLEID>/00000001/ or <TITLEID>/Headers/00000001/, with no other
     * paths, and within the size limits.
     */
    fun validate(input: InputStream): Long? {
        var titleId: String? = null
        var total = 0L
        var count = 0
        var hasSave = false
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                val match = ENTRY.matchEntire(name) ?: return null
                if (name.split('/').any { it == ".." || it == "." }) return null
                if (titleId == null) titleId = match.groupValues[1] else if (titleId != match.groupValues[1]) return null
                if (!entry.isDirectory) {
                    if (match.groupValues[2] == "00000001") hasSave = true
                    // Count what is really there, not what the entry claims.
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) return null
                    }
                }
                if (++count > MAX_ENTRIES) return null
            }
        }
        if (!hasSave) return null
        return titleId?.toLongOrNull(16)
    }

    /**
     * Replaces the title's saves with those in the ZIP (already validated for
     * [titleId]). What it replaces goes first to a backup ZIP in the app's files.
     * Unpacks to a temporary folder and swaps it in only once complete.
     */
    fun import(titleId: Long, input: InputStream): ImportResult {
        val profile = profileDir ?: return ImportResult.NO_PROFILE
        val title = Profile.titleDir(profile, titleId)
        val root = "%08X".format(titleId)
        val staging = File(title.parentFile, "$root.kiwi_import")
        staging.deleteRecursively()
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val match = ENTRY.matchEntire(entry.name) ?: return ImportResult.INVALID
                    if (match.groupValues[1] != root || entry.name.split('/').any { it == ".." || it == "." }) {
                        return ImportResult.INVALID
                    }
                    val target = File(staging, entry.name.removePrefix("$root/"))
                    if (!target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                        return ImportResult.INVALID
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
            if (info(titleId) != null) {
                val backups = File(context.filesDir, "save_backups").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
                File(backups, "${root}_$stamp.zip").outputStream().use { export(titleId, it) }
            }
            for (subtree in SUBTREES) {
                val dir = File(title, subtree)
                dir.deleteRecursively()
                val fresh = File(staging, subtree)
                if (fresh.exists()) {
                    dir.parentFile?.mkdirs()
                    if (!fresh.renameTo(dir)) fresh.copyRecursively(dir, overwrite = true)
                }
            }
            return ImportResult.DONE
        } catch (_: Exception) {
            return ImportResult.FAILED
        } finally {
            staging.deleteRecursively()
        }
    }

    enum class ImportResult { DONE, INVALID, NO_PROFILE, FAILED }

    private companion object {
        val SUBTREES = listOf("00000001", "Headers/00000001")
        val ENTRY = Regex("^([0-9A-F]{8})/(00000001|Headers/00000001)(/.*)?$")
        const val MAX_BYTES = 2L shl 30
        const val MAX_ENTRIES = 20_000
    }
}
