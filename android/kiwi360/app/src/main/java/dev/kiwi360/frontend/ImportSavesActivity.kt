// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend

import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import dev.kiwi360.frontend.library.AchievementStore
import dev.kiwi360.frontend.library.Library
import dev.kiwi360.frontend.library.SaveStore
import dev.kiwi360.frontend.ui.InitialFocus
import dev.kiwi360.frontend.ui.Kiwi
import dev.kiwi360.frontend.ui.KiwiFont
import dev.kiwi360.frontend.ui.LanePadding
import dev.kiwi360.frontend.ui.MetroBackground
import dev.kiwi360.frontend.ui.MetroTile
import dev.kiwi360.frontend.ui.SubScreen
import jp.xenia.emulator.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Restores a title's saved games from a ZIP another app (or Kiwi's own game
 * options) hands over: action <applicationId>.IMPORT_SAVES, data the ZIP's
 * content:// URI with read permission. Nothing is written until the user
 * confirms here; cancelling leaves everything as it was.
 */
class ImportSavesActivity : ComponentActivity() {
    private sealed interface Step {
        data object Checking : Step
        data class Confirm(val titleId: Long, val name: String, val gameRunning: Boolean) : Step
        data object Importing : Step
    }

    private var step by mutableStateOf<Step>(Step.Checking)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val source = intent.data
        // Recreated (the process was killed in the background), the read grant and the
        // question the user saw may both be gone: close rather than ask again.
        if (source == null || savedInstanceState != null) {
            finish()
            return
        }
        // A copy of our own: the read grant may not outlive this activity's first start.
        val copy = File(cacheDir, "import_saves.zip")
        setContent {
            MetroBackground {
                ProvideTextStyle(TextStyle(fontFamily = KiwiFont)) {
                    LaunchedEffect(Unit) { check(source, copy) }
                    when (val s = step) {
                        Step.Checking -> {}
                        Step.Importing -> {
                            BackHandler {}
                            Text(
                                stringResource(R.string.import_running), color = Kiwi.Ink, fontSize = 22.sp,
                                modifier = Modifier.padding(start = LanePadding, top = 120.dp),
                            )
                        }
                        is Step.Confirm -> ConfirmScreen(s, copy)
                    }
                }
            }
        }
    }

    private suspend fun check(source: Uri, copy: File) {
        val found = withContext(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(source)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
                val titleId = copy.inputStream().use { SaveStore(this@ImportSavesActivity).validate(it) }
                    ?: return@withContext null
                val name = Library(this@ImportSavesActivity).cachedGames().firstOrNull { it.titleId == titleId }?.name
                    ?: AchievementStore(this@ImportSavesActivity).summaries().firstOrNull { it.titleId == titleId }?.name
                    ?: "%08X".format(titleId)
                Step.Confirm(titleId, name, GameLauncher.isGameRunning(this@ImportSavesActivity))
            } catch (_: Exception) {
                null
            }
        }
        if (found == null) {
            done(R.string.import_invalid, copy)
        } else {
            step = found
        }
    }

    @Composable
    private fun ConfirmScreen(confirm: Step.Confirm, copy: File) {
        val cancel = remember { FocusRequester() }
        val restore = {
            step = Step.Importing
            // The activity's scope: this screen leaves the composition as soon as the
            // step changes, and a scope tied to it would cancel the restore halfway.
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    if (confirm.gameRunning) GameLauncher.closeRunningGame(this@ImportSavesActivity)
                    copy.inputStream().use { SaveStore(this@ImportSavesActivity).import(confirm.titleId, it) }
                }
                done(
                    when (result) {
                        SaveStore.ImportResult.DONE -> R.string.import_done
                        SaveStore.ImportResult.INVALID -> R.string.import_invalid
                        SaveStore.ImportResult.NO_PROFILE -> R.string.import_no_profile
                        SaveStore.ImportResult.FAILED -> R.string.import_failed
                    },
                    copy,
                )
            }
            Unit
        }
        SubScreen(stringResource(R.string.import_title), listOf("A" to stringResource(R.string.hint_select)), { done(null, copy) }) {
            Column(Modifier.padding(start = LanePadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.import_body, confirm.name), color = Kiwi.Ink, fontSize = 18.sp)
                if (confirm.gameRunning) {
                    Text(stringResource(R.string.import_game_running), color = Kiwi.ButtonB, fontSize = 16.sp)
                }
                MetroTile(420.dp, 52.dp, Kiwi.ButtonB, { restore() }) {
                    Text(
                        stringResource(R.string.import_restore), color = Color.White, fontSize = 18.sp,
                        modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp),
                    )
                }
                MetroTile(420.dp, 52.dp, Kiwi.Slate, { done(null, copy) }, focusRequester = cancel) {
                    Text(
                        stringResource(R.string.import_cancel), color = Color.White, fontSize = 18.sp,
                        modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp),
                    )
                }
            }
        }
        // Cancel first: restoring has to be chosen.
        InitialFocus(cancel)
    }

    private fun done(message: Int?, copy: File) {
        copy.delete()
        if (message != null) Toast.makeText(applicationContext, getString(message), Toast.LENGTH_LONG).show()
        finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // No way out while files are being replaced.
        if (step == Step.Importing && event.keyCode in BACK_KEYS) return true
        when (event.keyCode) {
            // A selects like the d-pad centre; B goes back.
            KeyEvent.KEYCODE_BUTTON_A -> return super.dispatchKeyEvent(
                KeyEvent(
                    event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_DPAD_CENTER,
                    event.repeatCount, event.metaState, event.deviceId, event.scanCode,
                    event.flags, event.source,
                ),
            )
            KeyEvent.KEYCODE_BUTTON_B -> {
                if (event.action == KeyEvent.ACTION_UP) onBackPressedDispatcher.onBackPressed()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private companion object {
        val BACK_KEYS = setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE)
    }
}
