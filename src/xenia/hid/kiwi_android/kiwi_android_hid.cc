// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

// Kiwi360: one gamepad whose state the Android activity pushes over JNI
// (jp.xenia.emulator.EmulatorActivity.nativeSetGamepadState). Every press is
// reported for at least kMinPressDuration, like a physical press, so a press
// whose release arrives immediately (adb "input gamepad keyevent") is still
// seen by games that sample input once per frame or debounce it.

#include "xenia/hid/kiwi_android/kiwi_android_hid.h"

#include <jni.h>

#include <array>
#include <chrono>
#include <cstdint>
#include <mutex>

#include "xenia/base/logging.h"
#include "xenia/hid/input.h"
#include "xenia/ui/virtual_key.h"

namespace xe {
namespace hid {
namespace kiwi_android {

namespace {

using Clock = std::chrono::steady_clock;
constexpr Clock::duration kMinPressDuration = std::chrono::milliseconds(100);

struct PadState {
  std::mutex mutex;
  bool connected = false;
  uint16_t buttons = 0;
  // Per button bit: the press stays reported until this time.
  std::array<Clock::time_point, 16> held_until{};
  uint8_t left_trigger = 0;
  uint8_t right_trigger = 0;
  int16_t thumb_lx = 0;
  int16_t thumb_ly = 0;
  int16_t thumb_rx = 0;
  int16_t thumb_ry = 0;
  uint32_t packet_number = 0;
};

PadState& Pad() {
  static PadState pad;
  return pad;
}

// Buttons held now or still within their minimum press. Lock pad.mutex.
uint16_t ReportedButtons(const PadState& pad) {
  uint16_t buttons = pad.buttons;
  const Clock::time_point now = Clock::now();
  for (uint32_t bit = 0; bit < pad.held_until.size(); ++bit) {
    if (now < pad.held_until[bit]) {
      buttons |= uint16_t(1) << bit;
    }
  }
  return buttons;
}

int16_t ClampAxis(jint value) {
  return int16_t(value < -32768 ? -32768 : (value > 32767 ? 32767 : value));
}

uint8_t ClampTrigger(jint value) {
  return uint8_t(value < 0 ? 0 : (value > 255 ? 255 : value));
}

// Bit index in X_INPUT_GAMEPAD::buttons -> keystroke virtual key.
constexpr std::array<ui::VirtualKey, 16> kButtonKeys = {
    ui::VirtualKey::kXInputPadDpadUp,     ui::VirtualKey::kXInputPadDpadDown,
    ui::VirtualKey::kXInputPadDpadLeft,   ui::VirtualKey::kXInputPadDpadRight,
    ui::VirtualKey::kXInputPadStart,      ui::VirtualKey::kXInputPadBack,
    ui::VirtualKey::kXInputPadLThumbPress, ui::VirtualKey::kXInputPadRThumbPress,
    ui::VirtualKey::kXInputPadLShoulder,  ui::VirtualKey::kXInputPadRShoulder,
    ui::VirtualKey::kXInputPadGuide,      ui::VirtualKey::kNone,
    ui::VirtualKey::kXInputPadA,          ui::VirtualKey::kXInputPadB,
    ui::VirtualKey::kXInputPadX,          ui::VirtualKey::kXInputPadY,
};

class KiwiAndroidInputDriver final : public InputDriver {
 public:
  KiwiAndroidInputDriver(xe::ui::Window* window, size_t window_z_order)
      : InputDriver(window, window_z_order) {}

  X_STATUS Setup() override {
    XELOGI("Kiwi360: Android gamepad input driver ready");
    return X_STATUS_SUCCESS;
  }

