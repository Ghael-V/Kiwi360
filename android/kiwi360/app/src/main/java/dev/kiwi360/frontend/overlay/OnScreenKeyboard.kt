// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.overlay

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kiwi360.frontend.ui.Kiwi
import dev.kiwi360.frontend.ui.MetroTile
import jp.xenia.emulator.R

private val ROWS = listOf("1234567890", "qwertyuiop", "asdfghjklñ", "zxcvbnm,.-")
private val KEY_W = 48.dp
private val KEY_H = 34.dp
private val GAP = 5.dp

/**
 * A text entry for a gamepad: a key grid navigated with the d-pad, plus
 * shortcuts (X delete, Y space, LB shift, Start accept). A hardware keyboard
 * and touch work too.
 */
@Composable
fun OnScreenKeyboard(initial: String, maxLength: Int, onAccept: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var shift by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }

    fun type(s: String) {
        val next = text + s
        if (maxLength <= 0 || next.length <= maxLength) text = next
    }

    fun delete() {
        if (text.isNotEmpty()) text = text.substring(0, text.offsetByCodePoints(text.length, -1))
    }

    Column(
        Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val native = event.nativeKeyEvent
            when (native.keyCode) {
                AndroidKeyEvent.KEYCODE_BUTTON_X, AndroidKeyEvent.KEYCODE_DEL -> delete()
                AndroidKeyEvent.KEYCODE_BUTTON_Y -> type(" ")
                AndroidKeyEvent.KEYCODE_BUTTON_L1 -> shift = !shift
                AndroidKeyEvent.KEYCODE_BUTTON_START -> onAccept(text)
                else -> {
                    // A hardware keyboard types straight in.
                    if (AndroidKeyEvent.isGamepadButton(native.keyCode) || !native.isPrintingKey) {
                        return@onPreviewKeyEvent false
                    }
                    val char = native.unicodeChar
                    if (char == 0) return@onPreviewKeyEvent false
                    type(String(Character.toChars(char)))
                }
            }
            true
        },
        verticalArrangement = Arrangement.spacedBy(GAP),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(KEY_W * 10 + GAP * 9).height(38.dp).background(Color.White).border(1.dp, Kiwi.Green),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text + "▏", color = Kiwi.Ink, fontSize = 18.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp),
                )
            }
            if (maxLength > 0) {
                Text("${text.length}/$maxLength", color = Kiwi.InkSoft, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
            }
        }
        ROWS.forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                row.forEachIndexed { index, c ->
                    val label = if (shift) c.uppercase() else c.toString()
                    Key(label, KEY_W, focusRequester = if (rowIndex == 0 && index == 0) first else null) { type(label) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
            Key("⇧", KEY_W * 1.5f, color = if (shift) Kiwi.Green else Kiwi.Slate) { shift = !shift }
            Key(stringResource(R.string.overlay_space), KEY_W * 3.5f + GAP * 2) { type(" ") }
            Key("⌫", KEY_W * 1.5f) { delete() }
            Key(stringResource(R.string.overlay_accept), KEY_W * 3.5f + GAP * 2, color = Kiwi.Green) { onAccept(text) }
        }
    }
    InitialFocus(first)
}

@Composable
private fun Key(
    label: String,
    width: Dp,
    focusRequester: FocusRequester? = null,
    color: Color = Kiwi.Slate,
    onClick: () -> Unit,
) {
    MetroTile(width, KEY_H, color, onClick, focusRequester = focusRequester) {
        Text(label, color = Color.White, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center))
    }
}
