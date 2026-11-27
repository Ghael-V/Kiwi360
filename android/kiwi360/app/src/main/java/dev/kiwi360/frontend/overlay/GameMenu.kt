// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.overlay

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kiwi360.frontend.effects.POST_EFFECTS_CUSTOM_KEY
import dev.kiwi360.frontend.effects.POST_EFFECTS_CVAR
import dev.kiwi360.frontend.effects.PostChain
import dev.kiwi360.frontend.effects.PostEffectsStore
import dev.kiwi360.frontend.effects.PostPreset
import dev.kiwi360.frontend.ui.ButtonHints
import dev.kiwi360.frontend.ui.Kiwi
import dev.kiwi360.frontend.ui.MetroTile
import jp.xenia.emulator.R

/** One value of a menu setting: what the cvar holds, and how it is shown. */
internal data class MenuValue(val value: String, @StringRes val label: Int = 0, val text: String? = null)

/**
 * A setting the menu cycles through. [cvar] null means a setting of the app
 * rather than of the emulator ([key] "hud" or "touch"). [isInt] keeps the
 * value as a number for the next launches.
 */
internal class MenuSetting(
    val key: String,
    @StringRes val title: Int,
    val cvar: String?,
    val values: List<MenuValue>,
    val isInt: Boolean,
    /** Maps any other current value onto one of [values]. */
    val normalize: (String) -> String = { it },
    /** How a value outside [values] is shown; 0 shows the value itself. */
    @StringRes val otherLabel: Int = 0,
) {
    /** The launch extra it is kept as. */
    val extraKey: String get() = cvar?.let { "cvar.$it" } ?: "kiwi.$key"

    /** [value] as it is kept: a number for an Int cvar. */
    fun kept(value: String): Any = if (isInt) (value.toIntOrNull() ?: 0) else value
}

/** [MenuSetting.key] of the image effects: Off, the presets, then the game's own chain if any. */
internal const val MENU_POST_EFFECTS = "post_effects"

/**
 * Also offered for every game in Settings (the HUD there is a GameOption, and the image
 * effects are the editor's tile).
 */
internal val MENU_SETTINGS = listOf(
    MenuSetting(
        "frame_gen", R.string.menu_frame_gen, "kiwi_frame_gen",
        listOf(
            MenuValue("0", R.string.opt_off), MenuValue("1", R.string.menu_fg_auto),
            MenuValue("2", text = "x2"), MenuValue("3", text = "x3"), MenuValue("4", text = "x4"),
        ),
        isInt = true,
    ),
    MenuSetting(
        "frame_gen_target", R.string.menu_fg_target, "kiwi_frame_gen_target",
        listOf("30", "45", "60", "90", "120").map { MenuValue(it, text = "$it FPS") },
        isInt = true,
        normalize = { value -> listOf("30", "45", "60", "90", "120").minByOrNull { kotlin.math.abs((value.toIntOrNull() ?: 60) - it.toInt()) }!! },
    ),
    MenuSetting(
        "frame_gen_quality", R.string.menu_fg_quality, "kiwi_frame_gen_quality",
        listOf(
            MenuValue("0", R.string.menu_quality_fast), MenuValue("1", R.string.menu_quality_balanced),
            MenuValue("2", R.string.menu_quality_high),
        ),
        isInt = true,
    ),
    MenuSetting(
        "scaling", R.string.menu_scaling, "postprocess_scaling_and_sharpening",
        listOf(
            MenuValue("bilinear", R.string.menu_scaling_bilinear), MenuValue("cas", text = "AMD CAS"),
            MenuValue("fsr", text = "AMD FSR 1"),
        ),
        isInt = false,
        normalize = { if (it == "cas" || it == "fsr") it else "bilinear" },
    ),
    MenuSetting(
        "antialiasing", R.string.menu_antialiasing, "postprocess_antialiasing",
        listOf(
            MenuValue("none", R.string.opt_off), MenuValue("fxaa", text = "FXAA"),
            MenuValue("fxaa_extreme", R.string.menu_fxaa_extreme),
        ),
        isInt = false,
        normalize = { if (it == "fxaa" || it == "fxaa_extreme") it else "none" },
    ),
    MenuSetting(
        MENU_POST_EFFECTS, R.string.menu_post_effects, POST_EFFECTS_CVAR,
        PostPreset.entries.map { MenuValue(it.chain, it.titleRes) },
        isInt = false,
        // A chain of the user's own stays as it is, and shows as "Custom".
        normalize = PostChain::normalize,
        otherLabel = R.string.pp_preset_custom,
    ),
    MenuSetting(
        "frame_limit", R.string.menu_frame_limit, "framerate_limit",
        listOf(MenuValue("0", R.string.menu_unlimited), MenuValue("30", text = "30 FPS"), MenuValue("60", text = "60 FPS")),
        isInt = true,
        normalize = { if (it == "30" || it == "60") it else "0" },
    ),
    MenuSetting(
        "touch", R.string.menu_touch_controls, null,
        listOf(
            MenuValue("auto", R.string.menu_touch_auto), MenuValue("always", R.string.menu_touch_always),
            MenuValue("never", R.string.menu_touch_never),
        ),
        isInt = false,
    ),
    MenuSetting(
        "hud", R.string.hud, null,
        listOf(MenuValue("full", R.string.hud_full), MenuValue("fps", R.string.hud_fps), MenuValue("off", R.string.hud_off)),
        isInt = false,
    ),
)

