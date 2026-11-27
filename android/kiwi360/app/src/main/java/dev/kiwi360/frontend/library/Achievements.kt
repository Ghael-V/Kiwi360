// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.library

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One achievement of a title, as its profile GPD keeps it. */
data class Achievement(
    val id: Int,
    val name: String,
    /** What it shows once unlocked. */
    val description: String,
    /** What it shows while locked (how to get it). */
    val lockedDescription: String,
    val unlocked: Boolean,
    /** Epoch ms, or null if locked or the console didn't record it. */
    val unlockedAt: Long?,
    val points: Int,
    /** A secret achievement: its name and description stay hidden until unlocked. */
    val hidden: Boolean,
    val imageId: Int,
    /** True if the GPD holds its picture (Xenia copies it there on unlock). */
    val hasIcon: Boolean,
)

/** A title's achievement totals, from the dashboard GPD. */
data class AchievementSummary(
    val titleId: Long,
    val name: String,
    val total: Int,
    val unlocked: Int,
    val pointsTotal: Int,
    val pointsEarned: Int,
    /** Epoch ms, 0 if unknown. */
    val lastPlayed: Long,
)

/**
 * Reads achievements outside a game, from the profile Xenia keeps on storage:
 * content/<XUID>/FFFE07D1/00010000/<XUID>/ holds one GPD (an XDBF database)
 * per title played, with every achievement of the title, plus FFFE07D1.gpd
 * with the totals of each title. Read only; a file being written by the
 * emulator just reads as empty.
 */
class AchievementStore(context: Context) {
    private val profileDir: File? by lazy { Profile.activeDir(context) }

    /** The title's GPD file, or null if the profile has none. Built from the number only. */
    fun titleGpd(titleId: Long): File? =
        profileDir?.let { File(Profile.gpdDir(it), "%08X.gpd".format(titleId)) }?.takeIf { it.isFile }

    /** Totals of every title the profile has played, newest first. */
    fun summaries(): List<AchievementSummary> {
        val file = profileDir?.let { Profile.dashboardGpd(it) } ?: return emptyList()
        return Xdbf.read(file).filter { it.namespace == NS_TITLE }.mapNotNull { entry ->
            val b = entry.data
            if (b.remaining() < 40) return@mapNotNull null
            AchievementSummary(
                titleId = b.getInt(0).toLong() and 0xFFFFFFFFL,
                total = b.getInt(4),
                unlocked = b.getInt(8),
                pointsTotal = b.getInt(12),
                pointsEarned = b.getInt(16),
                lastPlayed = fileTimeToMillis(b.getLong(32)) ?: 0L,
                name = utf16Strings(b, 40).firstOrNull().orEmpty(),
            )
        }.filter { it.total > 0 }.sortedByDescending { it.lastPlayed }
    }

    /** Every achievement of the title, in the game's order; empty if it has none. */
    fun achievements(titleId: Long): List<Achievement> {
        val entries = titleGpd(titleId)?.let { Xdbf.read(it) } ?: return emptyList()
        val images = entries.filter { it.namespace == NS_IMAGE }.map { it.id }.toSet()
        return entries.filter { it.namespace == NS_ACHIEVEMENT }.mapNotNull { entry ->
            val b = entry.data
            if (b.remaining() < 0x1C) return@mapNotNull null
            val flags = b.getInt(16)
            val strings = utf16Strings(b, 0x1C)
            val imageId = b.getInt(8)
            val unlocked = flags and (FLAG_ACHIEVED or FLAG_ACHIEVED_ONLINE) != 0
            Achievement(
                id = b.getInt(4),
                name = strings.getOrNull(0).orEmpty(),
                description = strings.getOrNull(1).orEmpty(),
                lockedDescription = strings.getOrNull(2)?.ifBlank { null } ?: strings.getOrNull(1).orEmpty(),
                unlocked = unlocked,
                unlockedAt = if (unlocked) fileTimeToMillis(b.getLong(20)) else null,
                points = b.getInt(12),
                hidden = flags and FLAG_SHOW_UNACHIEVED == 0,
                imageId = imageId,
                hasIcon = imageId.toLong() in images,
            )
        }.sortedBy { it.id }
    }

    /** The PNG of an achievement's picture, if the GPD holds it. */
    fun achievementIcon(titleId: Long, achievementId: Int): ByteArray? {
        val entries = titleGpd(titleId)?.let { Xdbf.read(it) } ?: return null
        val imageId = entries.firstOrNull { it.namespace == NS_ACHIEVEMENT && it.data.remaining() >= 12 && it.data.getInt(4) == achievementId }
            ?.data?.getInt(8) ?: return null
        val image = entries.firstOrNull { it.namespace == NS_IMAGE && it.id == imageId.toLong() } ?: return null
        return ByteArray(image.data.remaining()).also { image.data.duplicate().get(it) }
    }

    private companion object {
        const val NS_ACHIEVEMENT = 1
        const val NS_IMAGE = 2
        const val NS_TITLE = 4
        const val FLAG_SHOW_UNACHIEVED = 0x8
        const val FLAG_ACHIEVED_ONLINE = 0x10000
        const val FLAG_ACHIEVED = 0x20000

        /** Windows FILETIME (100 ns since 1601) to epoch ms; null for 0. */
        fun fileTimeToMillis(fileTime: Long): Long? =
            if (fileTime <= 0L) null else (fileTime - 116444736000000000L) / 10000L

        /** NUL-terminated big-endian UTF-16 strings from [offset] to the end. */
        fun utf16Strings(b: ByteBuffer, offset: Int): List<String> {
            val strings = mutableListOf<String>()
            val current = StringBuilder()
            var i = offset
            while (i + 1 < b.limit()) {
                val c = b.getChar(i)
                if (c == '\u0000') {
                    strings += current.toString()
                    current.clear()
                } else {
                    current.append(c)
                }
                i += 2
            }
            if (current.isNotEmpty()) strings += current.toString()
            return strings
        }
    }
}

/** Minimal reader of XDBF databases (GPD and SPA files): header, entry table, data. */
internal object Xdbf {
    class Entry(val namespace: Int, val id: Long, val data: ByteBuffer)

    private const val MAGIC = 0x58444246 // "XDBF"
    private const val MAX_SIZE = 16 shl 20

    fun read(file: File): List<Entry> = try {
        if (file.length() in 24..MAX_SIZE) parse(file.readBytes()) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun parse(bytes: ByteArray): List<Entry> {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (b.getInt(0) != MAGIC) return emptyList()
        val tableLength = b.getInt(8)
        val count = b.getInt(12)
        val freeLength = b.getInt(16)
        if (tableLength < 0 || freeLength < 0 || count < 0 || count > tableLength) return emptyList()
        val base = 24L + tableLength * 18L + freeLength * 8L
        if (base > bytes.size) return emptyList()
        val entries = ArrayList<Entry>(count)
        for (i in 0 until count) {
            val at = 24 + i * 18
            val namespace = b.getShort(at).toInt() and 0xFFFF
            val id = b.getLong(at + 2)
            val offset = b.getInt(at + 10).toLong() and 0xFFFFFFFFL
            val length = b.getInt(at + 14).toLong() and 0xFFFFFFFFL
            val start = base + offset
            if (start + length > bytes.size) continue
            val data = ByteBuffer.wrap(bytes, start.toInt(), length.toInt()).slice().order(ByteOrder.BIG_ENDIAN)
            entries += Entry(namespace, id, data)
        }
        return entries
    }
}
