// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

// Kiwi360: opens one of the emulator's windows on demand, to test the Compose
// overlay without waiting for a title to ask for it. The real dialog classes
// are built, so a test goes through the same path as a title's request; the
// result is logged ("Kiwi360 UI selftest: ...").
//   am start -n dev.kiwi360.proto/jp.xenia.emulator.EmulatorActivity \
//     --es kiwi.ui_test message|keyboard|signin|create_profile|passcode|
//                       achievements|disc_swap|notice|toast|achievement_toast|
//                       host_toast|crash

#include <jni.h>

#include <string>
#include <vector>

#include "xenia/base/logging.h"
#include "xenia/emulator.h"
#include "xenia/kernel/kernel_state.h"
#include "xenia/kernel/util/shim_utils.h"
#include "xenia/kernel/xam/ui/create_profile_ui.h"
#include "xenia/kernel/xam/ui/disc_swap_ui.h"
#include "xenia/kernel/xam/ui/game_achievements_ui.h"
#include "xenia/kernel/xam/ui/passcode_ui.h"
#include "xenia/kernel/xam/ui/signin_ui.h"
#include "xenia/kernel/xam/xam_state.h"
#include "xenia/kernel/xam/xam_ui.h"
#include "xenia/ui/imgui_dialog.h"
#include "xenia/ui/imgui_guest_notification.h"
#include "xenia/ui/imgui_host_notification.h"
#include "xenia/ui/kiwi_ui_bridge.h"
#include "xenia/ui/window.h"
#include "xenia/ui/windowed_app_context.h"

namespace {

using namespace xe;
using namespace xe::kernel;
using namespace xe::kernel::xam;

void RunSelfTest(const std::string& type) {
  KernelState* state = kernel_state();
  Emulator* emulator = state ? state->emulator() : nullptr;
  if (!emulator || !emulator->imgui_drawer() || !emulator->display_window()) {
    XELOGW("Kiwi360 UI selftest: no running title");
    return;
  }
  auto* drawer = emulator->imgui_drawer();
  auto* input = emulator->input_system();
  auto* profiles = state->xam_state()->profile_manager();
  XELOGI("Kiwi360 UI selftest: opening {}", type);

  if (type == "message") {
    auto* dialog = new MessageBoxDialog(
        drawer, input, "Storage device",
        "No storage device is selected. Progress will not be saved.",
        // "Cancel" is in the frontend's catalog: as the game's text it must stay
        // untranslated.
        {"Select device", "Continue without saving", "Cancel"}, 1);
    dialog->set_close_callback([dialog]() {
      XELOGI("Kiwi360 UI selftest: message -> button {} cancelled {}",
             dialog->chosen_button(), dialog->cancelled());
    });
  } else if (type == "host_message") {
    // The emulator's own text, as XamShowDirtyDiscErrorUI sends it.
    auto* dialog = new MessageBoxDialog(
        drawer, input, "Disc Read Error",
        "There's been an issue reading content from the game disc.\nThis is "
        "likely caused by bad or unimplemented file IO calls.",
        {"OK"}, 0);
    dialog->set_host_text();
    dialog->set_close_callback([dialog]() {
      XELOGI("Kiwi360 UI selftest: host_message -> button {} cancelled {}",
             dialog->chosen_button(), dialog->cancelled());
    });
  } else if (type == "keyboard") {
    std::string title = "Name your save";
    std::string description = "Enter a name for this save game.";
    std::string text = "Partida 1";
    auto* dialog =
        new KeyboardInputDialog(drawer, input, title, description, text, 16);
    dialog->set_close_callback([dialog]() {
      XELOGI("Kiwi360 UI selftest: keyboard -> '{}' cancelled {}",
             dialog->text(), dialog->cancelled());
    });
  } else if (type == "signin") {
    auto* dialog = new xam::ui::SigninUI(drawer, input, profiles, 0, 1);
    dialog->set_close_callback(
        []() { XELOGI("Kiwi360 UI selftest: signin closed"); });
  } else if (type == "create_profile") {
    auto* dialog = new xam::ui::CreateProfileUI(drawer, emulator);
    dialog->set_close_callback([profiles]() {
      XELOGI("Kiwi360 UI selftest: create_profile -> {} profiles",
             profiles->GetAccountCount());
    });
  } else if (type == "passcode") {
    // Outlives the dialog, as a title's buffer would.
    static MESSAGEBOX_RESULT result;
    auto* dialog = new xam::ui::ProfilePasscodeUI(drawer, input, "", "", &result);
    dialog->set_close_callback([dialog]() {
      XELOGI("Kiwi360 UI selftest: passcode -> signed in {} keys {:04X} "
             "{:04X} {:04X} {:04X}",
             dialog->SelectedSignedIn(), uint16_t(result.Passcode[0]),
             uint16_t(result.Passcode[1]), uint16_t(result.Passcode[2]),
             uint16_t(result.Passcode[3]));
    });
  } else if (type == "achievements") {
    UserProfile* profile = profiles->GetProfile(uint8_t(0));
    if (!profile) {
      XELOGW("Kiwi360 UI selftest: no signed-in profile");
      return;
    }
    TitleInfo info = {};
    info.title_name = xe::to_utf16(emulator->title_name());
    info.id = emulator->title_id();
    auto* dialog = new xam::ui::GameAchievementsUI(drawer, input, &info, profile);
    dialog->set_close_callback(
        []() { XELOGI("Kiwi360 UI selftest: achievements closed"); });
  } else if (type == "disc_swap") {
    std::vector<xam::ui::DiscSwapUI::DiscInfo> discs = {
        // The frontend adds the real images beside the running one.
        {"Disc 2 (missing)", xe::to_path("/nonexistent/disc2.iso")},
    };
    auto* dialog = new xam::ui::DiscSwapUI(drawer, input, "Insert Disc 2", discs,
                                      false);
    dialog->set_close_callback([dialog]() {
      XELOGI("Kiwi360 UI selftest: disc_swap -> result {} path {}",
             int(dialog->result()),
             std::string(xe::path_to_utf8(dialog->selected_path())));
    });
  } else if (type == "notice") {
    xe::ui::ImGuiDialog::ShowMessageBox(
        drawer, "Debug message",
        "A title called DbgPrint with something worth showing.\n\nLine 2.");
  } else if (type == "toast") {
    new xe::ui::XNotifyWindow(drawer, "", "Signed in as Kiwi360", 0);
  } else if (type == "achievement_toast") {
    // As AchievementManager shows one.
    new xe::ui::AchievementNotificationWindow(drawer, "Achievement unlocked",
                                              "20G - First Steps", 0);
  } else if (type == "host_toast") {
    // As EmulatorWindow shows one.
    new xe::ui::HostNotificationWindow(
        drawer, "Title Update",
        "An update exists for this game but none is installed.", 0);
  } else if (type == "crash") {
    xe::ui::kiwi_ui::ShowCrash(
        "guest",
        "Kiwi360 UI selftest: this is a test crash report.\n"
        "PC: 0x82000000\nLR: 0x82000004\nAccess Violation: read at 0x0");
  } else {
    XELOGW("Kiwi360 UI selftest: unknown type {}", type);
  }
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_jp_xenia_emulator_EmulatorActivity_nativeUiSelfTest(JNIEnv* env,
                                                         jclass clazz,
                                                         jstring type) {
  const char* chars = env->GetStringUTFChars(type, nullptr);
  const std::string value(chars ? chars : "");
  if (chars) {
    env->ReleaseStringUTFChars(type, chars);
  }
  // On the UI thread already (called from the activity).
  RunSelfTest(value);
}
