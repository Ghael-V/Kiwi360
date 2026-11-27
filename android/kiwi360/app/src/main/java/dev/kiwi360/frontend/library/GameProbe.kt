// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.library

import java.io.File
import java.io.RandomAccessFile

/** What a game file says about itself without running it. */
data class ProbeResult(
    val format: GameFormat,
    val titleId: Long,
    val mediaId: Long,
    val discNumber: Int,
    val discCount: Int,
    val version: String,
    /** Only STFS packages carry a readable name; disc images hide it in the encrypted XEX. */
    val name: String? = null,
    /** PNG bytes of the package's title image, STFS only. */
    val icon: ByteArray? = null,
)

enum class GameFormat { ISO, XEX, STFS }

/**
 * Reads title metadata from Xbox 360 game files: GDFX disc images (through
 * the default.xex execution info header), loose XEX files and STFS packages
 * (Games on Demand / Arcade). Everything read here is unencrypted header data.
 */
object GameProbe {
    private const val SECTOR = 2048L
    // Byte offsets of the game partition in the disc image layouts in use.
    private val PARTITION_OFFSETS = longArrayOf(0x0, 0xFB20, 0x20600, 0x2080000, 0xFD90000)
    private val GDFX_MAGIC = "MICROSOFT*XBOX*MEDIA".toByteArray(Charsets.US_ASCII)
    private const val XEX2_MAGIC = 0x58455832L
    private const val XEX_EXECUTION_INFO = 0x00040006L
    private const val XEX_HEADER_READ = 64 * 1024

    private const val STFS_CONTENT_GOD = 0x7000L
    private const val STFS_CONTENT_ARCADE = 0xD0000L

