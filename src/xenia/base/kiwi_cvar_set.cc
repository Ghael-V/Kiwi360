// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

#include "xenia/base/kiwi_cvar_set.h"

#include <cstdint>
#include <cstdlib>
#include <type_traits>

#include "xenia/base/cvar.h"

namespace xe {

namespace {

cvar::IConfigVar* FindConfigVar(const std::string& name) {
  if (!cvar::ConfigVars) {
    return nullptr;
  }
  auto it = cvar::ConfigVars->find(name);
  return it != cvar::ConfigVars->end() ? it->second : nullptr;
}

template <typename T>
bool SetIfType(cvar::IConfigVar* var, const T& value) {
  auto* typed = dynamic_cast<cvar::CommandVar<T>*>(var);
  if (!typed) {
    return false;
  }
  typed->SetCommandLineValue(value);
  return true;
}

template <typename T>
bool GetIfType(cvar::IConfigVar* var, std::string& out) {
  auto* typed = dynamic_cast<cvar::CommandVar<T>*>(var);
  if (!typed || !typed->current_value()) {
    return false;
  }
  if constexpr (std::is_same_v<T, bool>) {
    out = *typed->current_value() ? "true" : "false";
  } else if constexpr (std::is_same_v<T, std::string>) {
    out = *typed->current_value();
  } else {
    out = std::to_string(*typed->current_value());
  }
  return true;
}

}  // namespace

bool KiwiSetCvarFromString(const std::string& name, const std::string& value) {
  cvar::IConfigVar* var = FindConfigVar(name);
  if (!var) {
    return false;
  }
  if (auto* typed = dynamic_cast<cvar::CommandVar<bool>*>(var)) {
    typed->SetCommandLineValue(value == "true" || value == "1" ||
                               value == "on");
    return true;
  }
  const char* text = value.c_str();
  return SetIfType<int32_t>(var, int32_t(std::strtol(text, nullptr, 10))) ||
         SetIfType<uint32_t>(var, uint32_t(std::strtoul(text, nullptr, 10))) ||
         SetIfType<int64_t>(var, int64_t(std::strtoll(text, nullptr, 10))) ||
         SetIfType<uint64_t>(var, uint64_t(std::strtoull(text, nullptr, 10))) ||
         SetIfType<double>(var, std::strtod(text, nullptr)) ||
         SetIfType<std::string>(var, value);
}

std::optional<std::string> KiwiGetCvarAsString(const std::string& name) {
  cvar::IConfigVar* var = FindConfigVar(name);
  std::string value;
  if (var && (GetIfType<bool>(var, value) || GetIfType<int32_t>(var, value) ||
              GetIfType<uint32_t>(var, value) ||
              GetIfType<int64_t>(var, value) ||
              GetIfType<uint64_t>(var, value) ||
              GetIfType<double>(var, value) ||
              GetIfType<std::string>(var, value))) {
    return value;
  }
  return std::nullopt;
}

}  // namespace xe
