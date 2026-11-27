// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import dev.kiwi360.frontend.drivers.GpuDriverStore
import dev.kiwi360.frontend.library.Library
import jp.xenia.emulator.R

/**
 * Lets other apps (frontends like Bramble) boot a game:
 *
 *   action dev.kiwi360.LAUNCH_GAME, string extra "path": the game file's absolute path
 *
 * The game boots exactly as from the library, with the settings the user chose for
 * it; nothing else in the intent is used, so other apps can't pass emulator
 * settings. A game already running is closed first. No UI of its own: it shows a
 * toast if the game can't be opened, or if this device's CPU can't run games.
 */
class ExternalLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
        finish()
    }

    private fun handle(intent: Intent) {
        if (intent.action != ACTION_LAUNCH_GAME) return
        val path = intent.getStringExtra(EXTRA_PATH)?.trim().orEmpty()
        if (!CpuSupport.supported) {
            toast(getString(R.string.cpu_unsupported_toast, CpuSupport.result.cpuName))
            return
        }
        if (!Environment.isExternalStorageManager()) {
            // The library asks for the access.
            toast(getString(R.string.external_no_access))
            // Into the app's task, reusing a library already on top of it.
            startActivity(
                Intent(this, LibraryActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            return
        }
        val drivers = GpuDriverStore(this).also { it.installBundled(this) }
        val launcher = GameLauncher(Library(this), drivers)
        val found = path.takeIf { it.isNotEmpty() }?.let { launcher.gameFor(it) }
        if (found == null) {
            toast(
                getString(
                    if (path.isNotEmpty() && java.io.File(path).isFile) R.string.external_not_a_game
                    else R.string.external_not_found,
                    path,
                ),
            )
            return
        }
        if (!launcher.launch(this, found.first, found.second)) {
            toast(getString(R.string.cpu_unsupported_toast, CpuSupport.result.cpuName))
        }
    }

    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()

    companion object {
        const val ACTION_LAUNCH_GAME = "dev.kiwi360.LAUNCH_GAME"
        const val EXTRA_PATH = "path"
    }
}