/**
 * The menu over a running game (Select + Start or Back): settings that apply
 * right away and are kept for this game, and leaving the game. The game keeps
 * running behind it; it just doesn't get the gamepad meanwhile.
 */
@Composable
internal fun GameMenuPanel(overlay: KiwiOverlay) {
    val host = overlay.menuHost ?: return
    val requester = remember { FocusRequester() }
    var revision by remember { mutableIntStateOf(0) }
    BackHandler { overlay.closeMenu() }

    fun currentValue(setting: MenuSetting): String = setting.normalize(
        when {
            setting.key == "touch" -> overlay.touchMode
            setting.cvar == null -> host.hudMode
            else -> host.getCvar(setting.cvar) ?: ""
        },
    )

    /**
     * The game's own effects chain to offer after the presets, if it has one: the running
     * chain when it is a custom one (kept, so cycling through the presets can come back to
     * it), else the one kept before.
     */
    fun customEffects(current: String): String? {
        PostEffectsStore.customOf(current)?.let { custom ->
            overlay.customPostEffects = custom
            overlay.liveSettings?.put(POST_EFFECTS_CUSTOM_KEY, custom)
            return custom
        }
        return PostEffectsStore.customOf(overlay.liveSettings?.all()?.optString(POST_EFFECTS_CUSTOM_KEY, ""))
            ?: overlay.customPostEffects
    }

    fun next(setting: MenuSetting) {
        val current = currentValue(setting)
        val values = setting.values.map { it.value } +
            (if (setting.key == MENU_POST_EFFECTS) listOfNotNull(customEffects(current)) else emptyList())
        val value = values[(values.indexOf(current) + 1) % values.size]
        if (setting.key == "touch") {
            overlay.touchMode = value
            overlay.liveSettings?.put("kiwi.touch", value)
        } else if (setting.cvar == null) {
            host.hudMode = value
            overlay.liveSettings?.put("kiwi.hud", value)
        } else if (host.setCvar(setting.cvar, value)) {
            overlay.liveSettings?.put(setting.extraKey, setting.kept(value))
        }
        revision++
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .pointerInput(Unit) { detectTapGestures { overlay.closeMenu() } },
    ) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(480.dp)
                .background(Kiwi.BackgroundTop)
                // Taps on the panel itself don't close it.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(horizontal = 28.dp, vertical = 16.dp),
        ) {
            Text(stringResource(R.string.menu_title), color = Kiwi.Ink, fontSize = 28.sp, fontWeight = FontWeight.Light)
            Text(stringResource(R.string.menu_kept_hint), color = Kiwi.InkSoft, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
            ) {
                item {
                    MetroTile(420.dp, 50.dp, Kiwi.Green, { overlay.closeMenu() }, focusRequester = requester) {
                        Text(
                            stringResource(R.string.menu_resume), color = Color.White, fontSize = 18.sp,
                            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp),
                        )
                    }
                }
                items(MENU_SETTINGS, key = { it.key }) { setting ->
                    val value = remember(revision) { currentValue(setting) }
                    val shown = setting.values.firstOrNull { it.value == value }
                    MetroTile(420.dp, 52.dp, Kiwi.Slate, { next(setting) }) {
                        Row(
                            Modifier.fillMaxSize().padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(setting.title), color = Color.White, fontSize = 16.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            Text(
                                shown?.text ?: shown?.label?.takeIf { it != 0 }?.let { stringResource(it) }
                                    ?: setting.otherLabel.takeIf { it != 0 }?.let { stringResource(it) } ?: value,
                                color = Kiwi.GreenPale, fontSize = 16.sp, maxLines = 1,
                            )
                        }
                    }
                }
                item {
                    MetroTile(420.dp, 50.dp, Kiwi.ButtonB, {
                        overlay.closeMenu()
                        overlay.askExit()
                    }) {
                        Text(
                            stringResource(R.string.menu_exit), color = Color.White, fontSize = 18.sp,
                            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            ButtonHints(listOf("A" to stringResource(R.string.menu_change), "B" to stringResource(R.string.menu_resume)))
        }
    }
    InitialFocus(requester)
}
