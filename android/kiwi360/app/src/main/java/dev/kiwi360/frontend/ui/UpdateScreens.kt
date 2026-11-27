// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kiwi360.frontend.update.Release
import dev.kiwi360.frontend.update.UpdateCheck
import dev.kiwi360.frontend.update.Updater
import jp.xenia.emulator.R
import kotlinx.coroutines.launch
import java.io.File

private sealed interface UpdateStage {
    data object Offered : UpdateStage
    data object Downloading : UpdateStage
    /** Downloaded; the system installer was (or is about to be) opened on [apk]. */
    data class Ready(val apk: File) : UpdateStage
    /** [apk] is set when only the install failed, so Retry does not download again. */
    data class Failed(@StringRes val message: Int, val apk: File? = null) : UpdateStage
}

/**
 * A newer release: its version and notes, then Download and install, Skip this version
 * or Later. The download shows its progress and ends in the system installer. Closing
 * returns to [back].
 */
@Composable
internal fun UpdateScreen(state: AppState, release: Release, back: Screen) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<UpdateStage>(UpdateStage.Offered) }
    var progress by remember { mutableFloatStateOf(0f) }
    var needsPermission by remember { mutableStateOf(false) }
    val actionRequester = remember { FocusRequester() }
    val notesRequester = remember { FocusRequester() }

    fun install(apk: File) {
        stage = UpdateStage.Ready(apk)
        if (!Updater.canInstall(context)) {
            // Android asks once per app: the switch opens, and the install resumes on return.
            needsPermission = true
            if (!Updater.requestInstallPermission(context)) {
                stage = UpdateStage.Failed(R.string.update_error_permission, apk)
            }
            return
        }
        needsPermission = false
        if (!Updater.install(context, apk)) stage = UpdateStage.Failed(R.string.update_error_install, apk)
    }

    fun download() {
        stage = UpdateStage.Downloading
        progress = 0f
        scope.launch {
            val apk = Updater.download(context, release) { progress = it }
            if (apk == null) stage = UpdateStage.Failed(R.string.update_error_download) else install(apk)
        }
    }

    // Back from the "install unknown apps" screen: install if it was allowed.
    LaunchedEffect(state.resumeTick) {
        val current = stage
        if (needsPermission && current is UpdateStage.Ready && Updater.canInstall(context)) install(current.apk)
    }

    val close = { if (stage != UpdateStage.Downloading) state.screen = back }
    SubScreen(stringResource(R.string.update_title), listOf("A" to stringResource(R.string.hint_select)), close) {
        Row(
            Modifier.fillMaxSize().padding(start = LanePadding, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.update_versions, release.tag, Updater.installedVersion),
                    color = Kiwi.Ink, fontSize = 18.sp,
                )
                ReleaseNotes(
                    release.notes.ifEmpty { stringResource(R.string.update_no_notes) },
                    notesRequester,
                    Modifier.weight(1f).fillMaxWidth(),
                )
            }
            Column(Modifier.width(340.dp).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (val current = stage) {
                    UpdateStage.Offered -> {
                        ActionRow(R.string.update_download, Kiwi.Green, actionRequester, ::download)
                        ActionRow(R.string.update_skip, Kiwi.Slate) {
                            Updater.skip(context, release)
                            state.screen = back
                        }
                        ActionRow(R.string.update_later, Kiwi.Slate) { state.screen = back }
                    }
                    UpdateStage.Downloading -> {
                        Text(
                            stringResource(R.string.update_downloading, (progress * 100).toInt()),
                            color = Kiwi.Ink, fontSize = 17.sp,
                        )
                        ProgressBar(progress)
                    }
                    is UpdateStage.Ready -> {
                        ActionRow(R.string.update_install, Kiwi.Green, actionRequester) { install(current.apk) }
                        ActionRow(R.string.update_later, Kiwi.Slate) { state.screen = back }
                        if (needsPermission) {
                            Text(stringResource(R.string.update_permission), color = Kiwi.InkSoft, fontSize = 14.sp)
                        }
                    }
                    is UpdateStage.Failed -> {
                        Text(stringResource(current.message), color = Kiwi.ButtonB, fontSize = 15.sp)
                        ActionRow(R.string.update_retry, Kiwi.Green, actionRequester) {
                            current.apk?.takeIf { it.exists() }?.let(::install) ?: download()
                        }
                        ActionRow(R.string.update_later, Kiwi.Slate) { state.screen = back }
                    }
                }
            }
        }
    }
    // The first action of each stage; while downloading there is none, so the notes.
    InitialFocus(if (stage == UpdateStage.Downloading) notesRequester else actionRequester, stage)
}

