// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.effects

import android.content.Context
import dev.kiwi360.frontend.overlay.GameLiveSettings

/**
 * Where the effects chains are kept: in GameLiveSettings, which the launch passes to the
 * emulator (global first, then the game's own). The global chain absent or "" means no
 * effects; a game's chain absent means it follows the global one, present (even "") means
 * its own.
 */
object PostEffectsStore {
    fun global(context: Context): String =
        GameLiveSettings.global(context).all().optString(POST_EFFECTS_KEY, "")

    fun setGlobal(context: Context, chain: String) {
        val settings = GameLiveSettings.global(context)
        if (chain.isEmpty()) settings.remove(POST_EFFECTS_KEY) else settings.put(POST_EFFECTS_KEY, chain)
    }

    /** The game's own chain, or null when it follows the global one. */
    fun game(context: Context, gameKey: String): String? {
        val json = GameLiveSettings.forGame(context, gameKey).all()
        return if (json.has(POST_EFFECTS_KEY)) json.optString(POST_EFFECTS_KEY, "") else null
    }

    /** Sets the game's own chain; null makes it follow the global one again. */
    fun setGame(context: Context, gameKey: String, chain: String?) {
        val settings = GameLiveSettings.forGame(context, gameKey)
        if (chain == null) {
            settings.remove(POST_EFFECTS_KEY)
            settings.remove(POST_EFFECTS_CUSTOM_KEY)
            return
        }
        settings.put(POST_EFFECTS_KEY, chain)
        // Kept apart too, so cycling presets in the in-game menu can come back to it.
        if (isCustom(chain)) settings.put(POST_EFFECTS_CUSTOM_KEY, chain)
    }

    /** The game's last chain of its own that is not a preset, if any. */
    fun gameCustom(context: Context, gameKey: String): String? =
        customOf(GameLiveSettings.forGame(context, gameKey).all().optString(POST_EFFECTS_CUSTOM_KEY, ""))

    fun effective(context: Context, gameKey: String): String = game(context, gameKey) ?: global(context)

    /** A chain of the user's own: not empty and no preset's. */
    fun isCustom(chain: String): Boolean = PostChain.normalize(chain).let { it.isNotEmpty() && PostPreset.of(it) == null }

    /** [chain] normalized if it is a custom one, else null. */
    fun customOf(chain: String?): String? = chain?.let(PostChain::normalize)?.takeIf(::isCustom)
}