  X_RESULT GetCapabilities(uint32_t user_index, uint32_t flags,
                           X_INPUT_CAPABILITIES* out_caps) override {
    if (user_index != 0 || !out_caps) {
      return user_index != 0 ? X_ERROR_DEVICE_NOT_CONNECTED
                             : X_ERROR_BAD_ARGUMENTS;
    }
    *out_caps = {};
    out_caps->type = 0x01;      // XINPUT_DEVTYPE_GAMEPAD
    out_caps->sub_type = 0x01;  // XINPUT_DEVSUBTYPE_GAMEPAD
    out_caps->flags = 0;
    out_caps->gamepad.buttons = 0xF3FF;
    out_caps->gamepad.left_trigger = 0xFF;
    out_caps->gamepad.right_trigger = 0xFF;
    out_caps->gamepad.thumb_lx = int16_t(0xFFC0);
    out_caps->gamepad.thumb_ly = int16_t(0xFFC0);
    out_caps->gamepad.thumb_rx = int16_t(0xFFC0);
    out_caps->gamepad.thumb_ry = int16_t(0xFFC0);
    out_caps->vibration.left_motor_speed = 0;
    out_caps->vibration.right_motor_speed = 0;
    return X_ERROR_SUCCESS;
  }

  X_RESULT GetState(uint32_t user_index, X_INPUT_STATE* out_state) override {
    if (user_index != 0) {
      return X_ERROR_DEVICE_NOT_CONNECTED;
    }
    PadState& pad = Pad();
    std::lock_guard<std::mutex> lock(pad.mutex);
    if (out_state) {
      uint16_t buttons = ReportedButtons(pad);
      if (!logged_first_poll_) {
        logged_first_poll_ = true;
        XELOGI("Kiwi360 input: guest polls the gamepad");
      }
      if (buttons != last_polled_buttons_) {
        XELOGI("Kiwi360 input: guest sees buttons {:04X}", buttons);
      }
      if (buttons != last_polled_buttons_ ||
          pad.packet_number != last_packet_number_) {
        ++reported_packet_number_;
      }
      last_polled_buttons_ = buttons;
      last_packet_number_ = pad.packet_number;
      out_state->packet_number = reported_packet_number_;
      out_state->gamepad.buttons = buttons;
      out_state->gamepad.left_trigger = pad.left_trigger;
      out_state->gamepad.right_trigger = pad.right_trigger;
      out_state->gamepad.thumb_lx = pad.thumb_lx;
      out_state->gamepad.thumb_ly = pad.thumb_ly;
      out_state->gamepad.thumb_rx = pad.thumb_rx;
      out_state->gamepad.thumb_ry = pad.thumb_ry;
    }
    return X_ERROR_SUCCESS;
  }

  X_RESULT SetState(uint32_t user_index,
                    X_INPUT_VIBRATION* vibration) override {
    return user_index == 0 ? X_ERROR_SUCCESS : X_ERROR_DEVICE_NOT_CONNECTED;
  }

  X_RESULT GetKeystroke(uint32_t user_index, uint32_t flags,
                        X_INPUT_KEYSTROKE* out_keystroke) override {
    if (user_index != 0 && user_index != XUserIndexAny) {
      return X_ERROR_DEVICE_NOT_CONNECTED;
    }
    if (!out_keystroke) {
      return X_ERROR_BAD_ARGUMENTS;
    }
    if (!logged_first_keystroke_poll_) {
      logged_first_keystroke_poll_ = true;
      XELOGI("Kiwi360 input: guest polls keystrokes (user {:02X})", user_index);
    }
    uint16_t buttons;
    {
      PadState& pad = Pad();
      std::lock_guard<std::mutex> lock(pad.mutex);
      buttons = ReportedButtons(pad);
    }
    uint16_t changed = buttons ^ keystroke_buttons_;
    for (uint32_t bit = 0; bit < kButtonKeys.size(); ++bit) {
      uint16_t mask = uint16_t(1) << bit;
      if (!(changed & mask) || kButtonKeys[bit] == ui::VirtualKey::kNone) {
        continue;
      }
      bool down = (buttons & mask) != 0;
      keystroke_buttons_ ^= mask;
      *out_keystroke = {};
      out_keystroke->virtual_key = uint16_t(kButtonKeys[bit]);
      out_keystroke->unicode = 0;
      out_keystroke->flags =
          down ? X_INPUT_KEYSTROKE_KEYDOWN : X_INPUT_KEYSTROKE_KEYUP;
      out_keystroke->user_index = 0;
      out_keystroke->hid_code = 0;
      XELOGI("Kiwi360 input: keystroke {:04X} {}", uint16_t(kButtonKeys[bit]),
             down ? "down" : "up");
      return X_ERROR_SUCCESS;
    }
    return X_ERROR_EMPTY;
  }

