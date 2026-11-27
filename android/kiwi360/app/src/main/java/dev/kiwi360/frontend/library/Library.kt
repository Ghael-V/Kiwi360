// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.library

import android.content.Context
import android.os.storage.StorageManager
import dev.kiwi360.frontend.settings.ConsoleLanguage
import org.json.JSONObject
import java.io.File

/** One launchable file: a disc image, a loose XEX or an STFS package. */
data class GameFile(
    val path: String,
    val format: GameFormat,
    val titleId: Long,
    val discNumber: Int,
    val discCount: Int,
    val version: String,
)

/**
 * Settings the library passes on at launch, for every game (Settings) or for one
 * (its options). "" means: for a game, the global value; globally, the
 * emulator's default (for the language, the device's). Values are what
 * launch() passes on.
 */
enum class GameOption(val key: String, val values: List<String>) {
    RESOLUTION("resolution", listOf("", "1", "2", "3")),
    HUD("hud", listOf("", "full", "fps", "off")),
    RTZ("rtz", listOf("", "on", "off")),
    LICENSE("license", listOf("", "on")),
    LANGUAGE("language", listOf("") + ConsoleLanguage.LANGUAGES.map { it.first.toString() }),
    ;

    /** The global value always has one HUD mode: it is what a game without its own shows. */
    val globalValues: List<String> get() = if (this == HUD) listOf("full", "fps", "off") else values
}

/** What Kiwi knows about one game file, for other apps. */
data class TitleInfo(
    val titleId: Long,
    val name: String?,
    val icon: File?,
    val disc: Int,
    val discCount: Int,
    /** When the name or icon last changed, in ms; 0 if there is neither. */
    val updatedAt: Long,
)

/** A title as the library shows it: every disc of it in one entry. */
data class Game(
    val key: String,
    val titleId: Long,
    val name: String,
    /** True when [name] came from the game itself rather than its file name. */
    val hasRealName: Boolean,
    val files: List<GameFile>,
    val iconPath: String?,
    val coverPath: String?,
    val lastPlayed: Long,
) {
    val titleIdHex: String get() = "%08X".format(titleId)
    val discCount: Int get() = maxOf(files.size, files.maxOf { it.discCount })

    /** The file to boot by default: the last one played, else the lowest disc. */
    fun defaultFile(lastPath: String?): GameFile =
        files.firstOrNull { it.path == lastPath } ?: files.minBy { it.discNumber }
}

/**
 * Finds games in the user's folders and keeps what it learnt in a cache, so a
 * rescan only probes new or changed files.
 */
class Library(private val context: Context) {
    private val prefs = context.getSharedPreferences("kiwi_library", Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, "library_cache.json")
    private val iconDir = File(context.filesDir, "icons").apply { mkdirs() }

    /** Where the emulator process writes the title name and icon it read at boot. */
    private val emulatorMetadataDir: File?
        get() = context.getExternalFilesDir(null)?.let { File(it, "kiwi_library") }

