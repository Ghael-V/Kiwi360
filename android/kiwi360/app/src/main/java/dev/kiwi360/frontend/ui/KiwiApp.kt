// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.ui

import android.text.format.DateUtils
import android.widget.Toast
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import dev.kiwi360.frontend.aiconfig.AiConfigStore
import dev.kiwi360.frontend.drivers.GpuDriverStore
import dev.kiwi360.frontend.effects.PostChain
import dev.kiwi360.frontend.effects.PostEffectsStore
import dev.kiwi360.frontend.effects.PostPreset
import dev.kiwi360.frontend.logs.KiwiLogs
import dev.kiwi360.frontend.library.Game
import dev.kiwi360.frontend.library.GameFile
import dev.kiwi360.frontend.library.GameOption
import dev.kiwi360.frontend.overlay.OnScreenKeyboard
import dev.kiwi360.frontend.overlay.isValidGamertag
import dev.kiwi360.frontend.overlay.keyboardHints
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import dev.kiwi360.frontend.ImportSavesActivity
import dev.kiwi360.frontend.library.SaveStore
import dev.kiwi360.frontend.library.AchievementStore
import dev.kiwi360.frontend.library.AchievementSummary
import dev.kiwi360.frontend.library.InstalledContent
import dev.kiwi360.frontend.library.Library
import dev.kiwi360.frontend.library.PlayTime
import dev.kiwi360.frontend.settings.ConsoleLanguage
import dev.kiwi360.frontend.update.Release
import dev.kiwi360.frontend.update.Updater
import androidx.annotation.StringRes
import jp.xenia.emulator.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Screen-level state shared by the frontend. */
class AppState(val library: Library, val drivers: GpuDriverStore) {
    var games by mutableStateOf<List<Game>>(emptyList())
    var scanning by mutableStateOf(false)
    var scanRequest by mutableIntStateOf(0)
    var resumeTick by mutableIntStateOf(0)
    var hud by mutableStateOf(library.hudMode)
    var gamertag by mutableStateOf(library.gamertag)
    var consoleName by mutableStateOf(library.consoleName)
    var folders by mutableStateOf(library.folders)
        private set
    var screen by mutableStateOf<Screen>(Screen.Dashboard)
    var pivot by mutableIntStateOf(0)
    /** The tile focused last, so coming back from a sub-screen lands on it again. */
    var focusKey by mutableStateOf<String?>(null)
    /** Bumped when drivers are installed, removed or chosen. */
    var driverRevision by mutableIntStateOf(0)
    /** Set when another app opened a game's options: Back there returns to that app. */
    var returnToCaller: (() -> Unit)? = null
    /** A newer release found at start, offered once the dashboard is showing. */
    var pendingUpdate by mutableStateOf<Release?>(null)

    fun updateFolders(value: Set<String>) {
        library.folders = value
        folders = value
        scanRequest++
    }
}

sealed interface Screen {
    data object Dashboard : Screen
    data class Detail(val key: String) : Screen
    data object Folders : Screen
    data class Browse(val path: String?) : Screen
    data object About : Screen
    /** A game's own settings; Back returns to its detail, or to the dashboard if opened from a tile. */
    data class GameOptions(val key: String, val fromDetail: Boolean = true) : Screen
    /** The settings for every game. */
    data object GlobalOptions : Screen
    /** Text settings edited with the on-screen keyboard: "gamertag" or "console". */
    data class EditText(val field: String) : Screen
    data object Drivers : Screen
    data object DriverRepos : Screen
    data class DriverReleases(val repo: Int) : Screen
    /** A title's achievements; Back returns to its detail if it came from there. */
    data class Achievements(val titleId: Long, val fromDetail: Boolean) : Screen
    /** Installed content: delete it or move it to the SD card. */
    data object Content : Screen

    data class AiConfig(val key: String, val fromDetail: Boolean) : Screen

    /**
     * The image effects chain: [key] null edits the one for every game (Back returns to
     * the game settings), else that game's own (Back returns to its options).
     */
    data class PostEffects(val key: String?, val fromDetail: Boolean = true) : Screen

    /** Games whose recommended settings were undone after a failed start. */
    data class AiReverted(val notices: List<dev.kiwi360.frontend.aiconfig.AiConfigStore.Notice>) : Screen

    /**
     * This device's CPU can't run games: shown once at the first start ([notice]) and
     * when a game is started. Closing returns to [back].
     */
    data class UnsupportedCpu(val back: Screen, val notice: Boolean = false) : Screen

    /** A newer release: download and install it, skip it or leave it for later; closing returns to [back]. */
    data class Update(val release: Release, val back: Screen) : Screen

    /** Settings, Updates: the check at start, a check now and the installed version. */
    data object Updates : Screen
}

