// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.aiconfig

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import dev.kiwi360.frontend.library.Game
import dev.kiwi360.frontend.library.GameOption
import dev.kiwi360.frontend.library.Library
import dev.kiwi360.frontend.overlay.GameLiveSettings
import org.json.JSONObject
import java.io.File

/**
 * Applies a recommended configuration to a game's own settings and keeps what is needed to take
 * it back. Where a key lives:
 * - "app" keys are the game's options of the settings screens (Library, library process);
 * - hidden cvars go to the game's kept launch settings (GameLiveSettings, the same file the
 *   in-game menu keeps its changes in), as "cvar.<name>" with the cvar's own type.
 *
 * Files live in files/ai_config/, named after the game key (its title id):
 * - `<key>.backup.json`: before the first recommendation, the game's own value of every key the
 *   recommendations may change ({"options": {...}, "cvars": {...}}; null = not set for the game).
 *   Later recommendations keep that first copy, so undo always returns to the settings before
 *   any AI change.
 * - `<key>.json`: the state. A configuration is unconfirmed (applied) until a launch on it has
 *   shown frames for 90 s, or the player left a game that had shown frames. While unconfirmed:
 *   - the game process (EmulatorActivity, ":emu") marks it "launching" with its pid when the game
 *     starts, watches the presented frames, and at 90 s confirms it or, without a single frame,
 *     marks it failed ("no_boot"); the crash report marks it failed too;
 *   - the library process restores the copy for a failed state, and for a "launching" one whose
 *     process is gone (the game crashed, or the whole app died: then on the next start), reports
 *     the outcome to the server and offers the player to play again without them.
 */
object AiConfigStore {
    private const val TAG = "Kiwi360-AiConfig"
    private const val CONFIRM_AFTER_MS = 90_000L
    private const val POLL_MS = 2_000L

    const val APPLIED = "applied"
    const val LAUNCHING = "launching"
    const val CONFIRMED = "confirmed"
    const val FAILED = "failed"
    const val REVERTED = "reverted"

    private val keyFormat = Regex("^[0-9A-F]{8}$")

    /** Recommendations need the game's title id; the library keys a game with one by it. */
    fun supports(game: Game) = game.titleId != 0L && keyFormat.matches(game.key)

    class State(
        val answerId: String,
        val appliedAt: Long,
        val state: String,
        val titleName: String,
        val pid: Int = 0,
        val presented: Boolean = false,
        /** crashed or no_boot, for a failed or reverted state. */
        val outcome: String = "",
        /** How many settings the recommendation changed. */
        val count: Int = 0,
    ) {
        fun copy(
            state: String = this.state,
            pid: Int = this.pid,
            presented: Boolean = this.presented,
            outcome: String = this.outcome,
        ) = State(answerId, appliedAt, state, titleName, pid, presented, outcome, count)

        fun toJson(): String = JSONObject()
            .put("answer_id", answerId).put("applied_at", appliedAt).put("state", state)
            .put("title_name", titleName).put("pid", pid).put("presented", presented).put("outcome", outcome)
            .put("count", count)
            .toString()

        companion object {
            fun fromJson(text: String): State {
                val o = JSONObject(text)
                return State(
                    o.optString("answer_id"), o.optLong("applied_at"), o.getString("state"),
                    o.optString("title_name"), o.optInt("pid"), o.optBoolean("presented"), o.optString("outcome"),
                    o.optInt("count"),
                )
            }
        }
    }

    /** A game whose recommended settings were undone automatically; shown to the player once. */
    class Notice(val gameKey: String, val titleName: String, val outcome: String)

    private fun dir(context: Context) = File(context.filesDir, "ai_config").apply { mkdirs() }
    private fun stateFile(context: Context, key: String) = File(dir(context), "$key.json")
    private fun backupFile(context: Context, key: String) = File(dir(context), "$key.backup.json")

    private fun read(file: File): State? = runCatching { State.fromJson(file.readText()) }.getOrNull()

