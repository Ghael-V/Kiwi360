// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.aiconfig

import android.content.Context
import jp.xenia.emulator.R
import org.json.JSONObject

/**
 * The settings recommended settings may change, from res/raw/ai_settings_schema.json (made by
 * tools/ai-schema/gen_schema.py from Xenia Edge's cvars). Every suggestion from the server is
 * checked here again before it is written. A key is a game option of the settings screens
 * ("app": the internal resolution, exact vertex rounding) or a cvar the settings screens do
 * not show ("hidden"), kept per game like the in-game menu's settings.
 */
class AiSchema(val app: String, val schemaVersion: Int, val settings: List<Entry>) {
    class Entry(
        val key: String,
        val tier: String,
        val type: String,
        val default: Long,
        val min: Long?,
        val max: Long?,
        val enum: List<Pair<String, Long>>,
        val uiTitle: String?,
        val source: String,
        /** How the cvar is passed at launch: "bool", "int" or "string" (an enum of its values). */
        val cvarType: String,
        val description: String,
    ) {
        val isRisky get() = tier == "risky"
        val isBool get() = type == "bool"
        val isApp get() = source == "app"

        /** The cvar value for [value]: Boolean, Int, or for a string cvar the enum value's name. */
        fun cvarValue(value: Long): Any = when (cvarType) {
            "bool" -> value != 0L
            "string" -> enum.firstOrNull { it.second == value }?.first ?: enum.first().first
            else -> value.toInt()
        }

        /** The schema value of a stored cvar value, or null if it doesn't fit. */
        fun fromCvar(stored: Any?): Long? = when (stored) {
            null -> null
            is Boolean -> if (stored) 1L else 0L
            is Number -> stored.toLong()
            is String -> if (cvarType == "string") {
                enum.firstOrNull { it.first == stored }?.second
            } else {
                stored.toLongOrNull() ?: stored.toBooleanStrictOrNull()?.let { if (it) 1L else 0L }
            }
            else -> null
        }
    }

    private val byKey = settings.associateBy { it.key }

    fun entry(key: String): Entry? = byKey[key]

    /**
     * The value to store for [value] (a JSON value from the server) on [key], or null when the key
     * is unknown or the value does not fit it (wrong type, not one of the enum values, out of range).
     */
    fun normalize(key: String, value: Any?): Long? {
        val entry = byKey[key] ?: return null
        if (entry.isBool) return (value as? Boolean)?.let { if (it) 1L else 0L }
        val number = when (value) {
            is Int -> value.toLong()
            is Long -> value
            is Double -> value.toLong().takeIf { it.toDouble() == value }
            else -> null
        } ?: return null
        if (entry.enum.isNotEmpty()) return number.takeIf { n -> entry.enum.any { it.second == n } }
        if (entry.min == null || entry.max == null) return null
        return number.takeIf { it in entry.min..entry.max }
    }

    /** A value as the server reads it: "true"/"false" or the number. */
    fun wire(entry: Entry, value: Long): String = if (entry.isBool) (value != 0L).toString() else value.toString()

    companion object {
        @Volatile
        private var cached: AiSchema? = null

        fun load(context: Context): AiSchema = cached ?: synchronized(this) {
            cached ?: parse(
                context.resources.openRawResource(R.raw.ai_settings_schema).bufferedReader().use { it.readText() },
            ).also { cached = it }
        }

        private fun parse(text: String): AiSchema {
            val root = JSONObject(text)
            val list = root.getJSONArray("settings")
            val entries = (0 until list.length()).map { i ->
                val o = list.getJSONObject(i)
                val type = o.getString("type")
                val enumArray = o.optJSONArray("enum")
                val enum = if (enumArray == null) emptyList() else (0 until enumArray.length()).map { j ->
                    val v = enumArray.getJSONObject(j)
                    v.getString("name") to v.getLong("value")
                }
                val default = o.optString("default")
                Entry(
                    key = o.getString("key"),
                    tier = o.getString("tier"),
                    type = type,
                    default = if (type == "bool") (if (default == "true") 1L else 0L) else default.toLongOrNull() ?: 0L,
                    min = o.optString("min").toLongOrNull(),
                    max = o.optString("max").toLongOrNull(),
                    enum = enum,
                    uiTitle = o.optString("ui_title").ifEmpty { null },
                    source = o.optString("source"),
                    cvarType = o.optString("cvar_type").ifEmpty { if (type == "bool") "bool" else "int" },
                    description = o.optString("description"),
                )
            }
            return AiSchema(root.getString("app"), root.getInt("schema_version"), entries)
        }
    }
}
