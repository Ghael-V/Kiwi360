// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.overlay

import android.app.Activity
import android.view.ViewGroup
import jp.xenia.emulator.WindowedAppActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import dev.kiwi360.frontend.drivers.GpuDriverStore
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The windows the emulator shows over a game, drawn in Compose: system dialogs
 * a title asks for (message boxes, keyboard, sign-in…), notifications, the
 * crash report and the quit prompt.
 *
 * Native requests arrive as JSON (see xenia/ui/kiwi_ui_bridge.h); each dialog
 * answers once with JSON through [respond]. Toasts and the crash report need
 * no answer.
 */
class KiwiOverlay(
    private val activity: Activity,
    container: ViewGroup,
    private val respond: (id: Long, json: String) -> Unit,
    /** Ends the game and returns to the library. */
    val quitGame: () -> Unit,
    /** The disc image running, to offer the other images beside it for a disc swap. */
    val gamePath: String?,
    /** Live settings for the in-game menu; null leaves the menu out. */
    val menuHost: GameMenuHost?,
    /** The library's key of the game, to keep the menu's settings for it. */
    gameKey: String?,
    /** On-screen gamepad: "auto" (when no physical pad is there), "always" or "never". */
    initialTouchMode: String?,
) {
    data class Request(val id: Long, val json: JSONObject)
    data class Toast(val serial: Long, val kind: String, val title: String, val text: String)
    data class Crash(val text: String, val reportPath: String?)

    internal val requests = mutableStateListOf<Request>()
    internal val toasts = mutableStateListOf<Toast>()
    internal var crash by mutableStateOf<Crash?>(null)
    internal var exitAsked by mutableStateOf(false)
    internal var menuOpen by mutableStateOf(false)
    internal var touchMode by mutableStateOf(initialTouchMode?.takeIf { it in TOUCH_MODES } ?: "auto")
    internal var physicalPad by mutableStateOf(false)
    private val padWatcher = PhysicalGamepadWatcher(activity) { physicalPad = it }

    /** The on-screen gamepad shows while the game has the input. */
    internal val showVirtualPad: Boolean
        get() = menuHost != null && !capturesInput && when (touchMode) {
            "always" -> true
            "never" -> false
            else -> !physicalPad
        }
    internal val liveSettings: GameLiveSettings? =
        gameKey?.takeIf { it.isNotEmpty() }?.let { GameLiveSettings.forGame(activity, it) }
    /** The custom effects chain the in-game menu can go back to, when there is no [liveSettings]. */
    internal var customPostEffects: String? = null
    private var toastSerial = 0L

    /** The GPU driver the game runs on, for the shared logs: its name and version, else its folder. */
    internal val driverDescription: String
        get() = activity.intent.getBundleExtra(WindowedAppActivity.EXTRA_CVARS)
            ?.getString("vulkan_custom_driver_dir")?.takeIf { it.isNotEmpty() }
            ?.let { dir ->
                val id = File(dir).name
                GpuDriverStore(activity).let { store -> store.find(id)?.let { store.label(it) } } ?: id
            } ?: "system"

    /** Whether the overlay holds the gamepad: the game must not see input meanwhile. */
    val capturesInput: Boolean
        get() = requests.isNotEmpty() || crash != null || exitAsked || menuOpen

    private val view = ComposeView(activity).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent { OverlayRoot(this@KiwiOverlay) }
    }

    init {
        physicalPad = padWatcher.connected
        container.addView(
            view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    /** Main thread. */
    fun onNativeRequest(id: Long, bytes: ByteArray) {
        val json = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (_: Exception) {
            respond(id, """{"cancelled":true}""")
            return
        }
        when (json.optString("type")) {
            "toast" -> toasts += Toast(
                toastSerial++, json.optString("kind"), json.optString("title"), json.optString("text"),
            )
            "crash" -> {
                val text = json.optString("text")
                crash = Crash(text, saveCrashReport(text))
                // Recommended settings the game was trying out failed.
                dev.kiwi360.frontend.aiconfig.AiConfigStore.onFatal(activity)
            }
            else -> requests += Request(id, json)
        }
        updateFocus()
    }

    internal fun answer(request: Request, answer: JSONObject) {
        if (requests.remove(request)) {
            respond(request.id, answer.toString())
        }
        updateFocus()
    }

    internal fun cancel(request: Request) = answer(request, JSONObject().put("cancelled", true))

    /** The in-game menu, or the quit prompt when there is no menu. */
    fun openMenu() {
        if (menuHost == null) {
            askExit()
            return
        }
        menuOpen = true
        updateFocus()
    }

    internal fun closeMenu() {
        menuOpen = false
        updateFocus()
    }

    fun askExit() {
        exitAsked = true
        updateFocus()
    }

    internal fun dismissExit() {
        exitAsked = false
        updateFocus()
    }

    /** Key events must reach the Compose view while it holds the gamepad. */
    private fun updateFocus() {
        view.isFocusable = capturesInput
        view.isFocusableInTouchMode = capturesInput
        if (capturesInput) view.requestFocus() else view.clearFocus()
    }

    companion object {
        internal val TOUCH_MODES = listOf("auto", "always", "never")
    }

    private fun saveCrashReport(text: String): String? = try {
        val dir = File(activity.getExternalFilesDir(null), "crash_reports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash_$stamp.txt")
        file.writeText("Kiwi360 crash report $stamp\nGame: ${gamePath ?: "?"}\n\n$text\n")
        file.absolutePath
    } catch (_: Exception) {
        null
    }
}
