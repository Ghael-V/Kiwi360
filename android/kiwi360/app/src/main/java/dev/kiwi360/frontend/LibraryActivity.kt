// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.kiwi360.frontend.aiconfig.AiConfigStore
import dev.kiwi360.frontend.drivers.GpuDriverStore
import dev.kiwi360.frontend.library.Game
import dev.kiwi360.frontend.library.GameFile
import dev.kiwi360.frontend.library.Library
import dev.kiwi360.frontend.ui.AppState
import dev.kiwi360.frontend.ui.KiwiApp
import dev.kiwi360.frontend.ui.PIVOT_SETTINGS
import dev.kiwi360.frontend.ui.Screen
import jp.xenia.emulator.R

/**
 * The game library, in the app's main process. Games run in EmulatorActivity
 * in the ":emu" process: quitting a game ends that process, so every boot gets
 * a fresh emulator and a crash never takes the library down with it.
 */
class LibraryActivity : ComponentActivity() {
    private lateinit var state: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        // Unpacks the drivers shipped in the APK once per app version (~10 MB).
        val drivers = GpuDriverStore(this).also { it.installBundled(this) }
        state = AppState(Library(this), drivers)
        // Games can't run on this CPU: said once, before the player tries one. Another
        // app opening a game's options goes over it; it shows at the next start then.
        if (CpuSupport.shouldShowNotice(this)) state.screen = Screen.UnsupportedCpu(Screen.Dashboard, notice = true)
        setContent { KiwiApp(state, ::launch) }
        handleExternalIntent(intent)
        requestStorageAccess()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalIntent(intent)
    }

    /**
     * Other apps (frontends) open a game's options: action
     * <applicationId>.OPEN_GAME_SETTINGS with the game file's absolute path in the
     * "path" extra. Back from there returns to that app.
     */
    private fun handleExternalIntent(intent: Intent) {
        if (intent.action != "$packageName.$ACTION_OPEN_GAME_SETTINGS") return
        val path = intent.getStringExtra("path")?.trim().orEmpty()
        if (state.games.isEmpty()) state.games = state.library.cachedGames()
        val key = path.takeIf { it.isNotEmpty() }
            ?.let { GameLauncher(state.library, state.drivers).gameFor(it)?.first?.key }
        if (key != null && state.games.any { it.key == key }) {
            state.screen = Screen.GameOptions(key)
            state.returnToCaller = { moveTaskToBack(true) }
        } else {
            Toast.makeText(this, getString(R.string.external_settings_not_found, path), Toast.LENGTH_LONG).show()
            state.screen = Screen.Dashboard
        }
    }

    override fun onResume() {
        super.onResume()
        // Names and icons the emulator exported while a game ran.
        state.resumeTick++
        // A game that crashed or never started on recommended settings gets them undone.
        val notices = runCatching { AiConfigStore.recover(this, state.library) }.getOrDefault(emptyList())
        if (notices.isNotEmpty() && state.screen !is Screen.AiReverted) state.screen = Screen.AiReverted(notices)
    }

    /** Reading game folders anywhere on the storage needs all-files access. */
    private fun requestStorageAccess() {
        if (Environment.isExternalStorageManager()) return
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && state.scanRequest == 0 && Environment.isExternalStorageManager() &&
            state.games.isEmpty() && !state.scanning
        ) {
            // Access may have just been granted in the settings screen.
            state.scanRequest++
        }
    }

    private fun launch(game: Game, file: GameFile) {
        if (!GameLauncher(state.library, state.drivers).launch(this, game, file)) {
            // The CPU can't run games; back to where the player started it from.
            val from = state.screen
            state.screen = Screen.UnsupportedCpu(if (from is Screen.UnsupportedCpu) from.back else from)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            // A selects like the d-pad centre, which Compose's clickables understand.
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
            // Sections switch here rather than in Compose, which only sees keys
            // when something is focused (an empty section has nothing).
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1 -> {
                if (state.screen != Screen.Dashboard) return super.dispatchKeyEvent(event)
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val step = if (event.keyCode == KeyEvent.KEYCODE_BUTTON_L1) -1 else 1
                    state.pivot = (state.pivot + step).coerceIn(0, PIVOT_SETTINGS)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        /** Prefixed with the application ID, as other apps address it. */
        const val ACTION_OPEN_GAME_SETTINGS = "OPEN_GAME_SETTINGS"
    }
}
