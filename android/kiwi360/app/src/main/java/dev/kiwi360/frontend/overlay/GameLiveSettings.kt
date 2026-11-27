// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.overlay

import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.File

/**
 * Settings changed in the in-game menu: applied live, and kept for the game so
 * its next launches start with them. Written by the game's process, read by the
 * library when it launches the game; a plain file, so neither process caches a
 * stale copy.
 *
 * Keys are launch extras: "cvar.<name>" (an Int or a String, like the extras
 * the library sets) or "kiwi.hud".
 */
class GameLiveSettings private constructor(private val file: File) {
    fun all(): JSONObject = try {
        if (file.isFile) JSONObject(file.readText()) else JSONObject()
    } catch (_: Exception) {
        JSONObject()
    }

    fun put(key: String, value: Any) = write(all().put(key, value))

    fun remove(key: String) {
        val json = all()
        if (json.remove(key) != null) write(json)
    }

    private fun write(json: JSONObject) {
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.toString())
            temp.renameTo(file)
        } catch (_: Exception) {
        }
    }

    /** Adds the kept settings to a launch intent, over the library's own. */
    fun applyTo(intent: Intent) {
        val json = all()
        for (key in json.keys()) {
            when (val value = json.get(key)) {
                is Int -> intent.putExtra(key, value)
                is Long -> intent.putExtra(key, value.toInt())
                is Boolean -> intent.putExtra(key, value)
                else -> intent.putExtra(key, value.toString())
            }
        }
    }

    fun clear() {
        file.delete()
    }

    companion object {
        /** The same settings chosen for every game in Settings; a game's own go over them. */
        fun global(context: Context): GameLiveSettings =
            GameLiveSettings(File(context.filesDir, "global_settings.json"))

        fun forGame(context: Context, gameKey: String): GameLiveSettings {
            val name = gameKey.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)
            return GameLiveSettings(File(File(context.filesDir, "game_live"), "$name.json"))
        }
    }
}

/** What the in-game menu needs from the running emulator. */
interface GameMenuHost {
    /** The current value of a cvar as text, or null if it doesn't exist. */
    fun getCvar(name: String): String?

    /** Sets a cvar for the running game; false if it doesn't exist. */
    fun setCvar(name: String, value: String): Boolean

    /** Performance HUD mode: "full", "fps" or "off". */
    var hudMode: String

    /** What the on-screen gamepad holds, merged with any physical pad. */
    fun setVirtualPad(state: VirtualPadState)
}
