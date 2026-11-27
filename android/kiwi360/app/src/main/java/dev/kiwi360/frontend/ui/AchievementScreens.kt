// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.ui

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kiwi360.frontend.library.Achievement
import dev.kiwi360.frontend.library.AchievementStore
import dev.kiwi360.frontend.library.AchievementSummary
import jp.xenia.emulator.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "12/50 · 150/1000 G" */
@Composable
internal fun achievementProgress(summary: AchievementSummary): String = stringResource(
    R.string.achievements_progress, summary.unlocked, summary.total, summary.pointsEarned, summary.pointsTotal,
)

/** The achievements section of the dashboard: every title the profile has played. */
@Composable
internal fun AchievementsPivot(state: AppState, unit: Dp, padding: PaddingValues) {
    val context = LocalContext.current
    val requester = remember { FocusRequester() }
    val summaries by produceState<List<AchievementSummary>?>(null, state.resumeTick) {
        value = withContext(Dispatchers.IO) { AchievementStore(context).summaries() }
    }
    val list = summaries ?: return
    if (list.isEmpty()) {
        Text(
            stringResource(R.string.achievements_none),
            color = Kiwi.InkSoft, fontSize = 22.sp, fontWeight = FontWeight.Light,
            modifier = Modifier.padding(padding),
        )
        return
    }
    val focusIndex = list.indexOfFirst { "ach:%08X".format(it.titleId) == state.focusKey }.coerceAtLeast(0)
    LazyHorizontalGrid(
        rows = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = padding,
    ) {
        items(list, key = { it.titleId }) { summary ->
            val key = "%08X".format(summary.titleId)
            val game = state.games.firstOrNull { it.key == key }
            val icon = rememberImage(game?.iconPath ?: state.library.iconFile(key)?.absolutePath, 256)
            val color = remember(icon) { tileColorFor(icon, tileColorForKey(key)) }
            MetroTile(
                unit * 1.3f, unit, color,
                onClick = { state.screen = Screen.Achievements(summary.titleId, fromDetail = false) },
                focusRequester = if (list.indexOf(summary) == focusIndex) requester else null,
                onFocused = { state.focusKey = "ach:$key" },
            ) {
                if (icon != null) IconArt(icon, unit * 0.42f, Modifier.padding(bottom = 30.dp))
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        game?.name ?: summary.name, color = Color.White, fontSize = 16.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(achievementProgress(summary), color = Kiwi.GreenPale, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
    }
    InitialFocus(requester)
}

/** Every achievement of one title. */
@Composable
internal fun AchievementsScreen(state: AppState, titleId: Long, fromDetail: Boolean) {
    val context = LocalContext.current
    val key = "%08X".format(titleId)
    val requester = remember { FocusRequester() }
    val data by produceState<Pair<AchievementSummary?, List<Achievement>>?>(null, titleId) {
        value = withContext(Dispatchers.IO) {
            val store = AchievementStore(context)
            store.summaries().firstOrNull { it.titleId == titleId } to store.achievements(titleId)
        }
    }
    val name = state.games.firstOrNull { it.key == key }?.name ?: data?.first?.name ?: key
    val back = { state.screen = if (fromDetail) Screen.Detail(key) else Screen.Dashboard }
    SubScreen(name + " · " + stringResource(R.string.achievements), emptyList(), back) {
        val (summary, achievements) = data ?: return@SubScreen
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = LIST) {
            item {
                Text(
                    summary?.let { achievementProgress(it) } ?: stringResource(R.string.achievements_none),
                    color = Kiwi.InkSoft, fontSize = 16.sp,
                )
            }
            items(achievements, key = { it.id }) { achievement ->
                AchievementRow(
                    titleId, achievement,
                    focusRequester = if (achievement == achievements.first()) requester else null,
                )
            }
        }
    }
    InitialFocus(requester, data != null)
}

@Composable
private fun AchievementRow(titleId: Long, achievement: Achievement, focusRequester: FocusRequester?) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, titleId, achievement.id) {
        if (achievement.hasIcon) {
            value = withContext(Dispatchers.IO) {
                AchievementStore(context).achievementIcon(titleId, achievement.id)
                    ?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            }
        }
    }
    val secret = achievement.hidden && !achievement.unlocked
    // Focusable so the d-pad scrolls the list; selecting does nothing.
    MetroTile(
        760.dp, 72.dp, if (achievement.unlocked) Kiwi.GreenDeep else Kiwi.Slate,
        onClick = {}, focusRequester = focusRequester,
    ) {
        Row(Modifier.fillMaxSize().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).background(Color.Black.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                val image = icon
                if (image != null) {
                    Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Text(if (secret) "?" else "🔒", color = Color.White.copy(alpha = 0.7f), fontSize = 22.sp)
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    if (secret) stringResource(R.string.achievement_secret) else achievement.name,
                    color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        secret -> stringResource(R.string.achievement_secret_desc)
                        achievement.unlocked -> achievement.description
                        else -> achievement.lockedDescription
                    },
                    color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
                Text("${achievement.points} G", color = Kiwi.GreenPale, fontSize = 16.sp)
                if (achievement.unlocked) {
                    Text(
                        achievement.unlockedAt?.let {
                            stringResource(
                                R.string.achievement_unlocked_on,
                                DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH),
                            )
                        } ?: stringResource(R.string.achievement_unlocked),
                        color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

/** "3 h 20 min" or "45 min"; null for no time. */
@Composable
internal fun playTimeText(seconds: Long): String? {
    if (seconds < 60) return null
    val minutes = seconds / 60
    return if (minutes >= 60) {
        stringResource(R.string.play_time_hours, (minutes / 60).toInt(), (minutes % 60).toInt())
    } else {
        stringResource(R.string.play_time_minutes, minutes.toInt())
    }
}
