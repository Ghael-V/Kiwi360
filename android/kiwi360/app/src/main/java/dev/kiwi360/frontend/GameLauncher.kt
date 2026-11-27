// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import dev.kiwi360.frontend.aiconfig.AiConfigStore
import dev.kiwi360.frontend.drivers.GpuDriverStore
import dev.kiwi360.frontend.library.Game
import dev.kiwi360.frontend.library.GameFile
import dev.kiwi360.frontend.library.GameOption
import dev.kiwi360.frontend.library.GameProbe
import dev.kiwi360.frontend.library.Library
import dev.kiwi360.frontend.overlay.GameLiveSettings
import dev.kiwi360.frontend.settings.ConsoleLanguage
import java.io.File

/**
 * Boots a game in EmulatorActivity (the ":emu" process) with everything the
 * user chose for it: the global settings, the game's own options, what the
 * in-game menu kept for it, and its GPU driver. Used by the library and by
 * other apps through ExternalLaunchActivity.
 */
class GameLauncher(private val library: Library, private val drivers: GpuDriverStore) {

    /**
     * Starts the game; false, and nothing started, on a CPU the emulator can't run on
     * (see [CpuSupport]): the caller tells the user.
     */
    fun launch(activity: Activity, game: Game, file: GameFile): Boolean {
        // The emulator would die at its first atomic instruction, before the game shows.
        if (!CpuSupport.supported) return false
        library.recordLaunch(game, file)
        // Every game boots in a fresh process: Xenia can't start a second game in one.
        killEmulatorProcess(activity)
        // Recommended settings a launch failed on are undone before this one reads the settings.
        runCatching { AiConfigStore.recover(activity, library) }
        // Into the app's task (also from ExternalLaunchActivity, which has its own),
        // finishing an emulator instance already there: its process is gone, but a
        // stopped instance stays in the task and would come back with its old game.
        val intent = Intent().setClassName(activity.packageName, EMULATOR_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // The in-game menu's settings chosen for every game in Settings; everything below,
        // and what the menu kept for this game, goes over them.
        GameLiveSettings.global(activity).applyTo(intent)
        fun option(option: GameOption) = library.effectiveOption(game.key, option)
        intent.putExtra("cvar.target", file.path)
            .putExtra("kiwi.hud", option(GameOption.HUD))
            .putExtra("cvar.kiwi_console_name", library.consoleName)
            .putExtra("cvar.kiwi_default_gamertag", library.gamertag)
            .putExtra("cvar.kiwi_profile_gamertag", library.gamertag)
            // The console's language: the one chosen, else the device's. The country
            // always follows the device's region.
            .putExtra("cvar.user_language", option(GameOption.LANGUAGE).toIntOrNull() ?: ConsoleLanguage.fromDevice())
        ConsoleLanguage.deviceCountry()?.let { intent.putExtra("cvar.user_country", it) }
        option(GameOption.RESOLUTION).toIntOrNull()?.let {
            intent.putExtra("cvar.draw_resolution_scale_x", it).putExtra("cvar.draw_resolution_scale_y", it)
        }
        when (option(GameOption.RTZ)) {
            "on" -> intent.putExtra("cvar.kiwi_rtz_software_emulation", true)
            "off" -> intent.putExtra("cvar.kiwi_rtz_software_emulation", false)
        }
        if (option(GameOption.LICENSE) == "on") {
            intent.putExtra("cvar.license_mask", 1)
        }
        // What the in-game menu changed for this game last time, over the options above.
        intent.putExtra("kiwi.game_key", game.key)
        GameLiveSettings.forGame(activity, game.key).applyTo(intent)
        drivers.driverFor(game.key)?.let {
            intent.putExtra("cvar.vulkan_custom_driver_dir", it.dir.absolutePath)
                .putExtra("cvar.vulkan_custom_driver_name", it.libraryName)
        }
        activity.startActivity(intent)
        return true
    }

    /**
     * The library entry for a game file given by path: the one the library already
     * knows, else one built from the file's own header, keyed like the library keys
     * it so the game's settings apply. Null if it isn't an Xbox 360 game. Reads only
     * the file's header.
     */
    fun gameFor(path: String): Pair<Game, GameFile>? {
        val file = File(path)
        if (!file.isFile) return null
        library.cachedGames().forEach { game ->
            game.files.firstOrNull { it.path.equals(file.absolutePath, ignoreCase = true) }
                ?.let { return game to it }
        }
        val probe = GameProbe.probe(file) ?: return null
        val gameFile = GameFile(
            path = file.absolutePath,
            format = probe.format,
            titleId = probe.titleId,
            discNumber = probe.discNumber,
            discCount = probe.discCount,
            version = probe.version,
        )
        val key = if (probe.titleId != 0L) "%08X".format(probe.titleId) else file.absolutePath
        val game = Game(
            key = key,
            titleId = probe.titleId,
            name = probe.name ?: Library.prettyName(file),
            hasRealName = probe.name != null,
            files = listOf(gameFile),
            iconPath = null,
            coverPath = null,
            lastPlayed = 0L,
        )
        return game to gameFile
    }

    private fun killEmulatorProcess(activity: Activity) = closeRunningGame(activity)

    companion object {
        // By name: referencing the class would not load the native library, but keeps
        // the frontend free of any link to the emulator's code.
        private const val EMULATOR_ACTIVITY = "jp.xenia.emulator.EmulatorActivity"
        private const val KILL_TIMEOUT_MS = 3000L
        // The game's own exit gives itself 3 s before ending the process anyway.
        private const val QUIT_TIMEOUT_MS = 4000L

        /** True while a game runs (the ":emu" process exists). */
        fun isGameRunning(context: Context): Boolean =
            context.getSystemService(ActivityManager::class.java).runningAppProcesses
                ?.any { it.processName == "${context.packageName}:emu" } == true

        /** Broadcast (not exported) that makes the running game leave as its own menu does. */
        @JvmStatic
        fun closeGameAction(context: Context) = "${context.packageName}.CLOSE_GAME"

        /**
         * Ends the running game, if any, and waits until the system has seen its process die.
         * Not for the UI thread: it can wait a few seconds.
         */
        fun closeRunningGame(context: Context) {
            val manager = context.getSystemService(ActivityManager::class.java)
            val name = "${context.packageName}:emu"
            fun running() = manager.runningAppProcesses?.filter { it.processName == name }.orEmpty()
            // Started while the system still holds the dying process, a new emulator would
            // go to that process and die with it: wait until the system has seen it die.
            fun gone(timeoutMs: Long): Boolean {
                val deadline = SystemClock.uptimeMillis() + timeoutMs
                while (running().isNotEmpty()) {
                    if (SystemClock.uptimeMillis() >= deadline) return false
                    SystemClock.sleep(20)
                }
                return true
            }
            if (running().isEmpty()) return
            // Ask first: the game's activity then leaves the task. Killed from outside, it
            // stays in the task and Android starts the game again when it comes back to the
            // front (for example when the activity above it closes).
            context.sendBroadcast(Intent(closeGameAction(context)).setPackage(context.packageName))
            if (gone(QUIT_TIMEOUT_MS)) return
            running().forEach { Process.killProcess(it.pid) }
            gone(KILL_TIMEOUT_MS)
        }
    }
}