@Composable
fun KiwiApp(state: AppState, onLaunch: (Game, GameFile) -> Unit) {
    LaunchedEffect(state.scanRequest) {
        if (!state.library.hasChosenFolders) {
            state.updateFolders(state.library.suggestedFolders().map { it.absolutePath }.toSet())
            return@LaunchedEffect
        }
        state.scanning = true
        if (state.games.isEmpty()) {
            state.games = withContext(Dispatchers.IO) { state.library.cachedGames() }
        }
        state.games = withContext(Dispatchers.IO) { state.library.scan() }
        state.scanning = false
    }
    LaunchedEffect(state.resumeTick) {
        if (state.resumeTick > 0 && state.games.isNotEmpty()) {
            state.games = withContext(Dispatchers.IO) { state.library.refresh(state.games) }
        }
    }
    // Release builds look for a newer version once per app start, in the background.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        Updater.checkAtStart(context.applicationContext)?.let { state.pendingUpdate = it }
    }
    // Offered over the dashboard only, never over a notice or a screen the player is in.
    LaunchedEffect(state.pendingUpdate, state.screen) {
        val release = state.pendingUpdate ?: return@LaunchedEffect
        if (state.screen == Screen.Dashboard) {
            state.pendingUpdate = null
            state.screen = Screen.Update(release, Screen.Dashboard)
        }
    }
    MetroBackground {
        ProvideTextStyle(TextStyle(fontFamily = KiwiFont)) {
        when (val screen = state.screen) {
            Screen.Dashboard -> Dashboard(state, onLaunch)
            is Screen.Detail -> state.games.firstOrNull { it.key == screen.key }
                ?.let { GameDetail(state, it, onLaunch) }
                ?: LaunchedEffect(Unit) { state.screen = Screen.Dashboard }
            Screen.Folders -> FoldersScreen(state)
            is Screen.Browse -> BrowseScreen(state, screen.path)
            Screen.About -> AboutScreen(state)
            is Screen.GameOptions -> state.games.firstOrNull { it.key == screen.key }
                ?.let { GameOptionsScreen(state, it, screen.fromDetail) }
                ?: LaunchedEffect(Unit) { state.screen = Screen.Dashboard }
            Screen.GlobalOptions -> GlobalOptionsScreen(state)
            is Screen.EditText -> EditTextScreen(state, screen.field)
            Screen.Drivers -> DriversScreen(state)
            Screen.DriverRepos -> DriverReposScreen(state)
            is Screen.DriverReleases -> DriverReleasesScreen(state, screen.repo)
            is Screen.Achievements -> AchievementsScreen(state, screen.titleId, screen.fromDetail)
            Screen.Content -> ContentScreen(state)
            is Screen.AiConfig -> state.games.firstOrNull { it.key == screen.key }
                ?.let { game ->
                    AiConfigScreen(state, game) { state.screen = Screen.GameOptions(game.key, screen.fromDetail) }
                }
                ?: LaunchedEffect(Unit) { state.screen = Screen.Dashboard }
            is Screen.AiReverted -> AiRevertedScreen(state, screen.notices, onLaunch)
            is Screen.UnsupportedCpu -> UnsupportedCpuScreen(state, screen.back, screen.notice)
            is Screen.Update -> UpdateScreen(state, screen.release, screen.back)
            Screen.Updates -> UpdatesScreen(state)
            is Screen.PostEffects -> if (screen.key == null) {
                PostEffectsScreen(null) { state.screen = Screen.GlobalOptions }
            } else {
                state.games.firstOrNull { it.key == screen.key }
                    ?.let { game ->
                        PostEffectsScreen(game) { state.screen = Screen.GameOptions(game.key, screen.fromDetail) }
                    }
                    ?: LaunchedEffect(Unit) { state.screen = Screen.Dashboard }
            }
        }
        }
    }
}

/** Requests focus once the target is laid out; a missing target is not an error. */
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

@Composable
private fun TopBar(modifier: Modifier = Modifier) {
    val time by produceState("") {
        val format = DateFormat.getTimeInstance(DateFormat.SHORT)
        while (true) {
            value = format.format(Date())
            delay(10_000)
        }
    }
    Row(modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.kiwi_logo), null, Modifier.size(36.dp))
        Text(
            "kiwi360",
            color = Kiwi.Ink,
            fontSize = 20.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(start = 10.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(time, color = Kiwi.Ink, fontSize = 20.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
private fun Dashboard(state: AppState, onLaunch: (Game, GameFile) -> Unit) {
    val titles = listOf(
        stringResource(R.string.pivot_home),
        stringResource(R.string.pivot_games),
        stringResource(R.string.pivot_achievements),
        stringResource(R.string.pivot_settings),
    )
    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 48.dp - LanePadding, top = 16.dp, end = 32.dp, bottom = 14.dp)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_BUTTON_L1 -> {
                        state.pivot = (state.pivot - 1).coerceAtLeast(0)
                        true
                    }
                    AndroidKeyEvent.KEYCODE_BUTTON_R1 -> {
                        state.pivot = (state.pivot + 1).coerceAtMost(titles.size - 1)
                        true
                    }
                    else -> false
                }
            },
    ) {
        TopBar(Modifier.padding(start = LanePadding))
        PivotHeader(titles, state.pivot, { state.pivot = it }, Modifier.padding(start = LanePadding, top = 2.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val unit = min((maxHeight - LANE_VERTICAL * 2 - TILE_GAP) / 2, 190.dp)
            AnimatedContent(
                targetState = state.pivot,
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    (slideInHorizontally { it / 4 * direction } + fadeIn()) togetherWith fadeOut()
                },
                label = "pivot",
            ) { pivot ->
                when (pivot) {
                    0 -> HomePivot(state, unit, onLaunch)
                    1 -> GamesPivot(state, unit, onLaunch)
                    PIVOT_ACHIEVEMENTS -> AchievementsPivot(state, unit, LANE)
                    else -> SettingsPivot(state, unit)
                }
            }
        }
        val hints = when (state.pivot) {
            PIVOT_ACHIEVEMENTS -> listOf("A" to stringResource(R.string.hint_open))
            PIVOT_SETTINGS -> listOf("A" to stringResource(R.string.hint_select))
            else -> listOf(
                "A" to stringResource(R.string.hint_play),
                "X" to stringResource(R.string.hint_options),
                "Y" to stringResource(R.string.hint_details),
            )
        } + listOf("LB" to "", "RB" to stringResource(R.string.hint_sections))
        Row(Modifier.padding(start = LanePadding), verticalAlignment = Alignment.CenterVertically) {
            ButtonHints(hints)
            Spacer(Modifier.weight(1f))
            if (state.scanning) {
                Text(stringResource(R.string.scanning), color = Kiwi.InkSoft, fontSize = 15.sp)
            }
        }
    }
}

