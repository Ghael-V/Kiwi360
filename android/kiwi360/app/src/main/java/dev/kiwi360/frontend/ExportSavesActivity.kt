// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/**
 * Gives another app a copy of a title's saved games, once the user agrees:
 * action <applicationId>.EXPORT_SAVES, started for a result, with the game file's
 * absolute path in the "path" extra. On yes the result is RESULT_OK with
 * content://<applicationId>.library/save_zip/<TITLEID> and a one-time read grant;
 * anything else is RESULT_CANCELED. save_zip needs a signature permission
 * otherwise, so no app can read saves without the user knowing.
 */
class ExportSavesActivity : ComponentActivity() {
    private data class Request(val app: String, val game: String, val titleId: Long)

    private var request by mutableStateOf<Request?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        // Without a caller waiting for the result there is no one to give the copy to.
        // Recreated (the process was killed behind the caller), the question the user saw
        // is gone: close rather than ask again out of context.
        val caller = callingPackage
        val path = intent.getStringExtra(EXTRA_PATH)?.trim().orEmpty()
        if (savedInstanceState != null || caller == null || intent.action != "$packageName.$ACTION_EXPORT_SAVES") {
            finish()
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            MetroBackground {
                ProvideTextStyle(TextStyle(fontFamily = KiwiFont)) {
                    request?.let { ConfirmScreen(it) }
                }
            }
        }
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                val info = path.takeIf { it.startsWith("/") }?.let {
                    try {
                        Library(this@ExportSavesActivity).titleInfo(it)
                    } catch (_: Exception) {
                        null
                    }
                } ?: return@withContext null
                val name = info.name ?: "%08X".format(info.titleId)
                val hasSaves = SaveStore(this@ExportSavesActivity).info(info.titleId) != null
                Triple(info.titleId, name, hasSaves)
            }
            when {
                found == null -> close(getString(R.string.external_settings_not_found, path))
                !found.third -> close(getString(R.string.export_no_saves, found.second))
                else -> request = Request(appLabel(caller), found.second, found.first)
            }
        }
    }

    private fun appLabel(packageName: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }

    @Composable
    private fun ConfirmScreen(request: Request) {
        val cancel = remember { FocusRequester() }
        SubScreen(stringResource(R.string.export_title), listOf("A" to stringResource(R.string.hint_select)), { close(null) }) {
            Column(Modifier.padding(start = LanePadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.export_body, request.app, request.game), color = Kiwi.Ink, fontSize = 18.sp)
                MetroTile(420.dp, 52.dp, Kiwi.Green, { give(request) }) {
                    Text(
                        stringResource(R.string.export_give), color = Color.White, fontSize = 18.sp,
                        modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp),
                    )
                }
                MetroTile(420.dp, 52.dp, Kiwi.Slate, { close(null) }, focusRequester = cancel) {
                    Text(
                        stringResource(R.string.import_cancel), color = Color.White, fontSize = 18.sp,
                        modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp),
                    )
                }
            }
        }
        // Cancel first: giving the copy has to be chosen.
        InitialFocus(cancel)
    }

    private fun give(request: Request) {
        val uri = Uri.Builder().scheme("content").authority("$packageName.library")
            .appendPath("save_zip").appendPath("%08X".format(request.titleId)).build()
        setResult(RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        finish()
    }

    private fun close(message: String?) {
        if (message != null) Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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

    companion object {
        const val ACTION_EXPORT_SAVES = "EXPORT_SAVES"
        const val EXTRA_PATH = "path"
    }
}
