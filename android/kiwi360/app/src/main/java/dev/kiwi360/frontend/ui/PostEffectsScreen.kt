// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kiwi360.frontend.effects.EffectEntry
import dev.kiwi360.frontend.effects.MAX_POST_EFFECTS
import dev.kiwi360.frontend.effects.PostChain
import dev.kiwi360.frontend.effects.PostEffect
import dev.kiwi360.frontend.effects.PostEffectsStore
import dev.kiwi360.frontend.effects.PostPreset
import dev.kiwi360.frontend.library.Game
import jp.xenia.emulator.R
import java.util.concurrent.atomic.AtomicInteger

/** An effect in the editor, with an id that follows it when it moves. */
private class Slot(val id: Int, val entry: EffectEntry)

/**
 * A row of the editor: left/right (or the arrows by touch) run [onStep], A (or a tap) runs
 * [onConfirm], else steps forward.
 */
private class EditorRow(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val value: String? = null,
    val color: Color = Kiwi.Slate,
    val height: Int = 48,
    val indent: Boolean = false,
    val dim: Boolean = false,
    val onStep: ((Int) -> Unit)? = null,
    val onConfirm: (() -> Unit)? = null,
)

/**
 * Editor of a post-processing chain: a preset, then every effect in order with its switch,
 * its place in the order and its values, then adding effects. [game] null edits the chain of
 * every game, else that game's own; every change is kept at once.
 */
