// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

// Kiwi360: settings changed from the in-game menu while a game runs. A cvar is
// set for this session with command line priority (the next launch starts from
// the app's own settings again); the code reading it picks it up live, and
// display effects are applied to the presenter right away.

#include <jni.h>

#include <cstdint>
#include <cstdlib>
#include <string>
#include <optional>

#include "xenia/app/emulator_window.h"
#include "xenia/base/cvar.h"
#include "xenia/base/kiwi_cvar_set.h"
#include "xenia/base/logging.h"
#include "xenia/emulator.h"
#include "xenia/gpu/graphics_system.h"
#include "xenia/kernel/kernel_state.h"
#include "xenia/ui/kiwi_post_effects_chain.h"

DECLARE_string(postprocess_antialiasing);

namespace xe::kernel {
// Defined in kernel_state.cc (declared in kernel/util/shim_utils.h).
KernelState* kernel_state();
}  // namespace xe::kernel

namespace {

std::string ToString(JNIEnv* env, jstring value) {
  if (!value) {
    return {};
  }
  const char* chars = env->GetStringUTFChars(value, nullptr);
  std::string result(chars ? chars : "");
  if (chars) {
    env->ReleaseStringUTFChars(value, chars);
  }
  return result;
}

// Display effects are read when the presenter's paint config is set, so they
// need applying; everything else the menu changes is read live.
void ApplyDisplayConfig() {
  xe::kernel::KernelState* kernel_state = xe::kernel::kernel_state();
  if (!kernel_state || !kernel_state->emulator()) {
    return;
  }
  xe::gpu::GraphicsSystem* graphics_system =
      kernel_state->emulator()->graphics_system();
  if (!graphics_system) {
    return;
  }
  if (xe::gpu::CommandProcessor* command_processor =
          graphics_system->command_processor()) {
    command_processor->SetDesiredSwapPostEffect(
        xe::app::EmulatorWindow::GetSwapPostEffectForCvarValue(
            cvars::postprocess_antialiasing));
  }
  if (xe::ui::Presenter* presenter = graphics_system->presenter()) {
    presenter->SetGuestOutputPaintConfigFromUIThread(
        xe::app::EmulatorWindow::GetGuestOutputPaintConfigForCvars());
  }
}

}  // namespace

// Sets a cvar by name from its text form. Must be called on the UI (main)
// thread, where display effects are applied. False if there is no such cvar.
extern "C" JNIEXPORT jboolean JNICALL
Java_jp_xenia_emulator_EmulatorActivity_nativeSetCvar(JNIEnv* env, jclass clazz,
                                                      jstring jname,
                                                      jstring jvalue) {
  const std::string name = ToString(env, jname);
  const std::string value = ToString(env, jvalue);
  if (name == "kiwi_post_effects") {
    // The presenter reads the cvar only at launch; live changes reach it
    // through this copy, safe to hand over between threads.
    xe::ui::KiwiPublishPostEffects(value);
  }
  if (!xe::KiwiSetCvarFromString(name, value)) {
    XELOGW("Kiwi360 live settings: no settable cvar {}", name);
    return JNI_FALSE;
  }
  XELOGI("Kiwi360 live settings: {} = {}", name, value);
  if (name.rfind("postprocess_", 0) == 0) {
    ApplyDisplayConfig();
  }
  return JNI_TRUE;
}

// The current value of a cvar as text, or null if there is no such cvar.
extern "C" JNIEXPORT jstring JNICALL
Java_jp_xenia_emulator_EmulatorActivity_nativeGetCvar(JNIEnv* env, jclass clazz,
                                                      jstring jname) {
  std::optional<std::string> value =
      xe::KiwiGetCvarAsString(ToString(env, jname));
  return value ? env->NewStringUTF(value->c_str()) : nullptr;
}
