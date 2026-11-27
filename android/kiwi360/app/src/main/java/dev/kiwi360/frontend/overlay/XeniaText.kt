// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.overlay

import android.content.Context
import jp.xenia.emulator.R

/**
 * Shows the emulator's own texts (its notices, toasts and message boxes, never
 * the game's) in the user's language. Xenia writes them in English; each line
 * is looked up by that English original, gettext style, and anything not in
 * the catalog is shown as Xenia wrote it. Texts that mix in data (a disc
 * number, an offer ID) are matched by pattern and keep that data.
 *
 * Only call it on text Xenia marks as its own: a game's message box may say
 * "OK" too, and that one has to stay in the game's language.
 */
internal object XeniaText {
    private val EXACT: Map<String, (Context) -> String> = buildMap {
        fun add(original: String, res: Int) = put(original) { c: Context -> c.getString(res) }
        add("Achievement unlocked", R.string.overlay_achievement_unlocked)
        add("Controller Vibration", R.string.xe_vibration_title)
        add("Vibration is now On", R.string.xe_vibration_on)
        add("Vibration is now Off", R.string.xe_vibration_off)
        add("Title Update", R.string.xe_title_update)
        add("An update exists for this game but none is installed.", R.string.xe_title_update_missing)
        add("Warning!", R.string.xe_warning)
        add("Title Update signature doesn't match. Skipping its application!", R.string.xe_title_update_signature)
        add("Signed In", R.string.xe_signed_in)
        add("Signed Out", R.string.xe_signed_out)
        add("Screenshot Created!", R.string.xe_screenshot)
        add("Message Box", R.string.xe_message_box)
        add("OK", R.string.xe_ok)
        add("Cancel", R.string.xe_cancel)
        add("Yes", R.string.xe_yes)
        add("No", R.string.xe_no)
        add("Select storage device", R.string.xe_select_storage)
        add("Dummy HDD", R.string.xe_device_hdd)
        add("Hard Drive", R.string.xe_device_hdd)
        add("Dummy ODD", R.string.xe_device_odd)
        add("Optical Drive", R.string.xe_device_odd)
        add("Memory Unit", R.string.xe_device_mu)
        add("USB Storage", R.string.xe_device_usb)
        add("System", R.string.xe_device_system)
        add("Disc Read Error", R.string.xe_disc_read_error)
        add("There's been an issue reading content from the game disc.", R.string.xe_disc_read_error_1)
        add("This is likely caused by bad or unimplemented file IO calls.", R.string.xe_disc_read_error_2)
        add("Xbox Marketplace", R.string.xe_marketplace)
        add(
            "Game requested to open marketplace page with all content for the current title ID.",
            R.string.xe_marketplace_content_list,
        )
        add("Game requested to open marketplace page with all Xbox Live memberships.", R.string.xe_marketplace_membership_list)
        add("Changing gamertag currently not implemented.", R.string.xe_marketplace_gamertag)
        add("There are no current plans to download files from Xbox Marketplace.", R.string.xe_marketplace_downloads)
        add(
            "Note that since Kiwi360 cannot access Xbox Marketplace, any DLC must be installed manually using File -> Install Content.",
            R.string.xe_marketplace_note,
        )
        put("To start trial games in full mode, set license_mask to 1 in Kiwi360 config file.") { c ->
            c.getString(R.string.xe_marketplace_trial, c.getString(R.string.opt_license))
        }
        add("Do you wish to change license_mask to 1 for *this session*?", R.string.xe_marketplace_trial_ask)
        add("Game requested to open download page for the following free offer IDs:", R.string.xe_marketplace_free_offers)
        add("Game requested to open download page for the following offer IDs:", R.string.xe_marketplace_offers)
        add("NUI Troubleshooter", R.string.xe_kinect_title)
        add("The game has indicated there is a problem with NUI (Kinect).", R.string.xe_kinect_text)
        add("Guest Kernel Crash", R.string.xe_kernel_crash)
        add("The guest kernel has crashed (KeBugCheck).", R.string.xe_kernel_crash_1)
        add("The faulting thread has been suspended.", R.string.xe_kernel_crash_2)
        add("Disc swap cancelled.", R.string.xe_disc_cancelled)
        add("The game requires the correct disc to continue.", R.string.xe_disc_needed)
        add("Please select the requested disc.", R.string.xe_disc_pick_requested)
        add("Failed to mount the selected disc image.", R.string.xe_disc_mount_failed)
        add("Please select a valid disc image file.", R.string.xe_disc_pick_valid)
        add("Failed to access the mounted disc.", R.string.xe_disc_access_failed)
        add("Please try again with a different file.", R.string.xe_disc_try_other)
        add("Wrong Disc!", R.string.xe_disc_wrong)
        add("Please insert the correct disc.", R.string.xe_disc_insert_right)
        add("Wrong Game!", R.string.xe_disc_wrong_game)
        add("This disc does not belong to the current game.", R.string.xe_disc_other_game)
    }

    /** Lines with data in them: the groups fill the string's arguments in order. */
    private val PATTERNS: List<Pair<Regex, Int>> = listOf(
        Regex("Game requested to open marketplace page for offer ID (0x[0-9A-Fa-f]+)\\.") to
            R.string.xe_marketplace_content_item,
        Regex("Game requested to open marketplace page for an Xbox Live membership offer (0x[0-9A-Fa-f]+)\\.") to
            R.string.xe_marketplace_membership_item,
        Regex("Xbox Marketplace requested access to Microsoft Points offer page (0x[0-9A-Fa-f]+)\\.") to
            R.string.xe_marketplace_points,
        Regex("Xbox Marketplace requested access to credit card information page (0x[0-9A-Fa-f]+)\\.") to
            R.string.xe_marketplace_card,
        Regex("Unknown marketplace op (\\d+)") to R.string.xe_marketplace_unknown,
        Regex("Requested: Disc (\\d+)") to R.string.xe_disc_requested,
        Regex("Inserted: Disc (\\d+)") to R.string.xe_disc_inserted,
        Regex("Expected Title ID: ([0-9A-Fa-f]+)") to R.string.xe_disc_expected_id,
        Regex("Inserted Title ID: ([0-9A-Fa-f]+)") to R.string.xe_disc_inserted_id,
    )

    /** [text] with every line found in the catalog in the user's language. */
    fun translate(context: Context, text: String): String =
        if (text.isEmpty()) text else text.lines().joinToString("\n") { line(context, it) }

    private fun line(context: Context, line: String): String {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return line
        EXACT[trimmed]?.let { return it(context) }
        for ((pattern, res) in PATTERNS) {
            pattern.matchEntire(trimmed)?.let { match ->
                return context.getString(res, *match.groupValues.drop(1).toTypedArray())
            }
        }
        return line
    }
}