@Composable
internal fun PostEffectsScreen(game: Game?, onBack: () -> Unit) {
    val context = LocalContext.current
    val ids = remember { AtomicInteger() }
    fun slotsOf(chain: String) = PostChain.decode(chain).map { Slot(ids.getAndIncrement(), it) }

    val initial = remember { if (game == null) PostEffectsStore.global(context) else PostEffectsStore.effective(context, game.key) }
    var slots by remember { mutableStateOf(slotsOf(initial)) }
    var hasOwn by remember { mutableStateOf(game != null && PostEffectsStore.game(context, game.key) != null) }
    // The last chain of the user's own, offered among the presets so trying them doesn't lose it.
    var lastCustom by remember {
        mutableStateOf(PostEffectsStore.customOf(initial) ?: game?.let { PostEffectsStore.gameCustom(context, it.key) })
    }
    var adding by remember { mutableStateOf(PostEffect.Bloom) }
    // The row to focus once it is laid out (after a change removes the focused one).
    var focusTarget by remember { mutableStateOf<String?>("preset") }
    var focusedKey by remember { mutableStateOf<String?>(null) }

    fun update(list: List<Slot>) {
        slots = list
        val chain = PostChain.encode(list.map { it.entry })
        PostEffectsStore.customOf(chain)?.let { lastCustom = it }
        if (game == null) {
            PostEffectsStore.setGlobal(context, chain)
        } else {
            PostEffectsStore.setGame(context, game.key, chain)
            hasOwn = true
        }
    }

    // By id rather than by index: a key repeat can come before the rows are rebuilt.
    fun edit(id: Int, change: (EffectEntry) -> EffectEntry) {
        val index = slots.indexOfFirst { it.id == id }
        if (index >= 0) update(slots.toMutableList().also { it[index] = Slot(id, change(it[index].entry)) })
    }

    val chain = PostChain.encode(slots.map { it.entry })
    val on = stringResource(R.string.opt_on)
    val off = stringResource(R.string.opt_off)
    val rows = buildList {
        val preset = PostPreset.of(chain)
        add(
            EditorRow(
                "preset",
                context.getString(R.string.pp_preset),
                preset?.let { context.getString(it.descriptionRes) } ?: PostChain.summary(context, chain),
                PostPreset.label(context, chain),
                color = if (chain.isEmpty()) Kiwi.Slate else Kiwi.GreenDeep,
                height = 60,
                onStep = { delta ->
                    // Off, the presets, then the user's own chain if there is one.
                    val choices = PostPreset.entries.map { it.chain } + listOfNotNull(lastCustom)
                    val index = choices.indexOf(PostChain.normalize(chain))
                    val next = if (index < 0) (if (delta > 0) 0 else choices.lastIndex) else (index + delta).mod(choices.size)
                    update(slotsOf(choices[next]))
                },
            ),
        )
        slots.forEachIndexed { i, slot ->
            val entry = slot.entry
            val effect = entry.effect
            val base = "e${slot.id}"
            add(
                EditorRow(
                    base,
                    "${i + 1}. ${effect.title(context)}",
                    context.getString(effect.descriptionRes),
                    if (entry.enabled) on else off,
                    color = if (entry.enabled) Kiwi.GreenDeep else Kiwi.Slate,
                    height = 60,
                    dim = !entry.enabled,
                    onStep = { edit(slot.id) { it.copy(enabled = !it.enabled) } },
                ),
            )
            add(
                EditorRow(
                    "$base:order", context.getString(R.string.pp_order), context.getString(R.string.pp_order_desc),
                    "${i + 1} / ${slots.size}", indent = true,
                    onStep = { delta ->
                        val from = slots.indexOfFirst { it.id == slot.id }
                        val target = (from + delta).coerceIn(0, slots.lastIndex)
                        if (from >= 0 && target != from) {
                            // The row moves with its effect; make sure it keeps the focus.
                            focusTarget = "$base:order"
                            update(slots.toMutableList().also { it.add(target, it.removeAt(from)) })
                        }
                    },
                ),
            )
            effect.params.forEachIndexed { p, param ->
                add(
                    EditorRow(
                        "$base:p$p", context.getString(param.labelRes), null, param.format(entry.values[p]), indent = true,
                        onStep = { delta ->
                            edit(slot.id) { current ->
                                current.copy(values = current.values.toMutableList().also { it[p] = param.step(it[p], delta) })
                            }
                        },
                    ),
                )
            }
            add(
                EditorRow(
                    "$base:remove", context.getString(R.string.pp_remove), color = Kiwi.ButtonB, indent = true,
                    onConfirm = {
                        val index = slots.indexOfFirst { it.id == slot.id }
                        if (index >= 0) {
                            focusTarget = slots.getOrNull(index + 1)?.let { "e${it.id}" } ?: "add"
                            update(slots.toMutableList().also { it.removeAt(index) })
                        }
                    },
                ),
            )
        }
        val full = slots.size >= MAX_POST_EFFECTS
        add(
            EditorRow(
                "add",
                context.getString(R.string.pp_add),
                if (full) context.getString(R.string.pp_add_full, MAX_POST_EFFECTS) else context.getString(R.string.pp_add_desc),
                adding.title(context),
                color = if (full) Kiwi.Slate else Kiwi.Green,
                height = 60,
                onStep = { delta -> adding = PostEffect.entries[(adding.ordinal + delta).mod(PostEffect.entries.size)] },
                onConfirm = { if (slots.size < MAX_POST_EFFECTS) update(slots + Slot(ids.getAndIncrement(), EffectEntry(adding))) },
            ),
        )
        if (slots.isNotEmpty()) {
            add(
                EditorRow(
                    "remove_all", context.getString(R.string.pp_remove_all), color = Kiwi.ButtonB,
                    onConfirm = {
                        focusTarget = "add"
                        update(emptyList())
                    },
                ),
            )
        }
        if (game != null && hasOwn) {
            add(
                EditorRow(
                    "use_global", context.getString(R.string.pp_use_global), context.getString(R.string.pp_use_global_desc),
                    onConfirm = {
                        PostEffectsStore.setGame(context, game.key, null)
                        onBack()
                    },
                ),
            )
        }
    }

    val listState = rememberLazyListState()
    // Scrolls a row about to get the focus into view, so it is laid out and can take it.
    LaunchedEffect(focusTarget) {
        val index = rows.indexOfFirst { it.key == focusTarget }
        if (index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            listState.scrollToItem((index - 1).coerceAtLeast(0))
        }
    }
    // A moved effect keeps the focus on its order row: follow it.
    LaunchedEffect(slots.map { it.id }) {
        val index = rows.indexOfFirst { it.key == focusedKey }
        if (index < 0) return@LaunchedEffect
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        if (item == null || item.offset < 0 || item.offset + item.size > info.viewportEndOffset) {
            listState.animateScrollToItem((index - 2).coerceAtLeast(0))
        }
    }

    val title = stringResource(R.string.pp_title)
    SubScreen(
        if (game == null) title else game.name + " · " + title,
        listOf("◀ ▶" to stringResource(R.string.menu_change), "A" to stringResource(R.string.hint_select)),
        onBack,
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(
                stringResource(R.string.pp_editor_hint), color = Kiwi.InkSoft, fontSize = 14.sp,
                modifier = Modifier.padding(start = LanePadding + 4.dp, top = 4.dp),
            )
            LazyColumn(
                Modifier.weight(1f),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = LIST,
            ) {
                items(rows, key = { it.key }) { row ->
                    val requester = remember { FocusRequester() }
                    EditorTile(row, requester) {
                        focusedKey = row.key
                        if (focusTarget == row.key) focusTarget = null
                    }
                    if (focusTarget == row.key) {
                        LaunchedEffect(Unit) {
                            withFrameNanos { }
                            try {
                                requester.requestFocus()
                            } catch (_: IllegalStateException) {
                            }
                            focusTarget = null
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorTile(row: EditorRow, requester: FocusRequester, onFocused: () -> Unit) {
    val indent = if (row.indent) 28.dp else 0.dp
    MetroTile(
        620.dp - indent, row.height.dp, row.color,
        onClick = { row.onConfirm?.invoke() ?: row.onStep?.invoke(1) },
        modifier = Modifier
            .padding(start = indent)
            .onPreviewKeyEvent { event ->
                val delta = when (event.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> -1
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> 1
                    else -> return@onPreviewKeyEvent false
                }
                // Key repeats step on while the direction is held.
                if (event.type == KeyEventType.KeyDown) row.onStep?.invoke(delta)
                true
            },
        focusRequester = requester,
        onFocused = onFocused,
    ) {
        val textAlpha = if (row.dim) 0.6f else 1f
        Row(Modifier.fillMaxSize().padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    row.title, color = Color.White.copy(alpha = textAlpha), fontSize = if (row.height > 48) 17.sp else 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                row.subtitle?.let {
                    Text(
                        it, color = Color.White.copy(alpha = 0.7f * textAlpha), fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val step = row.onStep
            if (step != null) {
                // Touch: the arrows step down and up; a tap elsewhere on the row steps up.
                StepArrow("‹") { step(-1) }
            }
            row.value?.let {
                Text(
                    it, color = Kiwi.GreenPale, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = if (step == null) 8.dp else 0.dp),
                )
            }
            if (step != null) {
                StepArrow("›") { step(1) }
            }
        }
    }
}

/** A touch target beside a value; not focusable, so the d-pad only moves between rows. */
@Composable
private fun StepArrow(glyph: String, onTap: () -> Unit) {
    Text(
        glyph, color = Kiwi.GreenPale, fontSize = 26.sp,
        modifier = Modifier
            .fillMaxHeight()
            .width(40.dp)
            .pointerInput(onTap) { detectTapGestures { onTap() } }
            .padding(top = 8.dp),
        textAlign = TextAlign.Center,
    )
}
