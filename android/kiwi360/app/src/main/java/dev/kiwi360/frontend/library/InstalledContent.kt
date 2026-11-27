// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.library

import android.content.Context
import android.os.Environment
import android.os.StatFs
import dev.kiwi360.frontend.GameLauncher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One content type folder of a title: its updates, its downloadable content,
 * its installed data... in the internal content root or the SD card's.
 */
data class ContentItem(
    val xuid: String,
    val titleId: Long,
    val type: Long,
    val onSd: Boolean,
    val sizeBytes: Long,
    /** The folders' names as found, whatever their case. */
    internal val titleDir: String,
    internal val typeDir: String,
) {
    val key get() = "$xuid/$titleDir/$typeDir/${if (onSd) "sd" else "int"}"
}

/** Where content can live: the internal root always, the SD card's while one is mounted. */
object ContentStorage {
    /**
     * The content root on the SD card, in Kiwi's own folder there (no permission needed,
     * removed with the app). Null without a mounted removable volume. Xenia takes it as
     * content_root_secondary: a type folder there is used while the internal root has
     * none of the same title and type.
     */
    @JvmStatic
    fun sdRoot(context: Context): File? =
        context.getExternalFilesDirs(null).drop(1).firstOrNull { dir ->
            dir != null && Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED &&
                Environment.isExternalStorageRemovable(dir)
        }?.let { File(it, "content") }

    fun internalRoot(context: Context): File? = Profile.contentRoot(context)
}

/**
 * Installed content, listed by type folder (content_root/<XUID>/<TITLEID>/<TYPE>, its
 * headers in <TITLEID>/Headers/<TYPE>), to delete or to move between internal storage and
 * the SD card. Saved games and the profile are left out: they have their own backup.
 */
class InstalledContent(private val context: Context) {
    enum class Result { DONE, NO_SPACE, NO_SD, FAILED }

    fun sdAvailable() = ContentStorage.sdRoot(context) != null

    fun list(): List<ContentItem> {
        val roots = listOfNotNull(
            ContentStorage.internalRoot(context)?.let { it to false },
            ContentStorage.sdRoot(context)?.let { it to true },
        )
        val items = mutableListOf<ContentItem>()
        for ((root, onSd) in roots) {
            for (xuid in root.dirs().filter { it.name.isHex(16) }) {
                for (title in xuid.dirs().filter { it.name.isHex(8) && !it.name.equals(DASHBOARD, true) }) {
                    // A move cut short (the app killed halfway) leaves its partial copy.
                    if (!moving.get()) {
                        title.dirs().filter { it.name.startsWith(MOVING) }.forEach { it.deleteRecursively() }
                    }
                    for (type in title.dirs().filter { it.name.isHex(8) }) {
                        val typeId = type.name.toLong(16)
                        if (typeId in SAVES) continue
                        val size = type.sizeRecursive() + headersOf(title, type.name).sizeRecursive()
                        items += ContentItem(xuid.name, title.name.toLong(16), typeId, onSd, size, title.name, type.name)
                    }
                }
            }
        }
        return items.sortedWith(compareByDescending<ContentItem> { it.sizeBytes })
    }

    /** Free space where [item] would go if moved, in bytes; null without an SD card. */
    fun freeSpaceForMove(item: ContentItem): Long? = rootOf(!item.onSd)?.let { freeSpace(it) }

    /** Deletes [item] and its headers. Not for the UI thread. */
    fun delete(item: ContentItem): Result {
        val root = rootOf(item.onSd) ?: return Result.NO_SD
        GameLauncher.closeRunningGame(context)
        val title = titleDir(root, item)
        val type = File(title, typeName(item))
        val ok = type.deleteRecursively() and headersOf(title, typeName(item)).deleteRecursively()
        pruneEmpty(title)
        return if (ok) Result.DONE else Result.FAILED
    }

