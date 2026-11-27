// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kiwi360.frontend.CpuSupport
import jp.xenia.emulator.R

/**
 * Tells the player this device's CPU can't run games (see [CpuSupport]): once at the
 * library's first start ([notice]), and whenever a game is started. The library
 * stays usable; closing returns to [back].
 */
@Composable
internal fun UnsupportedCpuScreen(state: AppState, back: Screen, notice: Boolean) {
    val context = LocalContext.current
    val requester = remember { FocusRequester() }
    val close = {
        if (notice) CpuSupport.markNoticeShown(context)
        state.screen = back
    }
    SubScreen(stringResource(R.string.cpu_unsupported_title), listOf("A" to stringResource(R.string.hint_select)), close) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = LIST) {
            item {
                Text(
                    stringResource(R.string.cpu_unsupported_body, CpuSupport.result.cpuName),
                    color = Kiwi.Ink, fontSize = 18.sp,
                )
            }
            item { Text(stringResource(R.string.cpu_unsupported_library), color = Kiwi.InkSoft, fontSize = 16.sp) }
            item {
                MetroTile(620.dp, 52.dp, Kiwi.Slate, close, focusRequester = requester) {
                    Text(
                        stringResource(R.string.cpu_unsupported_close), color = Color.White, fontSize = 17.sp,
                        modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp),
                    )
                }
            }
        }
    }
    InitialFocus(requester)
}