    private fun write(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    fun state(context: Context, key: String): State? = read(stateFile(context, key))

    /** True while an AI change can be undone for [key]. */
    fun hasActive(context: Context, key: String): Boolean =
        state(context, key)?.let { it.state != REVERTED } ?: false

    // ---- Where each key lives (library process) ----

    private fun appOption(key: String): GameOption? = when (key) {
        "draw_resolution_scale" -> GameOption.RESOLUTION
        "kiwi_rtz_software_emulation" -> GameOption.RTZ
        else -> null
    }

    private fun appValue(option: GameOption, text: String): Long? = when (option) {
        GameOption.RTZ -> when (text) {
            "on" -> 1L
            "off" -> 0L
            else -> null
        }
        else -> text.toLongOrNull()
    }

    private fun appText(option: GameOption, value: Long): String = when (option) {
        GameOption.RTZ -> if (value != 0L) "on" else "off"
        else -> value.toString()
    }

    private fun cvarKey(key: String) = "cvar.$key"

    /** The value [game] runs [entry] with: its own, else the one chosen for every game, else the default. */
    fun effectiveValue(context: Context, library: Library, gameKey: String, entry: AiSchema.Entry): Long {
        appOption(entry.key)?.let { option ->
            return appValue(option, library.effectiveOption(gameKey, option)) ?: entry.default
        }
        val name = cvarKey(entry.key)
        return entry.fromCvar(GameLiveSettings.forGame(context, gameKey).all().opt(name))
            ?: entry.fromCvar(GameLiveSettings.global(context).all().opt(name))
            ?: entry.default
    }

    /** Writes [values] (key -> value, already checked against [schema]) as the game's own settings. */
    @Synchronized
    fun apply(
        context: Context,
        library: Library,
        schema: AiSchema,
        game: Game,
        answerId: String,
        values: Map<String, Long>,
    ) {
        val backup = backupFile(context, game.key)
        val previous = state(context, game.key)
        val live = GameLiveSettings.forGame(context, game.key)
        if (!backup.exists() || previous == null || previous.state == REVERTED) {
            val options = JSONObject()
            val cvars = JSONObject()
            val kept = live.all()
            schema.settings.forEach { entry ->
                val option = appOption(entry.key)
                if (option != null) {
                    options.put(option.key, library.gameOption(game.key, option))
                } else {
                    cvars.put(cvarKey(entry.key), kept.opt(cvarKey(entry.key)) ?: JSONObject.NULL)
                }
            }
            write(backup, JSONObject().put("options", options).put("cvars", cvars).toString())
        }
        values.forEach { (key, value) ->
            val entry = schema.entry(key) ?: return@forEach
            val option = appOption(key)
            if (option != null) {
                library.setGameOption(game.key, option, appText(option, value))
            } else {
                live.put(cvarKey(key), entry.cvarValue(value))
            }
        }
        val count = values.size + (previous?.takeIf { it.state != REVERTED }?.count ?: 0)
        write(
            stateFile(context, game.key),
            State(answerId, System.currentTimeMillis(), APPLIED, game.name, count = count).toJson(),
        )
        Log.i(TAG, "Applied ${values.size} settings to ${game.key}")
    }

    /** Puts [key]'s settings back as they were before the AI changes. Library process. */
    @Synchronized
    fun undo(context: Context, library: Library, key: String) {
        val answerId = restore(context, library, key)
        if (answerId != null) report(answerId, AiConfigClient.Outcome.REVERTED)
    }

    /** Forgets the AI change without touching the settings (the player reset the game's settings). */
    @Synchronized
    fun forget(context: Context, key: String) {
        backupFile(context, key).delete()
        stateFile(context, key).delete()
    }

    /** Restores the copy and drops the AI files. Returns the undone answer id. */
    private fun restore(context: Context, library: Library, key: String): String? {
        val state = state(context, key)
        val backup = backupFile(context, key)
        if (backup.exists()) {
            runCatching {
                val copy = JSONObject(backup.readText())
                // The copy's own keys, not the current schema's: an update may have dropped a key.
                copy.optJSONObject("options")?.let { options ->
                    options.keys().forEach { name ->
                        val option = GameOption.entries.firstOrNull { it.key == name } ?: return@forEach
                        library.setGameOption(key, option, options.optString(name))
                    }
                }
                val live = GameLiveSettings.forGame(context, key)
                copy.optJSONObject("cvars")?.let { cvars ->
                    cvars.keys().forEach { name ->
                        if (cvars.isNull(name)) live.remove(name) else live.put(name, cvars.get(name))
                    }
                }
            }.onFailure { Log.w(TAG, "Backup of $key unreadable: ${it.message}") }
            backup.delete()
        }
        stateFile(context, key).delete()
        Log.i(TAG, "Restored the settings of $key from before the AI change")
        return state?.answerId
    }

    private fun report(answerId: String, outcome: AiConfigClient.Outcome) {
        Thread({ AiConfigClient.feedback(answerId, outcome) }, "AiConfigFeedback").start()
    }

    private fun processAlive(context: Context, pid: Int): Boolean {
        if (pid <= 0) return false
        val manager = context.getSystemService(ActivityManager::class.java) ?: return true
        return manager.runningAppProcesses?.any { it.pid == pid } ?: true
    }

    /**
     * Library process, when it starts or comes back and before a game launches: undoes the
     * recommendations a launch failed on (crash report, crash, killed, or no frame in 90 s),
     * reports them and returns every notice the player has not seen yet.
     */
    @Synchronized
    fun recover(context: Context, library: Library): List<Notice> {
        val notices = mutableListOf<Notice>()
        val files = dir(context).listFiles { f -> f.name.endsWith(".json") && !f.name.endsWith(".backup.json") }
            ?: return notices
        for (file in files) {
            val key = file.name.removeSuffix(".json")
            val state = read(file) ?: continue
            val outcome = when {
                state.state == FAILED -> state.outcome.ifEmpty { AiConfigClient.Outcome.CRASHED.wire }
                state.state == LAUNCHING && !processAlive(context, state.pid) ->
                    if (state.presented) AiConfigClient.Outcome.CRASHED.wire else AiConfigClient.Outcome.NO_BOOT.wire
                else -> null
            }
            if (outcome != null) {
                Log.w(TAG, "$key failed with the recommended settings ($outcome); undoing them")
                restore(context, library, key)
                write(file, state.copy(state = REVERTED, pid = 0, outcome = outcome).toJson())
                report(
                    state.answerId,
                    if (outcome == AiConfigClient.Outcome.NO_BOOT.wire) AiConfigClient.Outcome.NO_BOOT
                    else AiConfigClient.Outcome.CRASHED,
                )
            }
            val now = if (outcome != null) read(file) else state
            if (now?.state == REVERTED) notices += Notice(key, now.titleName, now.outcome)
        }
        return notices
    }

    /** The player has seen these notices. */
    @Synchronized
    fun clearNotices(context: Context, keys: Collection<String>) {
        keys.forEach { key ->
            val file = stateFile(context, key)
            if (read(file)?.state == REVERTED) file.delete()
        }
    }

    // ---- Game process ----

    /** Frames the game has presented so far. */
    fun interface FrameCounter {
        fun frames(): Long
    }

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var watched: String? = null
    private var startedAt = 0L
    private var counter = FrameCounter { 0L }

    private fun presentedSoFar(state: State) = state.presented || runCatching { counter.frames() }.getOrDefault(0L) > 0

    /** Game process, as the game starts: arms the guard when it starts on an unconfirmed recommendation. */
    @JvmStatic
    fun onGameStarted(context: Context, gameKey: String?, frames: FrameCounter) {
        if (gameKey == null || !keyFormat.matches(gameKey)) return
        val file = stateFile(context, gameKey)
        val state = read(file) ?: return
        // A "launching" state left by an earlier process the library has not looked at yet is unconfirmed too.
        if (state.state != APPLIED && state.state != LAUNCHING) return
        write(file, state.copy(state = LAUNCHING, pid = Process.myPid(), presented = false).toJson())
        watched = gameKey
        startedAt = SystemClock.elapsedRealtime()
        counter = frames
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (watched != gameKey) return
                val now = read(file) ?: return
                if (now.state != LAUNCHING || now.pid != Process.myPid()) return
                val presented = presentedSoFar(now)
                if (SystemClock.elapsedRealtime() - startedAt >= CONFIRM_AFTER_MS) {
                    watched = null
                    if (presented) {
                        write(file, now.copy(state = CONFIRMED, pid = 0, presented = true).toJson())
                        Log.i(TAG, "$gameKey ran fine with the recommended settings")
                    } else {
                        write(file, now.copy(state = FAILED, pid = 0, outcome = AiConfigClient.Outcome.NO_BOOT.wire).toJson())
                        Log.w(TAG, "$gameKey showed no frame in 90 s with the recommended settings")
                    }
                    return
                }
                if (presented && !now.presented) write(file, now.copy(presented = true).toJson())
                handler.postDelayed(this, POLL_MS)
            }
        }, POLL_MS)
        Log.i(TAG, "$gameKey starts with unconfirmed recommended settings; watching it")
    }

    /** Game process: the emulator showed its crash report. The settings failed, whatever happens next. */
    @JvmStatic
    fun onFatal(context: Context) {
        val gameKey = watched ?: return
        watched = null
        handler.removeCallbacksAndMessages(null)
        val file = stateFile(context, gameKey)
        val state = read(file) ?: return
        if (state.state != LAUNCHING || state.pid != Process.myPid()) return
        val presented = presentedSoFar(state)
        write(
            file,
            state.copy(
                state = FAILED, pid = 0, presented = presented,
                outcome = if (presented) AiConfigClient.Outcome.CRASHED.wire else AiConfigClient.Outcome.NO_BOOT.wire,
            ).toJson(),
        )
        Log.w(TAG, "$gameKey crashed with the recommended settings")
    }

    /**
     * Game process, when the player leaves the game. A normal exit after the game showed frames
     * confirms the settings; an exit before any frame is no verdict.
     */
    @JvmStatic
    fun onGameExit(context: Context) {
        val gameKey = watched ?: return
        watched = null
        handler.removeCallbacksAndMessages(null)
        val file = stateFile(context, gameKey)
        val state = read(file) ?: return
        if (state.state != LAUNCHING || state.pid != Process.myPid()) return
        val next = if (presentedSoFar(state)) {
            state.copy(state = CONFIRMED, pid = 0, presented = true)
        } else {
            state.copy(state = APPLIED, pid = 0)
        }
        write(file, next.toJson())
    }
}