  InputType GetInputType() const override { return InputType::Controller; }

  std::vector<InputDeviceInfo> EnumerateDevices() override {
    InputDeviceInfo info{};
    info.driver_slot = 0;
    info.stable_id = "kiwi360-android-gamepad";
    info.display_name = "Android gamepad";
    info.preferred_slot = 0;
    info.auto_bind = true;
    return {info};
  }

  void DiscardKeystrokes() override { SyncKeystrokes(); }
  void ResetKeystrokes() override { SyncKeystrokes(); }

 private:
  void SyncKeystrokes() {
    PadState& pad = Pad();
    std::lock_guard<std::mutex> lock(pad.mutex);
    keystroke_buttons_ = ReportedButtons(pad);
  }

  uint16_t last_polled_buttons_ = 0;
  uint32_t last_packet_number_ = 0;
  uint32_t reported_packet_number_ = 0;
  uint16_t keystroke_buttons_ = 0;
  bool logged_first_poll_ = false;
  bool logged_first_keystroke_poll_ = false;
};

}  // namespace

std::unique_ptr<InputDriver> Create(xe::ui::Window* window,
                                    size_t window_z_order) {
  return std::make_unique<KiwiAndroidInputDriver>(window, window_z_order);
}

}  // namespace kiwi_android
}  // namespace hid
}  // namespace xe

// Buttons use the X_INPUT_GAMEPAD bit layout; thumbs are -32768..32767 with up
// positive, triggers 0..255.
extern "C" JNIEXPORT void JNICALL
Java_jp_xenia_emulator_EmulatorActivity_nativeSetGamepadState(
    JNIEnv* env, jclass clazz, jint buttons, jint left_trigger,
    jint right_trigger, jint thumb_lx, jint thumb_ly, jint thumb_rx,
    jint thumb_ry) {
  auto& pad = xe::hid::kiwi_android::Pad();
  std::lock_guard<std::mutex> lock(pad.mutex);
  uint16_t new_buttons = uint16_t(buttons);
  if (new_buttons != pad.buttons) {
    XELOGI("Kiwi360 input: activity buttons {:04X}", new_buttons);
  }
  const uint16_t pressed = uint16_t(new_buttons & ~pad.buttons);
  if (pressed) {
    const auto until = xe::hid::kiwi_android::Clock::now() +
                       xe::hid::kiwi_android::kMinPressDuration;
    for (uint32_t bit = 0; bit < pad.held_until.size(); ++bit) {
      if (pressed & (uint16_t(1) << bit)) {
        pad.held_until[bit] = until;
      }
    }
  }
  pad.buttons = new_buttons;
  pad.left_trigger = xe::hid::kiwi_android::ClampTrigger(left_trigger);
  pad.right_trigger = xe::hid::kiwi_android::ClampTrigger(right_trigger);
  pad.thumb_lx = xe::hid::kiwi_android::ClampAxis(thumb_lx);
  pad.thumb_ly = xe::hid::kiwi_android::ClampAxis(thumb_ly);
  pad.thumb_rx = xe::hid::kiwi_android::ClampAxis(thumb_rx);
  pad.thumb_ry = xe::hid::kiwi_android::ClampAxis(thumb_ry);
  pad.connected = true;
  ++pad.packet_number;
}