    /**
     * Moves [item] to the other storage. It is copied under a temporary name first and
     * takes its real name only once complete, so a cancelled or failed move leaves the
     * original as it was. [onProgress] gets bytes copied and the total.
     */
    suspend fun move(item: ContentItem, onProgress: (Long, Long) -> Unit): Result {
        val from = rootOf(item.onSd) ?: return Result.NO_SD
        val to = rootOf(!item.onSd) ?: return Result.NO_SD
        val name = typeName(item)
        val sourceTitle = titleDir(from, item)
        val targetTitle = titleDir(to, item)
        if (freeSpace(targetTitle) < item.sizeBytes + SPACE_MARGIN) return Result.NO_SPACE
        if (!moving.compareAndSet(false, true)) return Result.FAILED
        if (!targetTitle.isDirectory && !targetTitle.mkdirs()) {
            moving.set(false)
            return Result.FAILED
        }
        GameLauncher.closeRunningGame(context)
        val tempType = File(targetTitle, "$MOVING$name")
        val tempHeaders = File(targetTitle, "$MOVING${name}_headers")
        try {
            var done = 0L
            val progress = { n: Long -> done += n; onProgress(done, item.sizeBytes) }
            copyTree(File(sourceTitle, name), tempType, progress)
            val sourceHeaders = headersOf(sourceTitle, name)
            if (sourceHeaders.exists()) copyTree(sourceHeaders, tempHeaders, progress)
            withContext(NonCancellable) {
                // The data folder last: it is the one Xenia looks for.
                if (tempHeaders.exists()) place(tempHeaders, headersOf(targetTitle, name))
                place(tempType, File(targetTitle, name))
                File(sourceTitle, name).deleteRecursively()
                sourceHeaders.deleteRecursively()
                pruneEmpty(sourceTitle)
            }
            return Result.DONE
        } catch (e: IOException) {
            return Result.FAILED
        } finally {
            withContext(NonCancellable) {
                tempType.deleteRecursively()
                tempHeaders.deleteRecursively()
                pruneEmpty(targetTitle)
                moving.set(false)
            }
        }
    }

    private fun rootOf(onSd: Boolean): File? =
        if (onSd) ContentStorage.sdRoot(context) else ContentStorage.internalRoot(context)

    private fun titleDir(root: File, item: ContentItem) = File(root, "${item.xuid}/${item.titleDir}")

    private fun typeName(item: ContentItem) = item.typeDir

    private fun headersOf(title: File, type: String) = File(title, "$HEADERS/$type")

    /** Copies [from] into [to], file by file, stopping when the coroutine is cancelled. */
    private suspend fun copyTree(from: File, to: File, progress: (Long) -> Unit) {
        if (from.isDirectory) {
            if (!to.isDirectory && !to.mkdirs()) throw IOException("mkdir $to")
            for (child in from.listFiles().orEmpty()) copyTree(child, File(to, child.name), progress)
            return
        }
        val buffer = ByteArray(COPY_BUFFER)
        from.inputStream().use { input ->
            to.outputStream().use { output ->
                var unsynced = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    // Written out as it goes: otherwise gigabytes sit in the page cache, the
                    // progress runs ahead of the card and one final sync blocks a cancel.
                    unsynced += n
                    if (unsynced >= SYNC_EVERY) {
                        output.fd.sync()
                        unsynced = 0
                    }
                    progress(n.toLong())
                }
                output.fd.sync()
            }
        }
        if (to.length() != from.length()) throw IOException("short copy $to")
        to.setLastModified(from.lastModified())
    }

    /** Gives a complete copy its real name; into a folder that already exists, file by file. */
    private fun place(temp: File, target: File) {
        // Never an empty folder in place of the original, which goes next.
        if (!temp.exists()) throw IOException("missing $temp")
        if (!target.exists()) {
            target.parentFile?.mkdirs()
            if (temp.renameTo(target)) return
        }
        if (!target.isDirectory && !target.mkdirs()) throw IOException("mkdir $target")
        for (child in temp.listFiles().orEmpty()) {
            val dest = File(target, child.name)
            if (child.isDirectory) {
                place(child, dest)
            } else {
                dest.delete()
                if (!child.renameTo(dest)) throw IOException("rename $child")
            }
        }
        temp.deleteRecursively()
    }

    /** Removes the title's Headers folder, the title's and the XUID's when left empty. */
    private fun pruneEmpty(title: File) {
        File(title, HEADERS).takeIf { it.list()?.isEmpty() == true }?.delete()
        if (title.list()?.isEmpty() == true) title.delete()
        title.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
    }

    private fun freeSpace(dir: File): Long {
        var existing: File? = dir
        while (existing != null && !existing.exists()) existing = existing.parentFile
        return existing?.let { StatFs(it.path).availableBytes } ?: 0L
    }

    private companion object {
        const val HEADERS = "Headers"
        const val DASHBOARD = "FFFE07D1"
        const val MOVING = ".kiwi_moving_"
        const val COPY_BUFFER = 1 shl 20
        const val SYNC_EVERY = 32L shl 20
        const val SPACE_MARGIN = 64L shl 20
        /** Saved games (the 360's and original Xbox's): they go with the profile. */
        val SAVES = setOf(0x00000001L, 0x00060000L)
        /** Set during a move: its temporary copy is not a leftover. */
        val moving = AtomicBoolean(false)

        fun File.dirs(): List<File> = listFiles()?.filter { it.isDirectory }.orEmpty()

        fun String.isHex(length: Int) = this.length == length && all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }

        fun File.sizeRecursive(): Long = if (isDirectory) listFiles().orEmpty().sumOf { it.sizeRecursive() } else length()
    }
}
