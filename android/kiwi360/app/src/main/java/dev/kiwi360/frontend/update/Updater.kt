// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import jp.xenia.emulator.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** A published release with an APK newer than the installed version. */
data class Release(val tag: String, val name: String, val notes: String, val apkUrl: String, val apkSize: Long)

/** What a release check found. */
sealed interface UpdateCheck {
    data class Available(val release: Release) : UpdateCheck
    data object UpToDate : UpdateCheck
    /** No source answered (offline, server down). */
    data object Failed : UpdateCheck
}

/**
 * Checks the project's releases for a newer APK, downloads it and hands it to the system
 * installer. The project's own Forgejo is asked first and the GitHub mirror when it does
 * not answer. Releases are tagged vX.Y.Z and carry the signed APK as an asset.
 */
object Updater {
    private const val TAG = "Kiwi360-Updater"
    private const val USER_AGENT = "Kiwi360-Updater"

    /** The project's Forgejo and its GitHub mirror (repository API roots). */
    const val FORGEJO_REPO_API = "https://git.kiwi360.org/api/v1/repos/Kiwi360/Kiwi360"
    const val GITHUB_REPO_API = "https://api.github.com/repos/Ghael-V/Kiwi360"

    /**
     * Release lists asked in order. A build made with -PupdateUrl=<releases list URL> asks
     * only that one (a mock server), in a Forgejo/GitHub-shaped JSON array.
     */
    private val SOURCES: List<String>
        get() = BuildConfig.UPDATE_URL.takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: listOf(
            "$FORGEJO_REPO_API/releases?limit=20",
            "$GITHUB_REPO_API/releases?per_page=20",
        )

    private const val PREFS = "kiwi_updates"
    private const val KEY_CHECKS = "check_at_start"
    private const val KEY_SKIPPED = "skipped_release"

    /** Set once this process has done its start-up check. */
    @Volatile
    private var startCheckDone = false

    /**
     * Release builds only: the debug build is dev.kiwi360.proto and a release APK would
     * install next to it as another app. A build pointed at a mock server checks anyway.
     */
    val available: Boolean
        get() = !BuildConfig.DEBUG || BuildConfig.UPDATE_URL.isNotEmpty()

    val installedVersion: String get() = BuildConfig.VERSION_NAME

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Whether the library looks for a newer release when it starts; on by default. */
    fun checksAtStart(context: Context): Boolean = prefs(context).getBoolean(KEY_CHECKS, true)

    fun setChecksAtStart(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_CHECKS, value).apply()
    }

    /** The release the user chose to skip: no notice for it, or older ones, at start. */
    fun skippedRelease(context: Context): String = prefs(context).getString(KEY_SKIPPED, "").orEmpty()

    fun skip(context: Context, release: Release) {
        prefs(context).edit().putString(KEY_SKIPPED, release.tag).apply()
    }

    /**
     * The start-up check: at most once per process, only when [available] and enabled.
     * Returns the release to offer, or null (nothing new, skipped, offline or not run).
     */
    suspend fun checkAtStart(context: Context): Release? {
        if (startCheckDone || !available || !checksAtStart(context)) return null
        cleanUp(context)
        val result = check()
        startCheckDone = true
        val release = (result as? UpdateCheck.Available)?.release ?: return null
        val skipped = skippedRelease(context)
        return release.takeIf { skipped.isEmpty() || compareVersions(it.tag, skipped) > 0 }
    }

    /** Newest release above the installed version, from the first source that answers. */
    suspend fun check(current: String = installedVersion): UpdateCheck = withContext(Dispatchers.IO) {
        for (source in SOURCES) {
            val releases = runCatching { fetchReleases(source) }
                .onFailure { Log.w(TAG, "release check failed at $source: $it") }
                .getOrNull() ?: continue
            val newest = releases
                .filter { compareVersions(it.tag, current) > 0 }
                .maxWithOrNull { a, b -> compareVersions(a.tag, b.tag) }
            return@withContext newest?.let { UpdateCheck.Available(it) } ?: UpdateCheck.UpToDate
        }
        UpdateCheck.Failed
    }

    private fun fetchReleases(url: String): List<Release> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode}" }
            val array = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            return (0 until array.length()).mapNotNull { i ->
                val release = array.optJSONObject(i) ?: return@mapNotNull null
                if (release.optBoolean("draft")) return@mapNotNull null
                val tag = release.optString("tag_name")
                if (!VERSION_TAG.matches(tag)) return@mapNotNull null
                val assets = release.optJSONArray("assets") ?: return@mapNotNull null
                val apks = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                    .filter {
                        it.optString("name").endsWith(".apk", ignoreCase = true) &&
                            it.optString("browser_download_url").isNotEmpty()
                    }
                // Kiwi360-vX.Y.Z.apk; a release may also ship a debug build, users get the release one.
                val apk = apks.firstOrNull { it.optString("name").startsWith("Kiwi360", ignoreCase = true) }
                    ?: apks.firstOrNull { !it.optString("name").contains("debug", ignoreCase = true) }
                    ?: apks.firstOrNull() ?: return@mapNotNull null
                Release(
                    tag = tag,
                    name = release.optString("name").ifEmpty { tag },
                    notes = release.optString("body").replace("\r\n", "\n").trim(),
                    apkUrl = apk.optString("browser_download_url"),
                    apkSize = apk.optLong("size"),
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    /** vX.Y.Z, with any suffix such as -beta (ignored when comparing). */
    private val VERSION_TAG = Regex("""^[vV]\d+\.\d+\.\d+.*""")

    /**
     * Compares "v1.2.3"-style versions by their numeric parts; a leading "v" and any suffix
     * such as "-beta" or "-proto" are ignored.
     */
    fun compareVersions(a: String, b: String): Int {
        fun parts(version: String) = version.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-').substringBefore('+').split('.')
            .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val left = parts(a)
        val right = parts(b)
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    private fun updatesDir(context: Context) = File(context.cacheDir, "updates")

    private fun apkFile(context: Context, release: Release): File {
        val safeTag = release.tag.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(updatesDir(context).apply { mkdirs() }, "Kiwi360-$safeTag.apk")
    }

    /** At start nothing is downloading: earlier APKs (installed or abandoned) can go. */
    private fun cleanUp(context: Context) {
        runCatching { updatesDir(context).listFiles()?.forEach { it.delete() } }
    }

    /** Downloads the release's APK, reporting progress from 0 to 1; returns the file or null. */
    suspend fun download(context: Context, release: Release, onProgress: (Float) -> Unit): File? =
        withContext(Dispatchers.IO) {
            val target = apkFile(context, release)
            val partial = File(target.path + ".part")
            // Earlier downloads of other versions are no longer needed.
            target.parentFile?.listFiles()?.filter { it != target }?.forEach { it.delete() }
            runCatching {
                val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                try {
                    check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode}" }
                    val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
                    connection.inputStream.use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                    check(total <= 0 || partial.length() == total) { "incomplete download" }
                    target.delete()
                    check(partial.renameTo(target)) { "cannot move the download" }
                    target
                } finally {
                    connection.disconnect()
                }
            }.onFailure {
                Log.w(TAG, "download failed: $it")
                // A failed half-download must not linger in cache/updates.
                partial.delete()
            }.getOrNull()
        }

    /** False while Android still has to let this app install packages (see [requestInstallPermission]). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the "install unknown apps" switch for this app. */
    fun requestInstallPermission(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess

    /** Opens the system installer on [apk]; the user confirms there. */
    fun install(context: Context, apk: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { Log.w(TAG, "install failed: $it") }.isSuccess
}