internal const val PIVOT_ACHIEVEMENTS = 2
internal const val PIVOT_SETTINGS = 3

private val TILE_GAP = 12.dp
private val LANE_VERTICAL = 10.dp
private val LANE = PaddingValues(start = LanePadding, end = 48.dp, top = LANE_VERTICAL, bottom = LANE_VERTICAL)
internal val LIST = PaddingValues(horizontal = LanePadding, vertical = 8.dp)

@Composable
private fun HomePivot(state: AppState, unit: Dp, onLaunch: (Game, GameFile) -> Unit) {
    val requester = remember { FocusRequester() }
    val recent = state.games.sortedByDescending { it.lastPlayed }
    val hero = recent.firstOrNull()
    val others = recent.drop(1).take(4)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(TILE_GAP), contentPadding = LANE) {
        item {
            if (hero == null) {
                MetroTile(
                    width = unit * 2 + TILE_GAP,
                    height = unit * 2 + TILE_GAP,
                    color = Kiwi.Green,
                    onClick = { state.screen = Screen.Folders },
                    focusRequester = requester,
                ) {
                    Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                        Text(
                            stringResource(if (state.folders.isEmpty()) R.string.add_games_title else R.string.no_games_found),
                            color = Color.White,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Light,
                        )
                        Text(stringResource(R.string.add_games_body), color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp)
                    }
                }
            } else {
                GameTile(
                    state, hero, unit * 3 + TILE_GAP * 2, unit * 2 + TILE_GAP, onLaunch,
                    focusRequester = if (state.focusKey == null || state.focusKey == "home:${hero.key}") requester else null,
                    focusKey = "home:${hero.key}",
                    subtitle = stringResource(if (hero.lastPlayed > 0) R.string.continue_playing else R.string.play),
                    large = true,
                )
            }
        }
        items(others.chunked(2)) { column ->
            Column(verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                for (game in column) {
                    val key = "home:${game.key}"
                    GameTile(
                        state, game, unit * 1.5f, unit, onLaunch,
                        focusRequester = if (state.focusKey == key) requester else null,
                        focusKey = key,
                    )
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                ActionTile(
                    stringResource(R.string.all_games),
                    quantityString(R.plurals.game_count, state.games.size),
                    unit * 1.5f, unit, Kiwi.Slate,
                    focusRequester = if (state.focusKey == "home:all") requester else null,
                    onFocused = { state.focusKey = "home:all" },
                ) { state.pivot = 1 }
                ActionTile(
                    stringResource(R.string.folders),
                    quantityString(R.plurals.folder_count, state.folders.size),
                    unit * 1.5f, unit, Kiwi.GreenDeep,
                    focusRequester = if (state.focusKey == "home:folders") requester else null,
                    onFocused = { state.focusKey = "home:folders" },
                ) { state.screen = Screen.Folders }
            }
        }
    }
    InitialFocus(requester, hero?.key)
}

@Composable
private fun GamesPivot(state: AppState, unit: Dp, onLaunch: (Game, GameFile) -> Unit) {
    val requester = remember { FocusRequester() }
    val games = state.games
    if (games.isEmpty()) {
        Text(
            stringResource(if (state.scanning) R.string.scanning else R.string.no_games_found),
            color = Kiwi.InkSoft,
            fontSize = 22.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(start = LanePadding, top = LANE_VERTICAL),
        )
        return
    }
    val focusIndex = games.indexOfFirst { "games:${it.key}" == state.focusKey }.coerceAtLeast(0)
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = (focusIndex - 2).coerceAtLeast(0))
    LazyHorizontalGrid(
        rows = GridCells.Fixed(2),
        state = gridState,
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
        verticalArrangement = Arrangement.spacedBy(TILE_GAP),
        contentPadding = LANE,
    ) {
        items(games, key = { it.key }) { game ->
            val index = games.indexOf(game)
            GameTile(
                state, game, unit * 1.3f, unit, onLaunch,
                focusRequester = if (index == focusIndex) requester else null,
                focusKey = "games:${game.key}",
            )
        }
    }
    InitialFocus(requester)
}

