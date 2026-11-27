// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

#ifndef XENIA_BASE_KIWI_CVAR_SET_H_
#define XENIA_BASE_KIWI_CVAR_SET_H_

#include <optional>
#include <string>

namespace xe {

// Sets a cvar by name from its text form, with command line priority (for this
// session only). False if there is no such cvar or its type isn't handled.
bool KiwiSetCvarFromString(const std::string& name, const std::string& value);

// The current value of a cvar as text, if it exists.
std::optional<std::string> KiwiGetCvarAsString(const std::string& name);

}  // namespace xe

#endif  // XENIA_BASE_KIWI_CVAR_SET_H_
