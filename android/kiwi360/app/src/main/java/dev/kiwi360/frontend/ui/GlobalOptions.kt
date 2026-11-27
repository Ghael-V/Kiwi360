// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import dev.kiwi360.frontend.effects.PostChain
import dev.kiwi360.frontend.effects.PostEffectsStore
import dev.kiwi360.frontend.effects.PostPreset
import dev.kiwi360.frontend.library.GameOption
import dev.kiwi360.frontend.overlay.GameLiveSettings
import dev.kiwi360.frontend.overlay.MENU_POST_EFFECTS
import dev.kiwi360.frontend.overlay.MENU_SETTINGS
import dev.kiwi360.frontend.overlay.MenuSetting
import jp.xenia.emulator.R

/**
 * The in-game menu's settings offered here; its HUD is the GameOption one, and its image
 * effects are the effects tile, which opens the editor.
 */
private val GLOBAL_MENU_SETTINGS: List<MenuSetting>
    get() = MENU_SETTINGS.filter { it.key != "hud" && it.key != MENU_POST_EFFECTS }

/**
 * Settings for every game: what a game uses unless its own options or the
 * in-game menu chose something else for it.
 */
@Composable
internal fun GlobalOptionsScreen(state: AppState) {
    val context = LocalContext.current
    val requester = remember { FocusRequester() }
    val menuSettings = remember { GameLiveSettings.global(context) }
    // Re-read after each change.
    var revision by remember { mutableIntStateOf(0) }
    val back = { state.screen = Screen.Dashboard }
    SubScreen(stringResource(R.string.global_options), listOf("A" to stringResource(R.string.hint_select)), back) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = LIST) {
            item {
                Text(stringResource(R.string.global_options_hint), color = Kiwi.InkSoft, fontSize = 14.sp)
            }
            items(GameOption.entries) { option ->
                val value = remember(revision) { state.library.globalOption(option) }
                OptionRow(
                    stringResource(optionTitle(option)),
                    optionDetail(option)?.let { stringResource(it) },
                    optionLabel(option, value, global = true),
                    changed = value.isNotEmpty() && option != GameOption.HUD,
                    focusRequester = if (option == GameOption.entries.first()) requester else null,
                ) {
                    val values = option.globalValues
                    val next = values[(values.indexOf(value) + 1) % values.size]
                    state.library.setGlobalOption(option, next)
                    if (option == GameOption.HUD) state.hud = next
                    revision++
                }
            }
            item {
                val chain = remember(revision) { PostEffectsStore.global(context) }
                OptionRow(
                    stringResource(R.string.pp_title),
                    PostChain.summary(context, chain),
                    PostPreset.label(context, chain),
                    changed = chain.isNotEmpty(),
                ) { state.screen = Screen.PostEffects(null) }
            }
            item {
                Text(
                    stringResource(R.string.global_options_menu), color = Kiwi.InkSoft, fontSize = 14.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(GLOBAL_MENU_SETTINGS, key = { it.key }) { setting ->
                val value = remember(revision) { menuSettings.all().opt(setting.extraKey)?.toString() ?: "" }
                val shown = setting.values.firstOrNull { it.value == value }
                OptionRow(
                    stringResource(setting.title), null,
                    when {
                        value.isEmpty() -> stringResource(R.string.opt_default)
                        else -> shown?.text ?: shown?.label?.takeIf { it != 0 }?.let { stringResource(it) } ?: value
                    },
                    changed = value.isNotEmpty(),
                ) {
                    val values = listOf("") + setting.values.map { it.value }
                    val next = values[(values.indexOf(value) + 1) % values.size]
                    if (next.isEmpty()) menuSettings.remove(setting.extraKey) else menuSettings.put(setting.extraKey, setting.kept(next))
                    revision++
                }
            }
            item {
                MetroTile(
                    620.dp, 48.dp, Kiwi.Slate,
                    onClick = {
                        GameOption.entries.forEach { state.library.setGlobalOption(it, if (it == GameOption.HUD) "fps" else "") }
                        state.hud = "fps"
                        menuSettings.clear()
                        revision++
                    },
                ) {
                    Text(
                        stringResource(R.string.opt_reset), color = Color.White, fontSize = 16.sp,
                        modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp),
                    )
                }
            }
        }
    }
    InitialFocus(requester)
}

@Composable
private fun OptionRow(
    title: String,
    detail: String?,
    value: String,
    changed: Boolean,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    MetroTile(
        620.dp, 56.dp, if (changed) Kiwi.GreenDeep else Kiwi.Slate,
        onClick = onClick, focusRequester = focusRequester,
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail != null) {
                    Text(detail, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(value, color = Kiwi.GreenPale, fontSize = 17.sp, maxLines = 1)
        }
    }
}
