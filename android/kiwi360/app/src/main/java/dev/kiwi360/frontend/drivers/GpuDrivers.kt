// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.drivers

import android.content.Context
import androidx.annotation.StringRes
import jp.xenia.emulator.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.util.zip.ZipInputStream

/**
 * A custom Vulkan driver in the libadrenotools package layout: a folder with
 * meta.json and the driver library it names. The emulator loads it through
 * the vulkan_custom_driver_dir / vulkan_custom_driver_name cvars.
 */
data class GpuDriver(
    /** Folder name under the driver root; stable while installed. */
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val vendor: String,
    val libraryName: String,
    val dir: File,
)

/** An error whose text the user sees: a string resource, shown in their language. */
open class LocalizedIOException(@StringRes val textRes: Int, vararg val args: Any) : IOException()

class DriverInstallException(@StringRes textRes: Int, vararg args: Any) : LocalizedIOException(textRes, *args)

/** The text to show for an error while installing or downloading a driver. */
fun Throwable.userText(context: Context): String = when (this) {
    is LocalizedIOException -> context.getString(textRes, *args)
    is UnknownHostException, is ConnectException, is SocketTimeoutException, is SSLException ->
        context.getString(R.string.driver_error_network)
    else -> message ?: javaClass.simpleName
}

/**
 * Installed drivers, which one games use by default, and per-game overrides.
 * Lives in the app's internal storage: the driver library must not be on
 * shared storage for dlopen to accept it.
 */
class GpuDriverStore(context: Context) {
    val root = File(context.filesDir, "gpu_driver")
    private val prefs = context.getSharedPreferences("kiwi_gpu_drivers", Context.MODE_PRIVATE)

