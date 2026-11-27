// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.library

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.io.File

/**
 * Time played per game. Written by the emulator process, read by the library
 * and TitleProvider; a plain file per game, like GameLiveSettings, so neither
 * process caches a stale copy.
 */
class PlayTime private constructor(private val file: File) {
    data class Totals(val seconds: Long, val sessions: Int, val lastPlayed: Long)

    fun read(): Totals? = try {
        if (file.isFile) {
            JSONObject(file.readText()).let {
                Totals(it.optLong("seconds"), it.optInt("sessions"), it.optLong("last_played"))
            }
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }

    /** Adds [seconds] (and a session if [newSession]); stamps the time as the last played. */
    @Synchronized
    fun add(seconds: Long, newSession: Boolean) {
        val totals = read() ?: Totals(0, 0, 0)
        val json = JSONObject()
            .put("seconds", totals.seconds + seconds)
            .put("sessions", totals.sessions + if (newSession) 1 else 0)
            .put("last_played", System.currentTimeMillis())
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.toString())
            temp.renameTo(file)
        } catch (_: Exception) {
        }
    }

    companion object {
        fun forGame(context: Context, gameKey: String): PlayTime {
            val name = gameKey.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)
            return PlayTime(File(File(context.filesDir, "playtime"), "$name.json"))
        }
    }
}

/**
 * Counts, in the emulator process, the time a game spends in the foreground.
 * Kept every minute as well as on pause, so a crash or a killed process loses at
 * most a minute.
 */
class PlayTimeTracker(context: Context, gameKey: String) {
    private val store = PlayTime.forGame(context, gameKey)
    private val handler = Handler(Looper.getMainLooper())
    private var resumedAt = 0L
    private val periodic = object : Runnable {
        override fun run() {
            commit()
            handler.postDelayed(this, FLUSH_MS)
        }
    }

    /** Once per boot. */
    fun startSession() = store.add(0, newSession = true)

    fun resume() {
        resumedAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(periodic)
        handler.postDelayed(periodic, FLUSH_MS)
    }

    fun pause() {
        handler.removeCallbacks(periodic)
        commit()
        resumedAt = 0L
    }

    private fun commit() {
        if (resumedAt == 0L) return
        val seconds = (SystemClock.elapsedRealtime() - resumedAt) / 1000
        if (seconds > 0) {
            store.add(seconds, newSession = false)
            resumedAt += seconds * 1000
        }
    }

    private companion object {
        const val FLUSH_MS = 60_000L
    }
}