    var folders: Set<String>
        get() = prefs.getStringSet(KEY_FOLDERS, null) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_FOLDERS, value.toSet()).apply()

    val hasChosenFolders: Boolean get() = prefs.contains(KEY_FOLDERS)

    var hudMode: String
        get() = prefs.getString(KEY_HUD, "fps") ?: "fps"
        set(value) = prefs.edit().putString(KEY_HUD, value).apply()

    var gamertag: String
        get() = prefs.getString(KEY_GAMERTAG, "Kiwi360") ?: "Kiwi360"
        set(value) = prefs.edit().putString(KEY_GAMERTAG, value).apply()

    var consoleName: String
        get() = prefs.getString(KEY_CONSOLE, "Kiwi360") ?: "Kiwi360"
        set(value) = prefs.edit().putString(KEY_CONSOLE, value).apply()

    fun gameOption(key: String, option: GameOption): String =
        prefs.getString("opt_${key}_${option.key}", "") ?: ""

    fun setGameOption(key: String, option: GameOption, value: String) {
        prefs.edit().putString("opt_${key}_${option.key}", value).apply()
    }

    /** The value for every game; the HUD one is [hudMode]. */
    fun globalOption(option: GameOption): String =
        if (option == GameOption.HUD) hudMode else prefs.getString("global_${option.key}", "") ?: ""

    fun setGlobalOption(option: GameOption, value: String) {
        if (option == GameOption.HUD) {
            hudMode = value
        } else {
            prefs.edit().putString("global_${option.key}", value).apply()
        }
    }

    /** What a launch of the game uses: its own value, else the global one. */
    fun effectiveOption(key: String, option: GameOption): String =
        gameOption(key, option).ifEmpty { globalOption(option) }

    fun lastPlayedPath(game: Game): String? = prefs.getString("last_file_${game.key}", null)

    /** When the game was last launched (epoch ms), or 0. */
    fun lastLaunched(key: String): Long = prefs.getLong("last_played_$key", 0L)

    fun recordLaunch(game: Game, file: GameFile) {
        prefs.edit()
            .putLong("last_played_${game.key}", System.currentTimeMillis())
            .putString("last_file_${game.key}", file.path)
            .apply()
    }

    /** Folders named ROMs/x360 on any storage volume: the usual layout on handhelds. */
    fun suggestedFolders(): List<File> = storageRoots().flatMap { root ->
        listOf("ROMs/x360", "ROMs/xbox360", "roms/x360", "x360").map { File(root, it) }
    }.filter { it.isDirectory }
        // SD cards are FAT: ROMs/x360 and roms/x360 are the same folder.
        .distinctBy { it.canonicalPath.lowercase() }

    fun storageRoots(): List<File> {
        val storage = context.getSystemService(StorageManager::class.java)
        return storage.storageVolumes.mapNotNull { it.directory }.filter { it.canRead() }
    }

    /** Scans the folders, probing only files the cache doesn't know. Runs on a worker thread. */
    fun scan(): List<Game> {
        val cache = loadCache()
        val found = mutableListOf<GameFile>()
        val seen = mutableSetOf<String>()
        for (folder in folders) {
            walk(File(folder), 0) { file ->
                val path = file.absolutePath
                // Case-insensitive: the same file can be reached as ROMs/... and roms/... on FAT.
                if (!seen.add(path.lowercase())) return@walk
                val stamp = "${file.length()}:${file.lastModified()}"
                val cached = cache.optJSONObject(path)
                val entry = if (cached != null && cached.optString("stamp") == stamp) {
                    cached
                } else {
                    val probe = GameProbe.probe(file)
                    JSONObject().apply {
                        put("stamp", stamp)
                        if (probe != null) {
                            put("format", probe.format.name)
                            put("title_id", probe.titleId)
                            put("disc", probe.discNumber)
                            put("discs", probe.discCount)
                            put("version", probe.version)
                            probe.name?.let { put("name", it) }
                            probe.icon?.let { icon ->
                                File(iconDir, "%08X.png".format(probe.titleId)).writeBytes(icon)
                            }
                        }
                    }.also { cache.put(path, it) }
                }
                if (entry.has("title_id")) {
                    found += GameFile(
                        path = path,
                        format = GameFormat.valueOf(entry.getString("format")),
                        titleId = entry.getLong("title_id"),
                        discNumber = entry.optInt("disc", 1),
                        discCount = entry.optInt("discs", 1),
                        version = entry.optString("version"),
                    )
                }
            }
        }
        // The title's own name and icon, unpacked from its executable once per title.
        val named = found.filter { file ->
            cache.optJSONObject(file.path)?.optString("name")?.isNotBlank() == true
        }.map { it.titleId }.toMutableSet()
        for (file in found) {
            if (file.format == GameFormat.STFS || file.titleId in named) continue
            val entry = cache.optJSONObject(file.path) ?: continue
            if (entry.optBoolean("native_read")) continue
            val icon = File(iconDir, "%08X.png".format(file.titleId))
            val name = NativeTitleInfo.read(file.path, icon.absolutePath)
            entry.put("native_read", true)
            if (name != null) {
                entry.put("name", name)
                named += file.titleId
            }
        }
        // Forget files that are gone.
        cache.keys().asSequence().toList().filter { it.lowercase() !in seen }.forEach { cache.remove(it) }
        cacheFile.writeText(cache.toString())
        notifyIfMetadataChanged()
        return group(found, cache)
    }

    /** What the last scan found, straight from the cache: shown while a new scan runs. */
    fun cachedGames(): List<Game> {
        val cache = loadCache()
        val files = cache.keys().asSequence().mapNotNull { path ->
            val entry = cache.optJSONObject(path) ?: return@mapNotNull null
            if (!entry.has("title_id")) return@mapNotNull null
            GameFile(
                path = path,
                format = GameFormat.valueOf(entry.getString("format")),
                titleId = entry.getLong("title_id"),
                discNumber = entry.optInt("disc", 1),
                discCount = entry.optInt("discs", 1),
                version = entry.optString("version"),
            )
        }.toList()
        return group(files, cache)
    }

    /** Rebuilds the entries from the cache alone, to pick up names the emulator just exported. */
    fun refresh(games: List<Game>): List<Game> {
        val cache = loadCache()
        notifyIfMetadataChanged()
        return group(games.flatMap { it.files }, cache)
    }

    /**
     * What Kiwi knows about the game file at [path], for other apps (TitleProvider):
     * null if it isn't an Xbox 360 game Kiwi can read. Writes nothing; a file the
     * library hasn't scanned is probed here and not added to the cache.
     */
    fun titleInfo(path: String): TitleInfo? {
        val file = File(path)
        if (!file.isFile) return null
        val cache = loadCache()
        val entry = cache.keys().asSequence()
            .firstOrNull { it.equals(file.absolutePath, ignoreCase = true) }
            ?.let { cache.optJSONObject(it) }?.takeIf { it.has("title_id") }
        val titleId: Long
        val disc: Int
        val discCount: Int
        if (entry != null) {
            titleId = entry.getLong("title_id")
            disc = entry.optInt("disc", 1)
            discCount = entry.optInt("discs", 1)
        } else {
            val probe = GameProbe.probe(file) ?: return null
            titleId = probe.titleId
            disc = probe.discNumber
            discCount = probe.discCount
        }
        if (titleId == 0L) return null
        val key = "%08X".format(titleId)
        // The same name the library shows: exported by the emulator, else from the package.
        val exported = emulatorMetadataDir?.let { File(it, "$key.txt") }?.takeIf { it.isFile }
        val name = exported?.readText()?.trim()?.ifBlank { null }
            ?: cache.keys().asSequence().mapNotNull { cache.optJSONObject(it) }
                .firstOrNull { it.optLong("title_id") == titleId && it.optString("name").isNotBlank() }
                ?.optString("name")
        val icon = iconFile(key)
        // Names and icons are written together, so their files date the change; a name
        // with neither (unlikely) dates from the last scan.
        val updatedAt = listOfNotNull(exported, icon).maxOfOrNull { it.lastModified() }
            ?: if (name != null) cacheFile.lastModified() else 0L
        return TitleInfo(titleId, name, icon, disc, discCount, updatedAt)
    }

    /** A title's icon: the one the emulator exported, else the one the scan unpacked. */
    fun iconFile(key: String): File? = listOfNotNull(
        emulatorMetadataDir?.let { File(it, "$key.png") },
        File(iconDir, "$key.png"),
    ).firstOrNull { it.isFile }

    /** Tells other apps (TitleProvider observers) when a name or icon was added or changed. */
    private fun notifyIfMetadataChanged() {
        val stamp = listOfNotNull(emulatorMetadataDir, iconDir)
            .flatMap { it.listFiles()?.asList() ?: emptyList() }
            .maxOfOrNull { it.lastModified() } ?: 0L
        if (stamp > lastNotifiedStamp) {
            lastNotifiedStamp = stamp
            context.contentResolver.notifyChange(TitleProvider.titleUri(context), null)
        }
    }

    private fun group(files: List<GameFile>, cache: JSONObject): List<Game> =
        files.groupBy { if (it.titleId != 0L) "%08X".format(it.titleId) else it.path }
            .map { (key, discs) ->
                val sorted = discs.sortedWith(compareBy({ it.discNumber }, { it.path }))
                val first = sorted.first()
                val exported = emulatorMetadataDir?.let { File(it, "$key.txt") }
                    ?.takeIf { it.isFile }?.readText()?.trim()?.ifBlank { null }
                val packaged = sorted.firstNotNullOfOrNull {
                    cache.optJSONObject(it.path)?.optString("name")?.ifBlank { null }
                }
                val realName = exported ?: packaged
                Game(
                    key = key,
                    titleId = first.titleId,
                    name = realName ?: prettyName(File(first.path)),
                    hasRealName = realName != null,
                    files = sorted,
                    iconPath = iconFile(key)?.absolutePath,
                    coverPath = findCover(sorted, key),
                    lastPlayed = prefs.getLong("last_played_$key", 0L),
                )
            }
            .sortedBy { it.name.lowercase() }

    /** A cover image the user placed next to the game, by file name or title ID. */
    private fun findCover(files: List<GameFile>, key: String): String? {
        val candidates = mutableListOf<File>()
        for (file in files) {
            val f = File(file.path)
            val dir = f.parentFile ?: continue
            val stems = listOf(f.nameWithoutExtension, stripDisc(f.nameWithoutExtension), key)
            for (stem in stems.distinct()) {
                for (ext in IMAGE_EXTENSIONS) {
                    candidates += File(dir, "$stem.$ext")
                    candidates += File(dir, "covers/$stem.$ext")
                    dir.parentFile?.let { candidates += File(it, "covers/$stem.$ext") }
                }
            }
            if (f.isFile && f.extension.isEmpty()) {
                // GoD packages live in <title>/<type>/<file>; a cover may sit beside the title folder.
                dir.parentFile?.parentFile?.let { top ->
                    IMAGE_EXTENSIONS.forEach { candidates += File(top, "$key.$it") }
                }
            }
        }
        return candidates.firstOrNull { it.isFile }?.absolutePath
    }

    private fun walk(dir: File, depth: Int, onFile: (File) -> Unit) {
        if (depth > MAX_DEPTH) return
        val entries = dir.listFiles() ?: return
        for (entry in entries) {
            if (entry.isDirectory) {
                // STFS data folders hold thousands of blocks and never a game.
                if (!entry.name.endsWith(".data", ignoreCase = true) && !entry.name.startsWith(".")) {
                    walk(entry, depth + 1, onFile)
                }
            } else if (isCandidate(entry)) {
                onFile(entry)
            }
        }
    }

    private fun isCandidate(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext == "iso" || file.name.equals("default.xex", ignoreCase = true) ||
            (ext.isEmpty() && file.length() > 0x971A)
    }

    private fun loadCache(): JSONObject = try {
        if (cacheFile.isFile) JSONObject(cacheFile.readText()) else JSONObject()
    } catch (_: Exception) {
        JSONObject()
    }

    companion object {
        /** Newest name or icon file other apps were told about, for this process. */
        @Volatile
        private var lastNotifiedStamp = 0L

        private const val KEY_FOLDERS = "folders"
        private const val KEY_HUD = "hud"
        private const val KEY_GAMERTAG = "gamertag"
        private const val KEY_CONSOLE = "console_name"
        private const val MAX_DEPTH = 4
        private val IMAGE_EXTENSIONS = listOf("png", "jpg", "jpeg", "webp")
        private val DISC_SUFFIX = Regex("""(?i)[\s_.\-(\[]*(disc|disco|cd|dvd)[\s_.\-]*\d+.*$""")

        fun stripDisc(stem: String) = stem.replace(DISC_SUFFIX, "").ifBlank { stem }

        /** A readable name from a file name, until the game provides its own. */
        fun prettyName(file: File): String {
            var stem = if (file.name.equals("default.xex", ignoreCase = true)) {
                file.parentFile?.name ?: file.name
            } else {
                file.nameWithoutExtension
            }
            stem = stripDisc(stem).replace('_', ' ').replace('.', ' ')
                .replace(Regex("""\s+"""), " ").trim()
            return if (stem == stem.lowercase()) {
                stem.split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
            } else {
                stem
            }
        }
    }
}