    fun installed(): List<GpuDriver> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .mapNotNull { readDriver(it) }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.version.lowercase() }))

    fun find(id: String): GpuDriver? =
        if (id.isEmpty() || id.contains('/') || id.startsWith(".")) null else readDriver(File(root, id))

    /** The driver games use unless they override it; "" means the system driver. */
    var defaultDriverId: String
        get() = prefs.getString(KEY_DEFAULT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEFAULT, value).apply()

    /** "" follows the default, [SYSTEM] forces the system driver, anything else is a driver id. */
    fun gameDriverId(gameKey: String): String = prefs.getString(KEY_GAME_PREFIX + gameKey, "") ?: ""

    fun setGameDriverId(gameKey: String, value: String) {
        prefs.edit().apply {
            if (value.isEmpty()) remove(KEY_GAME_PREFIX + gameKey) else putString(KEY_GAME_PREFIX + gameKey, value)
        }.apply()
    }

    /** The driver a game boots with, or null for the system driver. */
    fun driverFor(gameKey: String): GpuDriver? {
        val override = gameDriverId(gameKey)
        return when (override) {
            SYSTEM -> null
            "" -> find(defaultDriverId)
            else -> find(override) ?: find(defaultDriverId)
        }
    }

    /** Every game with its own driver choice: game key to [gameDriverId]'s value. */
    fun gameOverrides(): Map<String, String> =
        prefs.all.mapNotNull { (key, value) ->
            if (!key.startsWith(KEY_GAME_PREFIX) || value !is String || value.isEmpty()) null
            else key.removePrefix(KEY_GAME_PREFIX) to value
        }.toMap()

    /** How the logs name a driver: its name and version, or "system". */
    fun label(driver: GpuDriver?): String = driver?.let { "${it.name} ${it.version}".trim() } ?: "system"

    /** How the logs name a per-game choice, saying what it falls back to as [driverFor] does. */
    fun overrideLabel(id: String): String = when (id) {
        SYSTEM -> "system (forced for this game)"
        else -> find(id)?.let { label(it) } ?: "$id (not installed; uses the default, ${label(find(defaultDriverId))})"
    }

    fun delete(driver: GpuDriver) {
        if (driver.dir.parentFile?.canonicalPath != root.canonicalPath) return
        driver.dir.deleteRecursively()
        if (defaultDriverId == driver.id) defaultDriverId = ""
        prefs.all.keys.filter { it.startsWith(KEY_GAME_PREFIX) && prefs.getString(it, "") == driver.id }
            .forEach { prefs.edit().remove(it).apply() }
    }

    /**
     * Installs a driver package (a zip with meta.json and the library, at the
     * top level or inside one folder). A package of the same version replaces
     * the installed one.
     */
    fun install(zip: InputStream): GpuDriver {
        root.mkdirs()
        val staging = File(root, ".staging-${System.nanoTime()}")
        try {
            extract(zip, staging)
            val meta = staging.walkTopDown().maxDepth(2)
                .firstOrNull { it.isFile && it.name == "meta.json" }
                ?: throw DriverInstallException(R.string.driver_error_no_meta)
            val packageDir = meta.parentFile!!
            val staged = readDriver(packageDir)
                ?: throw DriverInstallException(R.string.driver_error_bad_meta)
            val library = File(packageDir, staged.libraryName)
            if (!isElf(library)) throw DriverInstallException(R.string.driver_error_not_driver, staged.libraryName)
            // The same driver and version again replaces the installed copy, wherever it is.
            val previous = installed().firstOrNull { it.name == staged.name && it.version == staged.version }
            val id = previous?.id ?: uniqueId(staged)
            val target = File(root, id)
            target.deleteRecursively()
            if (!packageDir.renameTo(target)) throw DriverInstallException(R.string.driver_error_move)
            return readDriver(target) ?: throw DriverInstallException(R.string.driver_error_unreadable)
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Installs the driver packages shipped in the APK (assets/drivers/\*.zip) the
     * first time each installed APK runs (a rebuilt driver of the same version
     * ships without a version bump, so the install time is the stamp); a bundled
     * driver the user deleted stays deleted until the next update. While the user hasn't chosen a default
     * driver, the first bundled one becomes the default on Qualcomm (KGSL) GPUs,
     * which are the ones these Turnip builds run on.
     */
    fun installBundled(context: Context) {
        val packages = try {
            context.assets.list(BUNDLED_DIR)?.filter { it.endsWith(".zip") }?.sorted()
        } catch (_: IOException) {
            null
        } ?: return
        val installStamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        var firstBundled: GpuDriver? = null
        for (name in packages) {
            val key = KEY_BUNDLED_PREFIX + name
            val installedId = prefs.getString(key + KEY_BUNDLED_ID, "") ?: ""
            val driver = if (prefs.getLong(key, -1L) == installStamp) {
                find(installedId)
            } else {
                try {
                    context.assets.open("$BUNDLED_DIR/$name").use { install(it) }.also {
                        prefs.edit().putLong(key, installStamp).putString(key + KEY_BUNDLED_ID, it.id).apply()
                    }
                } catch (_: IOException) {
                    null
                }
            }
            if (firstBundled == null) firstBundled = driver
        }
        val bundled = firstBundled ?: return
        if (!prefs.contains(KEY_DEFAULT) && File(KGSL_DEVICE).exists()) {
            defaultDriverId = bundled.id
        }
    }

    private fun uniqueId(driver: GpuDriver): String {
        val base = (driver.version.ifEmpty { driver.name })
            .replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_', '.').take(64)
        return base.ifEmpty { "driver-${System.currentTimeMillis()}" }
    }

    private fun extract(zip: InputStream, into: File) {
        into.mkdirs()
        val canonicalRoot = into.canonicalPath + File.separator
        var total = 0L
        ZipInputStream(zip.buffered()).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                val out = File(into, entry.name)
                // No entries escaping the staging folder.
                if (!out.canonicalPath.startsWith(canonicalRoot)) {
                    throw DriverInstallException(R.string.driver_error_bad_path, entry.name)
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                out.outputStream().use { file ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_PACKAGE_BYTES) throw DriverInstallException(R.string.driver_error_too_large)
                        file.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    private fun readDriver(dir: File): GpuDriver? {
        val metaFile = File(dir, "meta.json")
        if (!metaFile.isFile) return null
        return try {
            val meta = JSONObject(metaFile.readText())
            val libraryName = meta.optString("libraryName")
            // A bare file name inside the folder.
            if (libraryName.isEmpty() || libraryName.contains('/') || libraryName.startsWith(".")) return null
            if (!File(dir, libraryName).isFile) return null
            GpuDriver(
                id = dir.name,
                name = meta.optString("name").ifEmpty { dir.name },
                version = meta.optString("driverVersion"),
                description = meta.optString("description"),
                vendor = meta.optString("vendor"),
                libraryName = libraryName,
                dir = dir,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun isElf(file: File): Boolean = try {
        file.inputStream().use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 && magic[0] == 0x7F.toByte() && magic[1] == 'E'.code.toByte() &&
                magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte()
        }
    } catch (_: IOException) {
        false
    }

    companion object {
        const val SYSTEM = "system"
        private const val KEY_DEFAULT = "default"
        private const val KEY_GAME_PREFIX = "game:"
        private const val KEY_BUNDLED_PREFIX = "bundled:"
        private const val KEY_BUNDLED_ID = ":id"
        private const val BUNDLED_DIR = "drivers"
        private const val KGSL_DEVICE = "/sys/class/kgsl/kgsl-3d0"
        private const val MAX_PACKAGE_BYTES = 256L * 1024 * 1024
    }
}

/** A GitHub repository publishing driver packages as release assets. */
data class DriverRepo(val name: String, val path: String)

/** The same sources the Lemon emulators offer, Adreno drivers only. */
val DRIVER_REPOS = listOf(
    DriverRepo("Lemon-Ade Turnip", "Ghael-V/Turnip-Lemon-Ade"),
    DriverRepo("Mr. Purple Turnip", "MrPurple666/purple-turnip"),
    DriverRepo("GameHub Adreno 8xx", "crueter/GameHub-8Elite-Drivers"),
    DriverRepo("KIMCHI Turnip", "K11MCH1/AdrenoToolsDrivers"),
    DriverRepo("Weab-Chan Freedreno", "Weab-chan/freedreno_turnip-CI"),
    DriverRepo("Whitebelyash Turnip", "whitebelyash/freedreno_turnip-CI"),
    DriverRepo("StevenMXZ Turnip", "StevenMXZ/Adreno-Tools-Drivers"),
    DriverRepo("Balemuni Apex Turnip", "Balemuni/Balemunis-Aurora"),
)

data class DriverAsset(val name: String, val url: String, val size: Long)

data class DriverRelease(
    val title: String,
    val tag: String,
    /** ISO 8601, as GitHub returns it. */
    val published: String,
    val prerelease: Boolean,
    val assets: List<DriverAsset>,
)

/** Release listing and downloads from GitHub, with no account or token. */
object DriverDownloader {
    /** Newest first; releases without a zip asset are left out. */
    fun releases(repo: DriverRepo): List<DriverRelease> {
        val text = get(URL("https://api.github.com/repos/${repo.path}/releases?per_page=30")) { it.bufferedReader().readText() }
        val array = JSONArray(text)
        val result = ArrayList<DriverRelease>()
        for (i in 0 until array.length()) {
            val release = array.getJSONObject(i)
            if (release.optBoolean("draft")) continue
            val assets = release.optJSONArray("assets") ?: continue
            val zips = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.optString("name").endsWith(".zip", ignoreCase = true) }
                .map { DriverAsset(it.optString("name"), it.optString("browser_download_url"), it.optLong("size")) }
                .filter { it.url.startsWith("https://") }
            if (zips.isEmpty()) continue
            result += DriverRelease(
                title = release.optString("name").ifBlank { release.optString("tag_name") },
                tag = release.optString("tag_name"),
                published = release.optString("published_at"),
                prerelease = release.optBoolean("prerelease"),
                assets = zips,
            )
        }
        return result.sortedByDescending { it.published }
    }

    /** Downloads an asset to [dest], reporting (bytes so far, total or -1). */
    fun download(asset: DriverAsset, dest: File, progress: (Long, Long) -> Unit) {
        get(URL(asset.url)) { input ->
            val total = asset.size.takeIf { it > 0 } ?: -1L
            dest.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                var reported = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    done += read
                    if (done - reported >= 256 * 1024) {
                        progress(done, total)
                        reported = done
                    }
                }
                progress(done, total)
            }
        }
    }

    private fun <T> get(url: URL, read: (InputStream) -> T): T {
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            // A generic client name: no user or device details leave the device.
            connection.setRequestProperty("User-Agent", "Kiwi360")
            if (url.host == "api.github.com") {
                connection.setRequestProperty("Accept", "application/vnd.github+json")
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                throw LocalizedIOException(
                    if (code == 403 || code == 429) R.string.driver_error_rate_limit else R.string.driver_error_http,
                    code,
                )
            }
            return connection.inputStream.use(read)
        } finally {
            connection.disconnect()
        }
    }
}