/** The release notes as plain text; focused, the d-pad scrolls them. */
@Composable
private fun ReleaseNotes(text: String, requester: FocusRequester, modifier: Modifier) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .background(Color.White.copy(alpha = 0.6f))
            .then(if (focused) Modifier.border(3.dp, Kiwi.Green) else Modifier)
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val step = when (event.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> 120f
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> -120f
                    else -> return@onPreviewKeyEvent false
                }
                scope.launch { scroll.animateScrollBy(step) }
                true
            }
            .focusable(),
    ) {
        Text(
            text, color = Kiwi.Ink, fontSize = 14.sp,
            modifier = Modifier.fillMaxWidth().verticalScroll(scroll).padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun ActionRow(@StringRes title: Int, color: Color, requester: FocusRequester? = null, onClick: () -> Unit) {
    MetroTile(320.dp, 52.dp, color, onClick, focusRequester = requester) {
        Text(
            stringResource(title), color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp),
        )
    }
}

@Composable
private fun ProgressBar(progress: Float) {
    Box(Modifier.width(320.dp).height(10.dp).background(Kiwi.Slate.copy(alpha = 0.25f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Kiwi.Green))
    }
}

/**
 * Settings, Updates: whether the library checks at start, a check now, and the
 * installed version.
 */
@Composable
internal fun UpdatesScreen(state: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requester = remember { FocusRequester() }
    var checksAtStart by remember { mutableStateOf(Updater.checksAtStart(context)) }
    var checking by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf(if (Updater.available) R.string.update_check_now_desc else R.string.update_dev_build)
    }
    val back = { state.screen = Screen.Dashboard }
    SubScreen(stringResource(R.string.updates_title), listOf("A" to stringResource(R.string.hint_select)), back) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = LIST) {
            item {
                Text(
                    stringResource(R.string.update_installed_version, Updater.installedVersion),
                    color = Kiwi.Ink, fontSize = 18.sp,
                )
            }
            item {
                OptionRow(
                    stringResource(R.string.update_checks),
                    stringResource(R.string.update_checks_desc),
                    stringResource(if (checksAtStart) R.string.opt_on else R.string.opt_off),
                    changed = !checksAtStart,
                    focusRequester = requester,
                ) {
                    checksAtStart = !checksAtStart
                    Updater.setChecksAtStart(context, checksAtStart)
                }
            }
            item {
                MetroTile(
                    620.dp, 56.dp, Kiwi.Slate,
                    onClick = {
                        if (checking || !Updater.available) return@MetroTile
                        checking = true
                        status = R.string.update_checking
                        scope.launch {
                            // Asked for: a skipped version is offered too.
                            when (val result = Updater.check()) {
                                is UpdateCheck.Available -> {
                                    status = R.string.update_check_now_desc
                                    state.screen = Screen.Update(result.release, Screen.Updates)
                                }
                                UpdateCheck.UpToDate -> status = R.string.update_up_to_date
                                UpdateCheck.Failed -> status = R.string.update_error_check
                            }
                            checking = false
                        }
                    },
                ) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.Center) {
                        Text(stringResource(R.string.update_check_now), color = Color.White, fontSize = 17.sp)
                        Text(
                            stringResource(status), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
    InitialFocus(requester)
}