@Composable
private fun SettingsPivot(state: AppState, unit: Dp) {
    val requester = remember { FocusRequester() }
    fun focusOf(key: String) = if (state.focusKey == key || (state.focusKey?.startsWith("settings:") != true && key == "settings:folders")) requester else null
    LazyRow(horizontalArrangement = Arrangement.spacedBy(TILE_GAP), contentPadding = LANE) {
        item {
            ActionTile(
                stringResource(R.string.folders), quantityString(R.plurals.folder_count, state.folders.size),
                unit * 2 + TILE_GAP, unit * 2 + TILE_GAP, Kiwi.Green,
                focusRequester = focusOf("settings:folders"), onFocused = { state.focusKey = "settings:folders" },
            ) { state.screen = Screen.Folders }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                ActionTile(
                    stringResource(R.string.global_options), stringResource(R.string.global_options_desc),
                    unit * 1.5f, unit, Kiwi.Slate,
                    focusRequester = focusOf("settings:global"), onFocused = { state.focusKey = "settings:global" },
                ) { state.screen = Screen.GlobalOptions }
                ActionTile(
                    stringResource(R.string.rescan), quantityString(R.plurals.game_count, state.games.size),
                    unit * 1.5f, unit, Kiwi.Slate,
                    focusRequester = focusOf("settings:rescan"), onFocused = { state.focusKey = "settings:rescan" },
                ) { state.scanRequest++ }
            }
        }
        item {
            val driverLabel = remember(state.driverRevision) {
                state.drivers.find(state.drivers.defaultDriverId)?.let { driverLabel(it.name, it.version) }
            } ?: stringResource(R.string.driver_system)
            ActionTile(
                stringResource(R.string.gpu_driver), driverLabel, unit * 1.5f, unit * 2 + TILE_GAP, Kiwi.GreenDeep,
                focusRequester = focusOf("settings:driver"), onFocused = { state.focusKey = "settings:driver" },
            ) { state.screen = Screen.Drivers }
        }
        item {
            val context = LocalContext.current
            val total by produceState<String?>(null, state.resumeTick) {
                value = withContext(Dispatchers.IO) {
                    val items = InstalledContent(context).list()
                    android.text.format.Formatter.formatShortFileSize(context, items.sumOf { it.sizeBytes })
                }
            }
            ActionTile(
                stringResource(R.string.content_title),
                total?.let { stringResource(R.string.content_tile_size, it) } ?: stringResource(R.string.content_tile_desc),
                unit * 1.5f, unit * 2 + TILE_GAP, Kiwi.Slate,
                focusRequester = focusOf("settings:content"), onFocused = { state.focusKey = "settings:content" },
            ) { state.screen = Screen.Content }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                ActionTile(
                    stringResource(R.string.settings_gamertag), state.gamertag, unit * 1.5f, unit, Kiwi.Slate,
                    focusRequester = focusOf("settings:gamertag"), onFocused = { state.focusKey = "settings:gamertag" },
                ) { state.screen = Screen.EditText("gamertag") }
                ActionTile(
                    stringResource(R.string.settings_console), state.consoleName, unit * 1.5f, unit, Kiwi.Slate,
                    focusRequester = focusOf("settings:console"), onFocused = { state.focusKey = "settings:console" },
                ) { state.screen = Screen.EditText("console") }
            }
        }
        item {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            var preparing by remember { mutableStateOf(false) }
            val chooserTitle = stringResource(R.string.share_logs_chooser)
            val failed = stringResource(R.string.share_logs_failed)
            ActionTile(
                stringResource(R.string.share_logs),
                stringResource(if (preparing) R.string.share_logs_preparing else R.string.share_logs_desc),
                unit * 1.5f, unit * 2 + TILE_GAP, Kiwi.Slate,
                focusRequester = focusOf("settings:logs"), onFocused = { state.focusKey = "settings:logs" },
            ) {
                if (!preparing) {
                    preparing = true
                    scope.launch {
                        val zip = withContext(Dispatchers.IO) { KiwiLogs.buildPackage(context) }
                        preparing = false
                        if (zip != null) {
                            context.startActivity(KiwiLogs.shareIntent(context, zip, chooserTitle))
                        } else {
                            Toast.makeText(context, failed, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        item {
            ActionTile(
                stringResource(R.string.updates_title), stringResource(R.string.updates_tile_desc, Updater.installedVersion),
                unit * 1.5f, unit * 2 + TILE_GAP, Kiwi.Slate,
                focusRequester = focusOf("settings:updates"), onFocused = { state.focusKey = "settings:updates" },
            ) { state.screen = Screen.Updates }
        }
        item {
            ActionTile(
                stringResource(R.string.about), stringResource(R.string.about_tagline),
                unit * 1.5f, unit * 2 + TILE_GAP, Kiwi.GreenDeep,
                focusRequester = focusOf("settings:about"), onFocused = { state.focusKey = "settings:about" },
            ) { state.screen = Screen.About }
        }
    }
    InitialFocus(requester)
}

@Composable
private fun ActionTile(
    title: String,
    subtitle: String,
    width: Dp,
    height: Dp,
    color: Color,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    MetroTile(width, height, color, onClick, focusRequester = focusRequester, onFocused = onFocused) {
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text(title, color = Color.White, fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun GameTile(
    state: AppState,
    game: Game,
    width: Dp,
    height: Dp,
    onLaunch: (Game, GameFile) -> Unit,
    focusRequester: FocusRequester?,
    focusKey: String,
    subtitle: String? = null,
    large: Boolean = false,
) {
    val cover = rememberImage(game.coverPath, if (large) 1024 else 512)
    val icon = rememberImage(if (cover == null) game.iconPath else null, 256)
    val color = remember(icon) { tileColorFor(icon, tileColorForKey(game.key)) }
    MetroTile(
        width, height, color,
        onClick = { onLaunch(game, game.defaultFile(state.library.lastPlayedPath(game))) },
        onSecondary = { state.screen = Screen.Detail(game.key) },
        focusRequester = focusRequester,
        onFocused = { state.focusKey = focusKey },
        onOptions = { state.screen = Screen.GameOptions(game.key, fromDetail = false) },
    ) {
        when {
            cover != null -> Image(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            icon != null -> IconArt(icon, height * 0.5f, Modifier.padding(bottom = 22.dp))
            else -> Text(
                game.name.take(1),
                color = Color.White.copy(alpha = 0.35f),
                fontSize = (height.value * 0.45f).sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (game.files.size > 1) {
            Text(
                quantityString(R.plurals.discs, game.files.size),
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopEnd).background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (subtitle != null) {
                Text(subtitle, color = Kiwi.GreenPale, fontSize = 15.sp)
            }
            Text(
                game.name,
                color = Color.White,
                fontSize = if (large) 28.sp else 16.sp,
                fontWeight = if (large) FontWeight.Light else FontWeight.Normal,
                maxLines = if (large) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun SubScreen(
    title: String,
    hints: List<Pair<String, String>>,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(start = 48.dp - LanePadding, top = 16.dp, end = 32.dp, bottom = 14.dp)) {
        TopBar(Modifier.padding(start = LanePadding))
        Text(
            title, color = Kiwi.Ink, fontSize = 40.sp, fontWeight = FontWeight.Light, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = LanePadding),
        )
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 2.dp, bottom = 2.dp)) { content() }
        ButtonHints(hints + ("B" to stringResource(R.string.hint_back)), Modifier.padding(start = LanePadding))
    }
}

@Composable
private fun GameDetail(state: AppState, game: Game, onLaunch: (Game, GameFile) -> Unit) {
    val requester = remember { FocusRequester() }
    val cover = rememberImage(game.coverPath ?: game.iconPath, 1024)
    SubScreen(game.name, listOf("A" to stringResource(R.string.hint_play)), { state.screen = Screen.Dashboard }) {
        Row(Modifier.fillMaxSize().padding(start = LanePadding, top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BoxWithConstraints(Modifier.fillMaxHeight()) {
                val side = maxHeight
                Box(Modifier.size(side).background(if (game.coverPath == null) tileColorFor(cover, tileColorForKey(game.key)) else Kiwi.Slate)) {
                    if (cover != null) {
                        if (game.coverPath != null) {
                            Image(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else {
                            IconArt(cover, side * 0.5f)
                        }
                    }
                }
            }
            Column(Modifier.fillMaxHeight().padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val version = game.files.first().version
                Text(
                    stringResource(R.string.title_id, game.titleIdHex) + "  ·  " + stringResource(R.string.version, version),
                    color = Kiwi.InkSoft, fontSize = 16.sp,
                )
                Text(
                    if (game.lastPlayed > 0) stringResource(
                        R.string.last_played,
                        DateUtils.getRelativeTimeSpanString(game.lastPlayed, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                    ) else stringResource(R.string.never_played),
                    color = Kiwi.InkSoft, fontSize = 16.sp,
                )
                val context = LocalContext.current
                val playTime = remember(game.key, state.resumeTick) { PlayTime.forGame(context, game.key).read() }
                playTime?.let { playTimeText(it.seconds) }?.let {
                    Text(stringResource(R.string.play_time, it), color = Kiwi.InkSoft, fontSize = 16.sp)
                }
                if (!game.hasRealName) {
                    Text(stringResource(R.string.name_after_boot), color = Kiwi.InkSoft, fontSize = 14.sp)
                }
                val achievements by produceState<AchievementSummary?>(null, game.key, state.resumeTick) {
                    value = withContext(Dispatchers.IO) {
                        AchievementStore(context).summaries().firstOrNull { it.titleId == game.titleId }
                    }
                }
                Spacer(Modifier.height(8.dp))
                val lastPath = state.library.lastPlayedPath(game)
                val default = game.defaultFile(lastPath)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = LIST) {
                    items(game.files) { file ->
                        MetroTile(
                            360.dp, 58.dp, if (file == default) Kiwi.Green else Kiwi.Slate,
                            onClick = { onLaunch(game, file) },
                            focusRequester = if (file == default) requester else null,
                        ) {
                            Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp)) {
                                Text(
                                    if (game.files.size > 1) stringResource(R.string.play_disc, file.discNumber) else stringResource(R.string.play),
                                    color = Color.White, fontSize = 18.sp,
                                )
                                Text(File(file.path).name, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    achievements?.let { summary ->
                        item {
                            MetroTile(
                                360.dp, 48.dp, Kiwi.Slate,
                                onClick = { state.screen = Screen.Achievements(game.titleId, fromDetail = true) },
                            ) {
                                Row(
                                    Modifier.align(Alignment.CenterStart).fillMaxWidth().padding(horizontal = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(stringResource(R.string.achievements), color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                                    Text(achievementProgress(summary), color = Kiwi.GreenPale, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                    item {
                        MetroTile(360.dp, 48.dp, Kiwi.Slate, onClick = { state.screen = Screen.GameOptions(game.key) }) {
                            Text(
                                stringResource(R.string.game_options), color = Color.White, fontSize = 16.sp,
                                modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    InitialFocus(requester)
}

@Composable
private fun FoldersScreen(state: AppState) {
    val requester = remember { FocusRequester() }
    val suggested = remember(state.folders) {
        state.library.suggestedFolders().map { it.absolutePath }.filter { it !in state.folders }
    }
    SubScreen(stringResource(R.string.folders), listOf("A" to stringResource(R.string.hint_select)), { state.screen = Screen.Dashboard }) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = LIST) {
            item {
                MetroTile(520.dp, 56.dp, Kiwi.Green, { state.screen = Screen.Browse(null) }, focusRequester = requester) {
                    Text("+  " + stringResource(R.string.add_folder), color = Color.White, fontSize = 18.sp, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp))
                }
            }
            items(state.folders.sorted()) { folder ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.width(520.dp).height(56.dp).background(Color.White.copy(alpha = 0.7f))) {
                        Text(folder, color = Kiwi.Ink, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp))
                    }
                    MetroTile(140.dp, 56.dp, Kiwi.ButtonB, { state.updateFolders(state.folders - folder) }) {
                        Text(stringResource(R.string.remove), color = Color.White, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
            items(suggested) { folder ->
                MetroTile(670.dp, 56.dp, Kiwi.Slate, { state.updateFolders(state.folders + folder) }) {
                    Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp)) {
                        Text(stringResource(R.string.suggested) + " · " + stringResource(R.string.add_folder), color = Kiwi.GreenPale, fontSize = 13.sp)
                        Text(folder, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    InitialFocus(requester)
}

@Composable
private fun BrowseScreen(state: AppState, path: String?) {
    val requester = remember(path) { FocusRequester() }
    val dir = path?.let { File(it) }
    val entries = remember(path) {
        if (dir == null) state.library.storageRoots()
        else (dir.listFiles() ?: emptyArray()).filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }
    }
    val up: () -> Unit = {
        val parent = dir?.parentFile
        val atRoot = dir == null || state.library.storageRoots().any { it.absolutePath == dir.absolutePath }
        state.screen = if (dir == null) Screen.Folders else if (atRoot || parent == null) Screen.Browse(null) else Screen.Browse(parent.absolutePath)
    }
    SubScreen(path ?: stringResource(R.string.storage), listOf("A" to stringResource(R.string.hint_open)), up) {
        val listState = rememberLazyListState()
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = LIST) {
            if (dir != null) {
                item {
                    MetroTile(520.dp, 52.dp, Kiwi.Green, {
                        state.updateFolders(state.folders + dir.absolutePath)
                        state.screen = Screen.Folders
                    }, focusRequester = requester) {
                        Text(stringResource(R.string.use_this_folder), color = Color.White, fontSize = 18.sp, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp))
                    }
                }
            }
            items(entries, key = { it.absolutePath }) { entry ->
                MetroTile(
                    520.dp, 48.dp, Kiwi.Slate, { state.screen = Screen.Browse(entry.absolutePath) },
                    focusRequester = if (dir == null && entry == entries.first()) requester else null,
                ) {
                    Text(
                        if (dir == null) entry.absolutePath else entry.name,
                        color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp),
                    )
                }
            }
        }
    }
    InitialFocus(requester, path)
}

@Composable
private fun AboutScreen(state: AppState) {
    val context = LocalContext.current
    val requester = remember { FocusRequester() }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Kiwi360's own license, then every third-party notice (~200 KB), in blocks
    // a lazy list can lay out as they scroll into view.
    val licenseBlocks = remember {
        try {
            LICENSE_FILES.flatMap { licenseBlocks(context.assets.open(it).bufferedReader().readText()) }
        } catch (_: Exception) {
            emptyList()
        }
    }
    val version = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }
    SubScreen(stringResource(R.string.about), emptyList(), { state.screen = Screen.Dashboard }) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(start = LanePadding)
                .focusRequester(requester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val step = when (event.nativeKeyEvent.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> 120f
                        AndroidKeyEvent.KEYCODE_DPAD_UP -> -120f
                        else -> return@onPreviewKeyEvent false
                    }
                    scope.launch { list.animateScrollBy(step) }
                    true
                }
                .focusable(),
            state = list,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.kiwi_logo), null, Modifier.size(72.dp))
                    Column(Modifier.padding(start = 16.dp)) {
                        Text("Kiwi360 $version", color = Kiwi.Ink, fontSize = 26.sp, fontWeight = FontWeight.Light)
                        Text(stringResource(R.string.about_tagline), color = Kiwi.InkSoft, fontSize = 16.sp)
                    }
                }
            }
            item { Text(stringResource(R.string.about_license), color = Kiwi.Ink, fontSize = 15.sp) }
            item { Text(stringResource(R.string.about_based_on), color = Kiwi.Ink, fontSize = 15.sp) }
            item { Text(stringResource(R.string.about_trademark), color = Kiwi.InkSoft, fontSize = 14.sp) }
            items(licenseBlocks) { block ->
                Text(
                    block, color = Kiwi.Ink, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
    InitialFocus(requester)
}

private val LICENSE_FILES = listOf("licenses/KIWI360_LICENSE.txt", "licenses/THIRD_PARTY_NOTICES.txt")

/** Splits a license text at blank lines into blocks of up to about 2 KB. */
private fun licenseBlocks(text: String): List<String> {
    val blocks = mutableListOf<String>()
    val current = StringBuilder()
    for (paragraph in text.replace("\r\n", "\n").split("\n\n")) {
        if (current.isNotEmpty() && current.length + paragraph.length > 2048) {
            blocks += current.toString()
            current.clear()
        }
        if (current.isNotEmpty()) current.append("\n\n")
        current.append(paragraph)
    }
    if (current.isNotEmpty()) blocks += current.toString()
    return blocks
}

@StringRes
internal fun optionTitle(option: GameOption): Int = when (option) {
    GameOption.RESOLUTION -> R.string.opt_resolution
    GameOption.HUD -> R.string.hud
    GameOption.RTZ -> R.string.opt_rtz
    GameOption.LICENSE -> R.string.opt_license
    GameOption.LANGUAGE -> R.string.opt_language
}

@StringRes
internal fun optionDetail(option: GameOption): Int? = when (option) {
    GameOption.RTZ -> R.string.opt_rtz_detail
    GameOption.LANGUAGE -> R.string.opt_language_detail
    else -> null
}

/** [global]: the value for every game, where "" is the emulator's (or the device's) own. */
@Composable
internal fun optionLabel(option: GameOption, value: String, global: Boolean = false): String = when {
    value.isEmpty() && global && option == GameOption.LANGUAGE ->
        stringResource(R.string.opt_language_auto, ConsoleLanguage.name(ConsoleLanguage.fromDevice()))
    value.isEmpty() -> stringResource(R.string.opt_default)
    option == GameOption.LANGUAGE -> ConsoleLanguage.name(value.toIntOrNull() ?: 0)
    option == GameOption.RESOLUTION -> "${value}x"
    option == GameOption.HUD -> stringResource(
        when (value) {
            "full" -> R.string.hud_full
            "off" -> R.string.hud_off
            else -> R.string.hud_fps
        },
    )
    value == "on" -> stringResource(R.string.opt_on)
    else -> stringResource(R.string.opt_off)
}

/** Export the game's saves to a ZIP the user places, or restore them from one. */
@Composable
private fun SaveTiles(game: Game) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun toast(message: Int) = Toast.makeText(context, context.getString(message), Toast.LENGTH_LONG).show()
    val exportTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { SaveStore(context).export(game.titleId, it) } == true
                } catch (_: Exception) {
                    false
                }
            }
            toast(if (ok) R.string.saves_exported else R.string.saves_export_failed)
        }
    }
    val importFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        context.startActivity(
            Intent(context, ImportSavesActivity::class.java).setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MetroTile(
            620.dp, 48.dp, Kiwi.Slate,
            onClick = {
                scope.launch {
                    val has = withContext(Dispatchers.IO) { SaveStore(context).info(game.titleId) != null }
                    if (has) exportTo.launch("${game.name.replace(Regex("[^A-Za-z0-9 ._-]"), "")} ${game.titleIdHex} saves.zip")
                    else toast(R.string.saves_none)
                }
            },
        ) {
            Text(
                stringResource(R.string.saves_export), color = Color.White, fontSize = 16.sp,
                modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp),
            )
        }
        MetroTile(620.dp, 48.dp, Kiwi.Slate, onClick = { importFrom.launch(arrayOf("application/zip", "application/octet-stream")) }) {
            Text(
                stringResource(R.string.saves_import), color = Color.White, fontSize = 16.sp,
                modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp),
            )
        }
    }
}

@Composable
private fun GameOptionsScreen(state: AppState, game: Game, fromDetail: Boolean) {
    val context = LocalContext.current
    val requester = remember { FocusRequester() }
    // Re-read after each change.
    var revision by remember { mutableIntStateOf(0) }
    val back = {
        val caller = state.returnToCaller
        state.returnToCaller = null
        if (caller != null) {
            state.screen = Screen.Dashboard
            caller()
        } else {
            state.screen = if (fromDetail) Screen.Detail(game.key) else Screen.Dashboard
        }
    }
    SubScreen(game.name + " \u00b7 " + stringResource(R.string.game_options), listOf("A" to stringResource(R.string.hint_select)), back) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = LIST) {
            items(GameOption.entries) { option ->
                val value = remember(revision) { state.library.gameOption(game.key, option) }
                MetroTile(
                    620.dp, 56.dp, if (value.isEmpty()) Kiwi.Slate else Kiwi.GreenDeep,
                    onClick = {
                        val values = option.values
                        state.library.setGameOption(game.key, option, values[(values.indexOf(value) + 1) % values.size])
                        revision++
                    },
                    focusRequester = if (option == GameOption.entries.first()) requester else null,
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(optionTitle(option)), color = Color.White, fontSize = 17.sp)
                            optionDetail(option)?.let {
                                Text(stringResource(it), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                            }
                        }
                        Text(optionLabel(option, value), color = Kiwi.GreenPale, fontSize = 17.sp)
                    }
                }
            }
            item {
                val current = remember(revision, state.driverRevision) { state.drivers.gameDriverId(game.key) }
                val choices = remember(state.driverRevision) {
                    listOf("", GpuDriverStore.SYSTEM) + state.drivers.installed().map { it.id }
                }
                val label = when (current) {
                    "" -> stringResource(R.string.opt_default)
                    GpuDriverStore.SYSTEM -> stringResource(R.string.driver_system)
                    else -> state.drivers.find(current)?.let { driverLabel(it.name, it.version) } ?: current
                }
                MetroTile(
                    620.dp, 56.dp, if (current.isEmpty()) Kiwi.Slate else Kiwi.GreenDeep,
                    onClick = {
                        state.drivers.setGameDriverId(game.key, choices[(choices.indexOf(current) + 1) % choices.size])
                        revision++
                    },
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.gpu_driver), color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        Text(label, color = Kiwi.GreenPale, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item {
                // Image effects: the game's own chain, else the one for every game.
                val own = remember(revision) { PostEffectsStore.game(context, game.key) }
                val chain = own ?: remember(revision) { PostEffectsStore.global(context) }
                val summary = PostChain.summary(context, chain)
                MetroTile(
                    620.dp, 56.dp, if (own == null) Kiwi.Slate else Kiwi.GreenDeep,
                    onClick = { state.screen = Screen.PostEffects(game.key, fromDetail) },
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.pp_title), color = Color.White, fontSize = 17.sp)
                            Text(
                                if (own == null) stringResource(R.string.pp_follows_global, summary) else summary,
                                color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(PostPreset.label(context, chain), color = Kiwi.GreenPale, fontSize = 17.sp, maxLines = 1)
                    }
                }
            }
            if (AiConfigStore.supports(game)) {
                item {
                    MetroTile(
                        620.dp, 56.dp, Kiwi.Green,
                        onClick = { state.screen = Screen.AiConfig(game.key, fromDetail) },
                    ) {
                        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.Center) {
                            Text(stringResource(R.string.ai_config_title), color = Color.White, fontSize = 17.sp)
                            Text(
                                stringResource(R.string.ai_config_tile_desc), color = Color.White.copy(alpha = 0.8f),
                                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                val active = AiConfigStore.state(context, game.key)
                    ?.takeIf { revision >= 0 && it.state != AiConfigStore.REVERTED }
                if (active != null) {
                    item {
                        MetroTile(
                            620.dp, 56.dp, Kiwi.Slate,
                            onClick = {
                                AiConfigStore.undo(context, state.library, game.key)
                                Toast.makeText(context, R.string.ai_config_undone, Toast.LENGTH_SHORT).show()
                                revision++
                            },
                        ) {
                            Column(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.Center) {
                                Text(stringResource(R.string.ai_config_undo), color = Color.White, fontSize = 17.sp)
                                Text(
                                    stringResource(R.string.ai_config_undo_desc, active.count),
                                    color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
            item { SaveTiles(game) }
            item {
                MetroTile(
                    620.dp, 48.dp, Kiwi.Slate,
                    onClick = {
                        GameOption.entries.forEach { state.library.setGameOption(game.key, it, "") }
                        state.drivers.setGameDriverId(game.key, "")
                        // Nothing of an AI change is left to undo.
                        AiConfigStore.forget(context, game.key)
                        // And what the in-game menu kept for it.
                        dev.kiwi360.frontend.overlay.GameLiveSettings.forGame(context, game.key).clear()
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
private fun EditTextScreen(state: AppState, field: String) {
    val isGamertag = field == "gamertag"
    var error by remember { mutableStateOf(false) }
    val back = { state.screen = Screen.Dashboard }
    SubScreen(
        stringResource(if (isGamertag) R.string.settings_gamertag else R.string.settings_console),
        // Its own B goes last, from SubScreen.
        keyboardHints().dropLast(1), back,
    ) {
        Column(Modifier.padding(start = LanePadding)) {
            Text(
                stringResource(
                    when {
                        error && isGamertag -> R.string.overlay_gamertag_invalid
                        isGamertag -> R.string.overlay_gamertag_rules
                        else -> R.string.console_name_rules
                    },
                ),
                color = if (error) Kiwi.ButtonB else Kiwi.InkSoft, fontSize = 14.sp,
            )
            Spacer(Modifier.height(6.dp))
            OnScreenKeyboard(
                initial = if (isGamertag) state.gamertag else state.consoleName,
                maxLength = if (isGamertag) 15 else 23,
                onAccept = { text ->
                    val value = text.trim()
                    if (isGamertag) {
                        if (isValidGamertag(value)) {
                            state.library.gamertag = value
                            state.gamertag = value
                            back()
                        } else {
                            error = true
                        }
                    } else if (value.isNotEmpty()) {
                        state.library.consoleName = value
                        state.consoleName = value
                        back()
                    }
                },
            )
        }
    }
}

/** "1 game" / "3 games": a plurals resource with the count as its only argument. */
@Composable
internal fun quantityString(id: Int, count: Int): String =
    LocalContext.current.resources.getQuantityString(id, count, count)