    fun probe(file: File): ProbeResult? = try {
        RandomAccessFile(file, "r").use { raf ->
            val magic = read(raf, 0, 4) ?: return null
            when {
                magic.contentEquals("CON ".toByteArray()) ||
                    magic.contentEquals("LIVE".toByteArray()) ||
                    magic.contentEquals("PIRS".toByteArray()) -> probeStfs(raf)
                be32(magic, 0) == XEX2_MAGIC ->
                    read(raf, 0, minOf(raf.length(), XEX_HEADER_READ.toLong()).toInt())
                        ?.let { parseXex(it, GameFormat.XEX) }
                else -> probeIso(raf)
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun probeIso(raf: RandomAccessFile): ProbeResult? {
        for (base in PARTITION_OFFSETS) {
            val volume = read(raf, base + 32 * SECTOR, 28) ?: continue
            if (!volume.copyOfRange(0, GDFX_MAGIC.size).contentEquals(GDFX_MAGIC)) continue
            val rootSector = le32(volume, 20)
            val rootSize = le32(volume, 24)
            if (rootSize < 13 || rootSize > 32L * 1024 * 1024) continue
            val root = read(raf, base + rootSector * SECTOR, rootSize.toInt()) ?: continue
            val (sector, length) = findFile(root, 0, "default.xex", 0) ?: continue
            val header = read(raf, base + sector * SECTOR,
                minOf(length, XEX_HEADER_READ.toLong()).toInt()) ?: continue
            return parseXex(header, GameFormat.ISO)
        }
        return null
    }

    /** Walks a GDFX directory tree (binary tree of entries in 4-byte units). */
    private fun findFile(dir: ByteArray, ordinal: Int, name: String, depth: Int): Pair<Long, Long>? {
        if (depth > 100) return null
        val offset = ordinal * 4
        if (offset + 14 > dir.size) return null
        val left = le16(dir, offset)
        val right = le16(dir, offset + 2)
        if (left == 0xFFFF) {
            // Padding up to the next sector.
            if (offset % SECTOR.toInt() == 0) return null
            val next = ((offset / SECTOR.toInt() + 1) * SECTOR.toInt()) / 4
            return findFile(dir, next, name, depth + 1)
        }
        val nameLength = dir[offset + 13].toInt() and 0xFF
        if (offset + 14 + nameLength > dir.size) return null
        val isDirectory = (dir[offset + 12].toInt() and 0x10) != 0
        val entryName = String(dir, offset + 14, nameLength, Charsets.ISO_8859_1)
        if (!isDirectory && entryName.equals(name, ignoreCase = true)) {
            return le32(dir, offset + 4) to le32(dir, offset + 8)
        }
        if (left != 0) findFile(dir, left, name, depth + 1)?.let { return it }
        if (right != 0) findFile(dir, right, name, depth + 1)?.let { return it }
        return null
    }

    private fun parseXex(h: ByteArray, format: GameFormat): ProbeResult? {
        if (h.size < 24 || be32(h, 0) != XEX2_MAGIC) return null
        val count = be32(h, 20)
        if (count > 256) return null
        for (i in 0 until count.toInt()) {
            val keyOffset = 24 + i * 8
            if (keyOffset + 8 > h.size) break
            if (be32(h, keyOffset) != XEX_EXECUTION_INFO) continue
            val info = be32(h, keyOffset + 4).toInt()
            if (info < 0 || info + 24 > h.size) return null
            return ProbeResult(
                format = format,
                titleId = be32(h, info + 12),
                mediaId = be32(h, info),
                discNumber = h[info + 18].toInt() and 0xFF,
                discCount = h[info + 19].toInt() and 0xFF,
                version = formatVersion(be32(h, info + 4)),
            )
        }
        return null
    }

    private fun probeStfs(raf: RandomAccessFile): ProbeResult? {
        val h = read(raf, 0, 0x971A) ?: read(raf, 0, 0x1800) ?: return null
        val contentType = be32(h, 0x344)
        if (contentType != STFS_CONTENT_GOD && contentType != STFS_CONTENT_ARCADE) return null
        val titleName = utf16be(h, 0x1691, 0x80)
        val displayName = utf16be(h, 0x411, 0x80)
        // The title image follows the package image; its place depends on the
        // metadata version, so take whichever candidate is a PNG.
        val titleImageSize = be32(h, 0x1716).toInt()
        val icon = listOf(0x571A, 0x541A).firstNotNullOfOrNull { offset ->
            if (titleImageSize in 8..0x4000 && offset + titleImageSize <= h.size &&
                isPng(h, offset)) h.copyOfRange(offset, offset + titleImageSize) else null
        } ?: run {
            val size = be32(h, 0x1712).toInt()
            if (size in 8..0x4000 && 0x171A + size <= h.size && isPng(h, 0x171A))
                h.copyOfRange(0x171A, 0x171A + size) else null
        }
        return ProbeResult(
            format = GameFormat.STFS,
            titleId = be32(h, 0x360),
            mediaId = be32(h, 0x354),
            discNumber = h[0x366].toInt() and 0xFF,
            discCount = h[0x367].toInt() and 0xFF,
            version = formatVersion(be32(h, 0x358)),
            name = titleName.ifBlank { displayName }.ifBlank { null },
            icon = icon,
        )
    }

    private fun formatVersion(v: Long) =
        "${(v shr 28) and 0xF}.${(v shr 24) and 0xF}.${(v shr 8) and 0xFFFF}.${v and 0xFF}"

    private fun isPng(b: ByteArray, offset: Int) = offset + 4 <= b.size &&
        b[offset] == 0x89.toByte() && b[offset + 1] == 'P'.code.toByte() &&
        b[offset + 2] == 'N'.code.toByte() && b[offset + 3] == 'G'.code.toByte()

    private fun utf16be(b: ByteArray, offset: Int, length: Int): String {
        if (offset + length > b.size) return ""
        val s = String(b, offset, length, Charsets.UTF_16BE)
        val end = s.indexOf('\u0000')
        return (if (end >= 0) s.substring(0, end) else s).trim()
    }

    private fun read(raf: RandomAccessFile, offset: Long, length: Int): ByteArray? {
        if (offset < 0 || length < 0 || offset + length > raf.length()) return null
        val buffer = ByteArray(length)
        raf.seek(offset)
        raf.readFully(buffer)
        return buffer
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int) = (b[o].toLong() and 0xFF) or
        ((b[o + 1].toLong() and 0xFF) shl 8) or ((b[o + 2].toLong() and 0xFF) shl 16) or
        ((b[o + 3].toLong() and 0xFF) shl 24)

    private fun be32(b: ByteArray, o: Int) = ((b[o].toLong() and 0xFF) shl 24) or
        ((b[o + 1].toLong() and 0xFF) shl 16) or ((b[o + 2].toLong() and 0xFF) shl 8) or
        (b[o + 3].toLong() and 0xFF)
}
