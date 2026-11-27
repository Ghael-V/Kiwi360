// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.update

import androidx.core.content.FileProvider

/**
 * Shares cache/updates with the system installer (authority <applicationId>.updates).
 * A subclass because the manifest already has a plain FileProvider for shared logs.
 */
class UpdateFileProvider : FileProvider()
