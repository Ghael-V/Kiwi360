// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.ui

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kiwi360.frontend.drivers.DRIVER_REPOS
import dev.kiwi360.frontend.drivers.DriverAsset
import dev.kiwi360.frontend.drivers.DriverDownloader
import dev.kiwi360.frontend.drivers.DriverRelease
import dev.kiwi360.frontend.drivers.GpuDriver
import dev.kiwi360.frontend.drivers.userText
import jp.xenia.emulator.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** "Name · version", without repeating a version the name already has. */
internal fun driverLabel(name: String, version: String): String =
    if (version.isEmpty() || name.contains(version)) name else "$name · $version"

/** Installed drivers: choose the default one, delete, download or import more. */
@Composable
internal fun DriversScreen(state: AppState) {
    val context = LocalContext.current
    val requester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    val installed = remember(state.driverRevision) { state.drivers.installed() }
    val selected = remember(state.driverRevision) { state.drivers.defaultDriverId }
    val importFailed = stringResource(R.string.driver_install_failed)
    val installedText = stringResource(R.string.driver_installed)
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = context.getString(R.string.driver_installing)
        scope.launch {
            status = try {
                val driver = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { state.drivers.install(it) }
                }
                state.driverRevision++
                installedText.format(driverLabel(driver.name, driver.version))
            } catch (e: Exception) {
                importFailed.format(e.userText(context))
            }
        }
    }
    SubScreen(
        stringResource(R.string.gpu_driver),
        listOf("A" to stringResource(R.string.hint_select), "Y" to stringResource(R.string.driver_delete)),
        { state.screen = Screen.Dashboard },
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = LIST) {
            status?.let { text ->
                item { Text(text, color = Kiwi.Ink, fontSize = 15.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)) }
            }
            item {
                DriverTile(
                    stringResource(R.string.driver_system), stringResource(R.string.driver_system_detail),
                    selected.isEmpty() || installed.none { it.id == selected },
                    requester,
                ) {
                    state.drivers.defaultDriverId = ""
                    state.driverRevision++
                }
            }
            items(installed, key = { it.id }) { driver ->
                val deleting = pendingDelete == driver.id
                DriverTile(
                    driverLabel(driver.name, driver.version),
                    if (deleting) stringResource(R.string.driver_delete_confirm) else driver.description,
                    driver.id == selected,
                    null,
                    color = if (deleting) Kiwi.ButtonB else null,
                    onSecondary = {
                        if (deleting) {
                            state.drivers.delete(driver)
                            pendingDelete = null
                            state.driverRevision++
                        } else {
                            pendingDelete = driver.id
                        }
                    },
                ) {
                    pendingDelete = null
                    state.drivers.defaultDriverId = driver.id
                    state.driverRevision++
                }
            }
            item {
                MetroTile(620.dp, 52.dp, Kiwi.Green, { state.screen = Screen.DriverRepos }) {
                    Text(
                        "↓  " + stringResource(R.string.driver_download), color = Color.White, fontSize = 18.sp,
                        modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp),
                    )
                }
            }
            item {
                MetroTile(620.dp, 52.dp, Kiwi.Slate, { importer.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                    Text(
                        "+  " + stringResource(R.string.driver_import), color = Color.White, fontSize = 18.sp,
                        modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp),
                    )
                }
            }
        }
    }
    InitialFocus(requester)
}

@Composable
private fun DriverTile(
    title: String,
    subtitle: String,
    selected: Boolean,
    focusRequester: FocusRequester?,
    color: Color? = null,
    onSecondary: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    MetroTile(
        620.dp, 62.dp, color ?: if (selected) Kiwi.GreenDeep else Kiwi.Slate, onClick,
        onSecondary = onSecondary, focusRequester = focusRequester,
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (selected) Text(stringResource(R.string.driver_in_use), color = Kiwi.GreenPale, fontSize = 15.sp)
        }
    }
}

/** The repositories drivers can be downloaded from. */
@Composable
internal fun DriverReposScreen(state: AppState) {
    val requester = remember { FocusRequester() }
    SubScreen(stringResource(R.string.driver_download), listOf("A" to stringResource(R.string.hint_open)), { state.screen = Screen.Drivers }) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = LIST) {
            itemsIndexed(DRIVER_REPOS) { index, repo ->
                MetroTile(
                    620.dp, 56.dp, if (index == 0) Kiwi.GreenDeep else Kiwi.Slate,
                    { state.screen = Screen.DriverReleases(index) },
                    focusRequester = if (index == 0) requester else null,
                ) {
                    Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp)) {
                        Text(repo.name, color = Color.White, fontSize = 17.sp)
                        Text(repo.path, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                }
            }
        }
    }
    InitialFocus(requester)
}

