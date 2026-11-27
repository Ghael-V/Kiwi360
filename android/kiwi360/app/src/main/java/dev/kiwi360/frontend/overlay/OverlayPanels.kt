// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.overlay

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kiwi360.frontend.logs.KiwiLogs
import dev.kiwi360.frontend.ui.ButtonHints
import dev.kiwi360.frontend.ui.Kiwi
import dev.kiwi360.frontend.ui.KiwiFont
import dev.kiwi360.frontend.ui.MetroTile
import dev.kiwi360.frontend.ui.rememberImage
import jp.xenia.emulator.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@Composable
internal fun OverlayRoot(overlay: KiwiOverlay) {
    ProvideTextStyle(TextStyle(fontFamily = KiwiFont)) {
        Box(Modifier.fillMaxSize()) {
            // Under the dialogs, only while the game has the input.
            val host = overlay.menuHost
            if (host != null && overlay.showVirtualPad) {
                VirtualGamepad(onState = { host.setVirtualPad(it) }, onMenu = { overlay.openMenu() })
            }
            val request = overlay.requests.firstOrNull()
            val crash = overlay.crash
            when {
                crash != null -> CrashScreen(overlay, crash)
                request != null -> RequestPanel(overlay, request)
                overlay.exitAsked -> ExitPanel(overlay)
                overlay.menuOpen -> GameMenuPanel(overlay)
            }
            Toasts(overlay, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun RequestPanel(overlay: KiwiOverlay, request: KiwiOverlay.Request) {
    // A new composition per request, so no state leaks from the previous one.
    androidx.compose.runtime.key(request.id) {
        when (request.json.optString("type")) {
            "message" -> MessagePanel(overlay, request)
            "keyboard" -> KeyboardPanel(overlay, request)
            "signin" -> SigninPanel(overlay, request)
            "create_profile" -> CreateProfilePanel(overlay, request)
            "passcode" -> PasscodePanel(overlay, request)
            "achievements" -> AchievementsPanel(overlay, request)
            "disc_swap" -> DiscSwapPanel(overlay, request)
            "notice" -> NoticePanel(overlay, request)
            else -> LaunchedEffect(Unit) { overlay.cancel(request) }
        }
    }
}

/** Requests focus once the target is laid out. */
@Composable
internal fun InitialFocus(requester: FocusRequester, key: Any? = Unit) {
    LaunchedEffect(key) {
        withFrameNanos { }
        try {
            requester.requestFocus()
        } catch (_: IllegalStateException) {
        }
    }
}

/**
 * A system dialog as the console draws them: a light band across the whole
 * width, over the dimmed game.
 */
@Composable
internal fun Band(
    title: String,
    hints: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    fullHeight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            // Touches don't reach the game while a dialog is up.
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints {
            Column(
                modifier
                    .fillMaxWidth()
                    .then(if (fullHeight) Modifier.height(maxHeight * 0.88f) else Modifier.heightIn(max = maxHeight * 0.88f))
                    .background(Kiwi.BackgroundTop)
                    .padding(horizontal = 56.dp, vertical = 14.dp),
            ) {
                Text(
                    title.ifBlank { " " }, color = Kiwi.Ink, fontSize = 28.sp, fontWeight = FontWeight.Light,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, color = Kiwi.InkSoft, fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(12.dp))
                Column(Modifier.weight(1f, fill = fullHeight)) { content() }
                Spacer(Modifier.height(12.dp))
                ButtonHints(hints)
            }
        }
    }
}

/** A full-width choice row inside a band. */
@Composable
internal fun ChoiceRow(
    text: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    detail: String? = null,
    color: Color = Kiwi.Slate,
    width: Dp = 520.dp,
) {
    MetroTile(width, if (detail != null) 58.dp else 48.dp, color, onClick, focusRequester = focusRequester) {
        Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp)) {
            Text(text, color = Color.White, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) {
                Text(detail, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private val LIST_PADDING = PaddingValues(horizontal = 12.dp, vertical = 6.dp)

@Composable
private fun MessagePanel(overlay: KiwiOverlay, request: KiwiOverlay.Request) {
    val json = request.json
    val context = LocalContext.current
    // The emulator's own text in the user's language; a game's stays as the game wrote it.
    val own = json.optBoolean("host")
    fun text(value: String) = if (own) XeniaText.translate(context, value) else value
    val buttons = json.optJSONArray("buttons").strings().map(::text)
    val default = json.optInt("default", 0).coerceIn(0, maxOf(0, buttons.size - 1))
    val requester = remember { FocusRequester() }
    BackHandler { overlay.cancel(request) }
    Band(
        text(json.optString("title")),
        listOf("A" to stringResource(R.string.overlay_select), "B" to stringResource(R.string.overlay_back)),
    ) {
        val body = text(json.optString("text"))
        if (body.isNotBlank()) {
            Text(
                body, color = Kiwi.Ink, fontSize = 18.sp,
                modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
            )
            Spacer(Modifier.height(14.dp))
        }
        val labels = buttons.ifEmpty { listOf(stringResource(R.string.overlay_ok)) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = LIST_PADDING) {
            itemsIndexed(labels) { index, label ->
                ChoiceRow(
                    label,
                    { overlay.answer(request, JSONObject().put("button", index)) },
                    focusRequester = if (index == default) requester else null,
                    color = if (index == default) Kiwi.Green else Kiwi.Slate,
                )
            }
        }
    }
    InitialFocus(requester)
}

@Composable
private fun NoticePanel(overlay: KiwiOverlay, request: KiwiOverlay.Request) {
    val requester = remember { FocusRequester() }
    val context = LocalContext.current
    BackHandler { overlay.answer(request, JSONObject()) }
    // Notices are always the emulator's own.
    Band(
        XeniaText.translate(context, request.json.optString("title")),
        listOf("A" to stringResource(R.string.overlay_ok)),
    ) {
        Text(
            XeniaText.translate(context, request.json.optString("text")), color = Kiwi.Ink, fontSize = 16.sp,
            modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
        )
        Spacer(Modifier.height(12.dp))
        Box(Modifier.padding(LIST_PADDING)) {
            ChoiceRow(stringResource(R.string.overlay_ok), { overlay.answer(request, JSONObject()) }, requester, color = Kiwi.Green, width = 240.dp)
        }
    }
    InitialFocus(requester)
}

@Composable
private fun KeyboardPanel(overlay: KiwiOverlay, request: KiwiOverlay.Request) {
    val json = request.json
    BackHandler { overlay.cancel(request) }
    Band(json.optString("title"), keyboardHints(), subtitle = json.optString("description")) {
        OnScreenKeyboard(
            initial = json.optString("text"),
            maxLength = json.optInt("max", 0),
            onAccept = { overlay.answer(request, JSONObject().put("text", it)) },
        )
    }
}

@Composable
fun keyboardHints() = listOf(
    "A" to stringResource(R.string.overlay_type),
    "X" to stringResource(R.string.overlay_delete),
    "Y" to stringResource(R.string.overlay_space),
    "LB" to stringResource(R.string.overlay_shift),
    "START" to stringResource(R.string.overlay_accept),
    "B" to stringResource(R.string.overlay_cancel),
)

private val GAMERTAG = Regex("^[A-Za-z][A-Za-z0-9]*( [A-Za-z0-9]+)*$")

fun isValidGamertag(text: String) = text.length in 1..15 && GAMERTAG.matches(text)

@Composable
private fun CreateProfilePanel(overlay: KiwiOverlay, request: KiwiOverlay.Request) {
    var error by remember { mutableStateOf(false) }
    BackHandler { overlay.cancel(request) }
    Band(
        stringResource(R.string.overlay_create_profile), keyboardHints(),
        subtitle = stringResource(if (error) R.string.overlay_gamertag_invalid else R.string.overlay_gamertag_rules),
    ) {
        OnScreenKeyboard(initial = "", maxLength = 15, onAccept = {
            if (isValidGamertag(it)) overlay.answer(request, JSONObject().put("gamertag", it)) else error = true
        })
    }
}

@Composable
private fun SigninPanel(overlay: KiwiOverlay, request: KiwiOverlay.Request) {
    val json = request.json
    val users = json.optInt("users", 1).coerceIn(1, 4)
    val profiles = remember(request) {
        val array = json.optJSONArray("profiles") ?: JSONArray()
        (0 until array.length()).map { array.getJSONObject(it) }
    }
    val chosen = remember { mutableStateListOf<String>() }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    BackHandler {
        when {
            creating -> creating = false
            chosen.isNotEmpty() -> chosen.removeAt(chosen.lastIndex)
            else -> overlay.cancel(request)
        }
    }
    if (creating) {
        Band(
            stringResource(R.string.overlay_create_profile), keyboardHints(),
            subtitle = stringResource(if (error) R.string.overlay_gamertag_invalid else R.string.overlay_gamertag_rules),
        ) {
            OnScreenKeyboard(initial = "", maxLength = 15, onAccept = {
                if (isValidGamertag(it)) overlay.answer(request, JSONObject().put("create", it)) else error = true
            })
        }
        return
    }
    Band(
        stringResource(R.string.overlay_signin),
        listOf("A" to stringResource(R.string.overlay_select), "B" to stringResource(R.string.overlay_back)),
        subtitle = if (users > 1) stringResource(R.string.overlay_signin_player, chosen.size + 1) else null,
    ) {
        val available = profiles.filter { it.optString("xuid") !in chosen }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = LIST_PADDING) {
            itemsIndexed(available, key = { _, p -> p.optString("xuid") }) { index, profile ->
                val signedIn = profile.optInt("slot", -1) >= 0
                ChoiceRow(
                    profile.optString("gamertag"),
                    {
                        chosen += profile.optString("xuid")
                        if (chosen.size >= users) {
                            overlay.answer(request, JSONObject().put("xuids", JSONArray(chosen.toList())))
                        }
                    },
                    focusRequester = if (index == 0) requester else null,
                    detail = if (signedIn) stringResource(R.string.overlay_signed_in) else null,
                    color = if (signedIn) Kiwi.Green else Kiwi.Slate,
                )
            }
            item {
                ChoiceRow(
                    "+  " + stringResource(R.string.overlay_create_profile),
                    { creating = true },
                    focusRequester = if (available.isEmpty()) requester else null,
                    color = Kiwi.GreenDeep,
                )
            }
        }
    }
    InitialFocus(requester, chosen.size)
}

private val PASSCODE_KEYS = mapOf(
    AndroidKeyEvent.KEYCODE_BUTTON_X to "X",
    AndroidKeyEvent.KEYCODE_BUTTON_Y to "Y",
    AndroidKeyEvent.KEYCODE_BUTTON_R1 to "RB",
    AndroidKeyEvent.KEYCODE_BUTTON_L1 to "LB",
    AndroidKeyEvent.KEYCODE_BUTTON_L2 to "LT",
    AndroidKeyEvent.KEYCODE_BUTTON_R2 to "RT",
    AndroidKeyEvent.KEYCODE_DPAD_UP to "Up",
    AndroidKeyEvent.KEYCODE_DPAD_DOWN to "Down",
    AndroidKeyEvent.KEYCODE_DPAD_LEFT to "Left",
    AndroidKeyEvent.KEYCODE_DPAD_RIGHT to "Right",
)

@Composable
private fun PasscodePanel(overlay: KiwiOverlay, request: KiwiOverlay.Request) {
    val keys = remember { mutableStateListOf<String>() }
    val requester = remember { FocusRequester() }
    fun press(name: String) {
        if (keys.size < 4) keys += name
        if (keys.size == 4) overlay.answer(request, JSONObject().put("keys", JSONArray(keys.toList())))
    }
    BackHandler { if (keys.isNotEmpty()) keys.removeAt(keys.lastIndex) else overlay.cancel(request) }
    Band(
        request.json.optString("title"),
        listOf("B" to stringResource(R.string.overlay_back)),
        subtitle = request.json.optString("description"),
        modifier = Modifier
            .focusRequester(requester)
            .onPreviewKeyEvent { event ->
                val name = PASSCODE_KEYS[event.nativeKeyEvent.keyCode] ?: return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) press(name)
                true
            }
            .focusable(),
    ) {
        Text(stringResource(R.string.overlay_passcode_hint), color = Kiwi.Ink, fontSize = 16.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(4) { i ->
                Box(
                    Modifier.size(64.dp).background(if (i < keys.size) Kiwi.Green else Kiwi.Slate.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (i < keys.size) "●" else "", color = Color.White, fontSize = 22.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // Touch fallback.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (name in PASSCODE_KEYS.values) {
                Box(
                    Modifier.size(52.dp, 36.dp).background(Kiwi.Slate)
                        .pointerInput(name) { detectTapGestures { press(name) } },
                    contentAlignment = Alignment.Center,
                ) { Text(name, color = Color.White, fontSize = 13.sp) }
            }
        }
    }
    InitialFocus(requester)
}

@Composable
private fun AchievementsPanel(overlay: KiwiOverlay, request: KiwiOverlay.Request) {
    val json = request.json
    val items = remember(request) {
        val array = json.optJSONArray("items") ?: JSONArray()
        (0 until array.length()).map { array.getJSONObject(it) }
    }
    val unlocked = items.count { it.optBoolean("unlocked") }
    val score = items.filter { it.optBoolean("unlocked") }.sumOf { it.optInt("score") }
    val total = items.sumOf { it.optInt("score") }
    val requester = remember { FocusRequester() }
    BackHandler { overlay.answer(request, JSONObject()) }
    Band(
        json.optString("title").ifBlank { stringResource(R.string.overlay_achievements) },
        listOf("B" to stringResource(R.string.overlay_back)),
        subtitle = stringResource(R.string.overlay_achievements_summary, unlocked, items.size, score, total),
        fullHeight = true,
    ) {
        if (items.isEmpty()) {
            Text(stringResource(R.string.overlay_no_achievements), color = Kiwi.InkSoft, fontSize = 18.sp)
            Box(Modifier.size(1.dp).focusRequester(requester).focusable())
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = LIST_PADDING) {
            itemsIndexed(items) { index, item ->
                AchievementRow(item, if (index == 0) requester else null) { overlay.answer(request, JSONObject()) }
            }
        }
    }
    InitialFocus(requester)
}

@Composable
private fun AchievementRow(item: JSONObject, requester: FocusRequester?, onClick: () -> Unit) {
    val unlocked = item.optBoolean("unlocked")
    val icon = rememberImage(item.optString("icon").ifBlank { null }, 128)
    MetroTile(900.dp, 72.dp, if (unlocked) Kiwi.Slate else Color(0xFF666B62), onClick, focusRequester = requester) {
        Row(Modifier.fillMaxSize().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).background(Color.Black.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                if (icon != null) Image(icon, null, Modifier.fillMaxSize()) else Text("🔒", fontSize = 22.sp)
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(
                    item.optString("name").ifBlank { stringResource(R.string.overlay_secret_achievement) },
                    color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.optString("description").ifBlank { stringResource(R.string.overlay_hidden_description) },
                    color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Text("${item.optInt("score")} G", color = if (unlocked) Kiwi.GreenPale else Color.White.copy(alpha = 0.5f), fontSize = 18.sp)
        }
    }
}

@Composable
private fun DiscSwapPanel(overlay: KiwiOverlay, request: KiwiOverlay.Request) {
    val json = request.json
    val discs = remember(request) {
        val array = json.optJSONArray("discs") ?: JSONArray()
        val listed = (0 until array.length()).map {
            array.getJSONObject(it).let { d -> d.optString("label") to d.optString("path") }
        }
        // Also offer every other image beside the running one.
        val known = listed.map { File(it.second).absolutePath }.toSet()
        val siblings = overlay.gamePath?.let { File(it).parentFile }?.listFiles()
            ?.filter { it.isFile && it.extension.equals("iso", true) && it.absolutePath !in known }
            ?.sortedBy { it.name.lowercase() }
            ?.map { it.nameWithoutExtension to it.absolutePath }
            ?: emptyList()
        listed + siblings
    }
    val requester = remember { FocusRequester() }
    BackHandler { overlay.cancel(request) }
    Band(
        stringResource(R.string.overlay_disc_swap),
        listOf("A" to stringResource(R.string.overlay_select), "B" to stringResource(R.string.overlay_cancel)),
        // The game's prompt, or the emulator's error after a wrong disc: only the
        // emulator's lines are in the catalog.
        subtitle = XeniaText.translate(LocalContext.current, json.optString("message")),
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = LIST_PADDING) {
            itemsIndexed(discs) { index, (label, path) ->
                ChoiceRow(
                    label.ifBlank { File(path).name },
                    { overlay.answer(request, JSONObject().put("path", path)) },
                    focusRequester = if (index == 0) requester else null,
                    detail = File(path).name,
                )
            }
        }
    }
    InitialFocus(requester)
}

@Composable
private fun ExitPanel(overlay: KiwiOverlay) {
    val requester = remember { FocusRequester() }
    BackHandler { overlay.dismissExit() }
    Band(
        stringResource(R.string.exit_game_title),
        listOf("A" to stringResource(R.string.overlay_select), "B" to stringResource(R.string.exit_game_no)),
        subtitle = stringResource(R.string.exit_game_body),
    ) {
        Column(Modifier.padding(LIST_PADDING), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceRow(stringResource(R.string.exit_game_yes), overlay.quitGame, requester, color = Kiwi.ButtonB)
            ChoiceRow(stringResource(R.string.exit_game_no), { overlay.dismissExit() })
        }
    }
    InitialFocus(requester)
}

@Composable
private fun CrashScreen(overlay: KiwiOverlay, crash: KiwiOverlay.Crash) {
    val requester = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    BackHandler { overlay.quitGame() }
    Box(
        Modifier.fillMaxSize().background(Color(0xFF1E211C)).pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 28.dp)) {
            Text(stringResource(R.string.crash_title), color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Light)
            Text(stringResource(R.string.crash_body), color = Color.White.copy(alpha = 0.75f), fontSize = 16.sp)
            if (crash.reportPath != null) {
                Text(stringResource(R.string.crash_saved, crash.reportPath), color = Kiwi.GreenPale, fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                crash.text,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .verticalScroll(scroll)
                    .padding(12.dp),
            )
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val step = when (event.nativeKeyEvent.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> 160f
                        AndroidKeyEvent.KEYCODE_DPAD_UP -> -160f
                        else -> return@onPreviewKeyEvent false
                    }
                    scope.launch { scroll.animateScrollBy(step) }
                    true
                },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChoiceRow(stringResource(R.string.crash_back), overlay.quitGame, requester, color = Kiwi.Green, width = 360.dp)
                    ShareLogsButton(overlay)
                }
            }
            Spacer(Modifier.height(8.dp))
            ButtonHints(
                listOf("A" to stringResource(R.string.hint_select), "↕" to stringResource(R.string.crash_scroll)),
                textColor = Color.White,
            )
        }
    }
    InitialFocus(requester)
}

/** Packs the logs (this session's included, flushed already) and opens the share sheet. */
@Composable
private fun ShareLogsButton(overlay: KiwiOverlay) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }
    val chooserTitle = stringResource(R.string.share_logs_chooser)
    val failed = stringResource(R.string.share_logs_failed)
    ChoiceRow(
        stringResource(if (preparing) R.string.share_logs_preparing else R.string.share_logs),
        onClick = {
            if (!preparing) {
                preparing = true
                scope.launch {
                    val zip = withContext(Dispatchers.IO) {
                        KiwiLogs.buildPackage(context, overlay.driverDescription)
                    }
                    preparing = false
                    if (zip != null) {
                        context.startActivity(KiwiLogs.shareIntent(context, zip, chooserTitle))
                    } else {
                        android.widget.Toast.makeText(context, failed, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        },
        width = 300.dp,
    )
}

@Composable
private fun Toasts(overlay: KiwiOverlay, modifier: Modifier) {
    val toast = overlay.toasts.firstOrNull()
    // One at a time, each for a few seconds, like the console.
    LaunchedEffect(toast?.serial) {
        if (toast != null) {
            delay(4500)
            overlay.toasts.remove(toast)
        }
    }
    AnimatedVisibility(
        visible = toast != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier.padding(bottom = 28.dp),
    ) {
        val shown = toast ?: return@AnimatedVisibility
        val achievement = shown.kind == "achievement"
        val context = LocalContext.current
        fun text(value: String) = if (shown.kind == "guest") value else XeniaText.translate(context, value)
        val header = stringResource(R.string.overlay_achievement_unlocked)
        // Xenia titles achievement toasts "Achievement unlocked", which the header already says.
        val title = text(shown.title).takeUnless { achievement && it == header }.orEmpty()
        val body = text(shown.text)
        Row(
            Modifier.background(Color(0xEE262A23)).padding(horizontal = 18.dp, vertical = 12.dp).width(520.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).background(if (achievement) Kiwi.Green else Kiwi.Slate, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (achievement) "🏆" else "i", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.padding(start = 14.dp)) {
                if (achievement) {
                    Text(header, color = Kiwi.GreenPale, fontSize = 13.sp)
                }
                if (title.isNotBlank()) {
                    Text(title, color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (body.isNotBlank()) {
                    Text(body, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }
