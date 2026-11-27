// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.kiwi360.frontend.aiconfig.AiConfigClient
import dev.kiwi360.frontend.aiconfig.AiConfigStore
import dev.kiwi360.frontend.aiconfig.AiSchema
import dev.kiwi360.frontend.library.Game
import dev.kiwi360.frontend.library.GameFile
import dev.kiwi360.frontend.library.GameOption
import dev.kiwi360.frontend.library.Library
import jp.xenia.emulator.BuildConfig
import jp.xenia.emulator.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** One change that passed the schema, with the value the game has now. */
private class Checked(
    val entry: AiSchema.Entry,
    val value: Long,
    val current: Long,
    val reason: String,
    val experimental: Boolean,
)

private sealed interface AiState {
    data object Asking : AiState

    class Suggest(val answerId: String, val notes: String, val changes: List<Checked>) : AiState

    class Message(val text: String) : AiState
}

/**
 * Recommended settings for [game]: asks the server, shows what would change and why (each change
 * can be left out), and writes the chosen ones as the game's own settings.
 */
@Composable
internal fun AiConfigScreen(state: AppState, game: Game, back: () -> Unit) {
    val context = LocalContext.current
    val schema = remember { AiSchema.load(context) }
    var ai by remember { mutableStateOf<AiState>(AiState.Asking) }
    val requester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        val request = withContext(Dispatchers.IO) { buildRequest(context, state, schema, game) }
        ai = when (val result = AiConfigClient.suggest(request)) {
            is AiConfigClient.Result.Ok -> {
                val changes = check(context, state.library, schema, game, result.suggestion)
                if (changes.isEmpty()) {
                    AiState.Message(
                        buildString {
                            append(context.getString(R.string.ai_config_nothing))
                            if (result.suggestion.notes.isNotBlank()) append("\n\n").append(result.suggestion.notes)
                        },
                    )
                } else {
                    AiState.Suggest(result.suggestion.answerId, result.suggestion.notes, changes)
                }
            }
            is AiConfigClient.Result.ServerError -> AiState.Message(errorText(context, result.code))
            AiConfigClient.Result.NetworkError -> AiState.Message(context.getString(R.string.ai_config_network_error))
        }
    }

    val title = game.name + " · " + stringResource(R.string.ai_config_title)
    SubScreen(title, listOf("A" to stringResource(R.string.hint_select)), back) {
        when (val current = ai) {
            AiState.Asking -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = LIST) {
                item { Text(stringResource(R.string.ai_config_asking), color = Kiwi.InkSoft, fontSize = 16.sp) }
                item { ActionTile(stringResource(R.string.import_cancel), Kiwi.Slate, back, requester) }
                item { ActionTile(stringResource(R.string.ai_config_privacy), Kiwi.Slate, { openPrivacy(context) }) }
            }
            is AiState.Suggest -> {
                // Every change starts chosen; A on one leaves it out or takes it back.
                val left = remember(current) { mutableStateListOf<String>() }
                val chosen = current.changes.filter { it.entry.key !in left }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = LIST) {
                    if (current.notes.isNotBlank()) {
                        item { Text(current.notes, color = Kiwi.Ink, fontSize = 15.sp) }
                    }
                    items(current.changes, key = { it.entry.key }) { change ->
                        val included = change.entry.key !in left
                        ChangeTile(context, change, included, if (change == current.changes.first()) requester else null) {
                            if (included) left += change.entry.key else left -= change.entry.key
                        }
                    }
                    item {
                        ActionTile(
                            stringResource(R.string.ai_config_apply, chosen.size),
                            if (chosen.isEmpty()) Kiwi.Slate else Kiwi.Green,
                            {
                                if (chosen.isNotEmpty()) {
                                    AiConfigStore.apply(
                                        context, state.library, schema, game, current.answerId,
                                        chosen.associate { it.entry.key to it.value },
                                    )
                                    Toast.makeText(context, R.string.ai_config_applied, Toast.LENGTH_LONG).show()
                                    back()
                                }
                            },
                        )
                    }
                    item { ActionTile(stringResource(R.string.import_cancel), Kiwi.Slate, back) }
                    item { ActionTile(stringResource(R.string.ai_config_privacy), Kiwi.Slate, { openPrivacy(context) }) }
                    item { Text(stringResource(R.string.ai_config_undo_hint), color = Kiwi.InkSoft, fontSize = 13.sp) }
                }
            }
            is AiState.Message -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = LIST) {
                item { Text(current.text, color = Kiwi.Ink, fontSize = 16.sp) }
                item { ActionTile(stringResource(R.string.ai_config_close), Kiwi.Slate, back, requester) }
            }
        }
    }
    InitialFocus(requester, ai)
}

/** Tells the player which games had their recommended settings undone after a failed start, and offers to play again. */
@Composable
internal fun AiRevertedScreen(state: AppState, notices: List<AiConfigStore.Notice>, onLaunch: (Game, GameFile) -> Unit) {
    val context = LocalContext.current
    val requester = remember { FocusRequester() }
    val close = {
        AiConfigStore.clearNotices(context, notices.map { it.gameKey })
        state.screen = Screen.Dashboard
    }
    SubScreen(stringResource(R.string.ai_config_reverted_title), listOf("A" to stringResource(R.string.hint_select)), close) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = LIST) {
            items(notices, key = { it.gameKey }) { notice ->
                val name = notice.titleName.ifBlank { notice.gameKey }
                Text(
                    stringResource(
                        if (notice.outcome == AiConfigClient.Outcome.NO_BOOT.wire) R.string.ai_config_reverted_no_boot
                        else R.string.ai_config_reverted_crash,
                        name,
                    ),
                    color = Kiwi.Ink, fontSize = 16.sp,
                )
            }
            // Only one game can have been playing: the fallback is to start it again without them.
            val game = notices.firstNotNullOfOrNull { notice -> state.games.firstOrNull { it.key == notice.gameKey } }
            if (game != null) {
                item {
                    ActionTile(stringResource(R.string.ai_config_play_again, game.name), Kiwi.Green, {
                        close()
                        onLaunch(game, game.defaultFile(state.library.lastPlayedPath(game)))
                    }, requester)
                }
            }
            item { ActionTile(stringResource(R.string.ai_config_close), Kiwi.Slate, close, if (game == null) requester else null) }
        }
    }
    InitialFocus(requester)
}

@Composable
private fun ActionTile(text: String, color: Color, onClick: () -> Unit, focusRequester: FocusRequester? = null) {
    MetroTile(620.dp, 52.dp, color, onClick, focusRequester = focusRequester) {
        Text(
            text, color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp),
        )
    }
}

@Composable
private fun ChangeTile(context: Context, change: Checked, included: Boolean, focusRequester: FocusRequester?, onClick: () -> Unit) {
    val color = when {
        !included -> Kiwi.Slate.copy(alpha = 0.55f)
        change.experimental -> Color(0xFF9E7A1A)
        else -> Kiwi.GreenDeep
    }
    MetroTile(620.dp, if (change.reason.isBlank()) 64.dp else 92.dp, color, onClick, focusRequester = focusRequester) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val experimental = if (change.experimental) " · " + stringResource(R.string.ai_config_experimental) else ""
                Text(
                    title(context, change.entry) + experimental, color = Color.White, fontSize = 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    valueText(context, change.entry, change.current) + "  →  " + valueText(context, change.entry, change.value),
                    color = Kiwi.GreenPale, fontSize = 15.sp, maxLines = 1,
                )
                if (change.reason.isNotBlank()) {
                    Text(
                        change.reason, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                stringResource(if (included) R.string.ai_config_included else R.string.ai_config_left_out),
                color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/** The request: the game, the device and every recommendable value that differs from its default. */
private fun buildRequest(context: Context, state: AppState, schema: AiSchema, game: Game): AiConfigClient.Request {
    val current = schema.settings.mapNotNull { entry ->
        val value = AiConfigStore.effectiveValue(context, state.library, game.key, entry)
        if (value == entry.default) null else entry.key to schema.wire(entry, value)
    }.toMap()
    val file = game.defaultFile(state.library.lastPlayedPath(game))
    return AiConfigClient.Request(
        appVersion = BuildConfig.VERSION_NAME,
        schemaVersion = schema.schemaVersion,
        titleId = game.titleIdHex,
        titleName = game.name.take(128),
        gameVersion = file.version.take(32),
        language = Locale.getDefault().language,
        device = AiConfigClient.device(context, state.drivers.driverFor(game.key)),
        current = current,
    )
}

private fun check(
    context: Context,
    library: Library,
    schema: AiSchema,
    game: Game,
    suggestion: AiConfigClient.Suggestion,
): List<Checked> = suggestion.changes.mapNotNull { change ->
    val entry = schema.entry(change.key) ?: return@mapNotNull null
    // A risky setting only when the server marks it experimental (a curated answer has a precedent).
    if (entry.isRisky && !change.experimental) return@mapNotNull null
    val value = schema.normalize(change.key, change.value) ?: return@mapNotNull null
    val now = AiConfigStore.effectiveValue(context, library, game.key, entry)
    if (value == now) return@mapNotNull null
    Checked(entry, value, now, change.reason, change.experimental || entry.isRisky)
}.distinctBy { it.entry.key }

/**
 * What the feature sends and keeps, in Spanish or English. For now this is the Lemon project's
 * page: the same server answers every app.
 */
private fun openPrivacy(context: Context) {
    val url = if (Locale.getDefault().language == "es") {
        "https://lemon-emu.org/es/privacidad-ia.html"
    } else {
        "https://lemon-emu.org/privacy-ai.html"
    }
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Toast.makeText(context, url, Toast.LENGTH_LONG).show() }
}

private fun appOption(entry: AiSchema.Entry): GameOption? = when (entry.key) {
    "draw_resolution_scale" -> GameOption.RESOLUTION
    "kiwi_rtz_software_emulation" -> GameOption.RTZ
    else -> null
}

/** The setting's name: the game option's title for the visible ones, else the cvar's name. */
private fun title(context: Context, entry: AiSchema.Entry): String =
    appOption(entry)?.let { context.getString(optionTitle(it)) } ?: entry.key

/** A value as a player reads it: On/Off, an enum's name or the number. */
private fun valueText(context: Context, entry: AiSchema.Entry, value: Long): String {
    if (entry.isBool) return context.getString(if (value != 0L) R.string.opt_on else R.string.opt_off)
    entry.enum.firstOrNull { it.second == value }?.let { return it.first }
    return value.toString()
}

private fun errorText(context: Context, code: String): String = when (code) {
    "rate_limited" -> context.getString(R.string.ai_config_rate_limited)
    "schema_unknown" -> context.getString(R.string.ai_config_update_app)
    "ai_unavailable" -> context.getString(R.string.ai_config_unavailable)
    else -> context.getString(R.string.ai_config_server_error, code)
}
