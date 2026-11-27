// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

#include "xenia/ui/kiwi_ui_bridge.h"

#include "xenia/base/platform.h"

namespace xe {
namespace ui {
namespace kiwi_ui {

namespace {
// The request was answered; nothing more to send unless Resend() is called.
constexpr uint64_t kAnswered = ~uint64_t(0);
}  // namespace

bool RemoteDialog::Pump(
    const std::function<std::string()>& make_request,
    const std::function<void(const JsonReader&)>& on_response) {
  if (refused_) {
    return false;
  }
  if (!id_) {
    id_ = Request(make_request());
    if (!id_) {
      refused_ = true;
      return false;
    }
  }
  if (id_ == kAnswered) {
    return true;
  }
  if (auto response = TakeResponse(id_)) {
    id_ = kAnswered;
    on_response(JsonReader(*response));
  }
  return true;
}

#if !XE_PLATFORM_ANDROID
// Only the Android frontend draws these windows.
bool Available() { return false; }
uint64_t Request(const std::string& json) { return 0; }
std::optional<std::string> TakeResponse(uint64_t id) { return std::nullopt; }
bool Toast(std::string_view kind, std::string_view title,
           std::string_view text) {
  return false;
}
bool ShowCrash(std::string_view title, std::string_view text) { return false; }
#endif

}  // namespace kiwi_ui
}  // namespace ui
}  // namespace xe
