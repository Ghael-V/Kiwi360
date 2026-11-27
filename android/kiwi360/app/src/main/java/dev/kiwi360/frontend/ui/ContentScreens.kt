// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.ui

import android.text.format.Formatter
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import dev.kiwi360.frontend.library.AchievementStore
import dev.kiwi360.frontend.library.ContentItem
import dev.kiwi360.frontend.library.InstalledContent
import jp.xenia.emulator.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a content type folder holds, for the list. */
internal fun contentTypeLabel(type: Long): Int = when (type) {
    0x00000002L -> R.string.content_type_dlc
    0x000B0000L -> R.string.content_type_update
    0x00004000L -> R.string.content_type_installed
    0x00007000L, 0x000A0000L -> R.string.content_type_game
    0x00080000L -> R.string.content_type_demo
    0x000D0000L -> R.string.content_type_arcade
    0x00040000L -> R.string.content_type_cache
    else -> R.string.content_type_other
}

/**
 * Installed content, the largest first: A moves an item between internal storage and the
 * SD card, Y twice deletes it. B during a move cancels it and leaves the original as it was.
 */
@Composable
internal fun ContentScreen(state: AppState) {
    val context = LocalContext.current
    val store = remember { InstalledContent(context) }
    val requester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<ContentItem>?>(null) }
    var sd by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var names by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    LaunchedEffect(revision) {
        val (list, hasSd) = withContext(Dispatchers.IO) { store.list() to store.sdAvailable() }
        names = withContext(Dispatchers.IO) {
            val achievements = AchievementStore(context).summaries()
            list.map { it.titleId }.distinct().associateWith { titleId ->
                state.games.firstOrNull { it.titleId == titleId }?.name
                    ?: achievements.firstOrNull { it.titleId == titleId }?.name
                    ?: "%08X".format(titleId)
            }
        }
        items = list
        sd = hasSd
    }
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    fun resultText(result: InstalledContent.Result, done: Int) = context.getString(
        when (result) {
            InstalledContent.Result.DONE -> done
            InstalledContent.Result.NO_SPACE -> R.string.content_no_space
            InstalledContent.Result.NO_SD -> R.string.content_no_sd
            InstalledContent.Result.FAILED -> R.string.content_failed
        },
    )

    fun move(item: ContentItem) {
        if (job != null) return
        if (!sd) {
            status = context.getString(R.string.content_no_sd)
            return
        }
        pendingDelete = null
        val moving = if (item.onSd) R.string.content_moving_internal else R.string.content_moving_sd
        status = context.getString(moving, 0)
        job = scope.launch {
            var last = -1
            val result = try {
                withContext(Dispatchers.IO) {
                    store.move(item) { done, total ->
                        val percent = if (total > 0) (done * 100 / total).toInt().coerceAtMost(100) else 0
                        if (percent != last) {
                            last = percent
                            scope.launch { status = context.getString(moving, percent) }
                        }
                    }
                }
            } finally {
                job = null
            }
            status = resultText(result, if (item.onSd) R.string.content_moved_internal else R.string.content_moved_sd)
            revision++
        }
    }

    fun delete(item: ContentItem) {
        if (job != null) return
        if (pendingDelete != item.key) {
            pendingDelete = item.key
            return
        }
        pendingDelete = null
        status = context.getString(R.string.content_deleting)
        job = scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { store.delete(item) }
            } finally {
                job = null
            }
            status = resultText(result, R.string.content_deleted)
            revision++
        }
    }

    SubScreen(
        stringResource(R.string.content_title),
        listOf("A" to stringResource(R.string.content_move), "Y" to stringResource(R.string.content_delete)),
        {
            // While a move runs, B stops it: the copy is discarded and the original stays.
            val running = job
            if (running != null) {
                running.cancel()
                status = context.getString(R.string.content_move_cancelled)
            } else {
                state.screen = Screen.Dashboard
            }
        },
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = LIST) {
            item {
                Text(
                    status ?: stringResource(if (sd) R.string.content_hint else R.string.content_hint_no_sd),
                    color = Kiwi.Ink, fontSize = 15.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }
            val list = items
            when {
                list == null -> item {
                    Text(stringResource(R.string.content_loading), color = Kiwi.InkSoft, fontSize = 16.sp, modifier = Modifier.padding(start = 4.dp))
                }
                list.isEmpty() -> item {
                    MetroTile(620.dp, 56.dp, Kiwi.Slate, { state.screen = Screen.Dashboard }, focusRequester = requester) {
                        Text(
                            stringResource(R.string.content_empty), color = Color.White, fontSize = 16.sp,
                            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp),
                        )
                    }
                }
                else -> items(list, key = { it.key }) { item ->
                    val deleting = pendingDelete == item.key
                    MetroTile(
                        620.dp, 62.dp, if (deleting) Kiwi.ButtonB else Kiwi.Slate,
                        { move(item) },
                        onSecondary = { delete(item) },
                        focusRequester = if (item == list.first()) requester else null,
                    ) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(names[item.titleId] ?: "%08X".format(item.titleId), color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (deleting) {
                                        stringResource(R.string.content_delete_confirm)
                                    } else {
                                        stringResource(contentTypeLabel(item.type), "%08X".format(item.type)) + "  ·  " + size(item.sizeBytes)
                                    },
                                    color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                stringResource(if (item.onSd) R.string.content_sd else R.string.content_internal),
                                color = if (item.onSd) Kiwi.GreenPale else Color.White.copy(alpha = 0.8f), fontSize = 15.sp,
                            )
                        }
                    }
                }
            }
        }
    }
    InitialFocus(requester, items)
}
