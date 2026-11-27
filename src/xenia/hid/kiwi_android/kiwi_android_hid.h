// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

// Kiwi360: gamepad input fed from the Android activity over JNI.

#ifndef XENIA_HID_KIWI_ANDROID_KIWI_ANDROID_HID_H_
#define XENIA_HID_KIWI_ANDROID_KIWI_ANDROID_HID_H_

#include <memory>

#include "xenia/hid/input_driver.h"

namespace xe {
namespace hid {
namespace kiwi_android {

std::unique_ptr<InputDriver> Create(xe::ui::Window* window,
                                    size_t window_z_order);

}  // namespace kiwi_android
}  // namespace hid
}  // namespace xe

#endif  // XENIA_HID_KIWI_ANDROID_KIWI_ANDROID_HID_H_