private sealed interface ReleasesState {
    data object Loading : ReleasesState
    data class Loaded(val releases: List<DriverRelease>) : ReleasesState
    data class Failed(val message: String) : ReleasesState
}

/** One repository's releases; A downloads and installs a package. */
@Composable
internal fun DriverReleasesScreen(state: AppState, repoIndex: Int) {
    val context = LocalContext.current
    val repo = DRIVER_REPOS.getOrNull(repoIndex) ?: run {
        LaunchedEffect(Unit) { state.screen = Screen.DriverRepos }
        return
    }
    val requester = remember(repoIndex) { FocusRequester() }
    val scope = rememberCoroutineScope()
    var releases by remember(repoIndex) { mutableStateOf<ReleasesState>(ReleasesState.Loading) }
    var status by remember(repoIndex) { mutableStateOf<String?>(null) }
    var busy by remember(repoIndex) { mutableStateOf(false) }
    LaunchedEffect(repoIndex) {
        releases = try {
            ReleasesState.Loaded(withContext(Dispatchers.IO) { DriverDownloader.releases(repo) })
        } catch (e: Exception) {
            ReleasesState.Failed(e.userText(context))
        }
    }
    val installedText = stringResource(R.string.driver_installed)
    val failedText = stringResource(R.string.driver_install_failed)

    fun download(asset: DriverAsset) {
        if (busy) return
        busy = true
        scope.launch {
            val file = File(context.cacheDir, "driver-download.zip")
            status = try {
                withContext(Dispatchers.IO) {
                    DriverDownloader.download(asset, file) { done, total ->
                        val text = if (total > 0) {
                            context.getString(R.string.driver_downloading_progress, asset.name, (done * 100 / total).toInt())
                        } else {
                            context.getString(R.string.driver_downloading, asset.name)
                        }
                        scope.launch { status = text }
                    }
                }
                status = context.getString(R.string.driver_installing)
                val driver: GpuDriver = withContext(Dispatchers.IO) { file.inputStream().use { state.drivers.install(it) } }
                state.driverRevision++
                installedText.format(driverLabel(driver.name, driver.version))
            } catch (e: Exception) {
                failedText.format(e.userText(context))
            } finally {
                file.delete()
                busy = false
            }
        }
    }

    SubScreen(repo.name, listOf("A" to stringResource(R.string.driver_install)), { state.screen = Screen.DriverRepos }) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = LIST) {
            status?.let { text ->
                item { Text(text, color = Kiwi.Ink, fontSize = 15.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)) }
            }
            when (val current = releases) {
                ReleasesState.Loading -> item {
                    Text(stringResource(R.string.driver_loading), color = Kiwi.InkSoft, fontSize = 16.sp, modifier = Modifier.padding(start = 4.dp))
                }
                is ReleasesState.Failed -> item {
                    MetroTile(620.dp, 56.dp, Kiwi.ButtonB, { state.screen = Screen.DriverRepos }, focusRequester = requester) {
                        Text(
                            stringResource(R.string.driver_list_failed, current.message), color = Color.White, fontSize = 15.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp),
                        )
                    }
                }
                is ReleasesState.Loaded -> {
                    if (current.releases.isEmpty()) {
                        item {
                            MetroTile(620.dp, 56.dp, Kiwi.Slate, { state.screen = Screen.DriverRepos }, focusRequester = requester) {
                                Text(
                                    stringResource(R.string.driver_no_releases), color = Color.White, fontSize = 16.sp,
                                    modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp),
                                )
                            }
                        }
                    }
                    val first = current.releases.firstOrNull()?.assets?.firstOrNull()
                    current.releases.forEach { release ->
                        items(release.assets, key = { release.tag + "/" + it.url }) { asset ->
                            MetroTile(
                                620.dp, 62.dp, if (asset == first) Kiwi.GreenDeep else Kiwi.Slate,
                                { download(asset) },
                                focusRequester = if (asset == first) requester else null,
                            ) {
                                Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp)) {
                                    Text(
                                        release.title + if (release.prerelease) "  ·  " + stringResource(R.string.driver_prerelease) else "",
                                        color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        asset.name + "  ·  " + Formatter.formatShortFileSize(context, asset.size) +
                                            "  ·  " + release.published.take(10),
                                        color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    InitialFocus(requester, releases)
}
