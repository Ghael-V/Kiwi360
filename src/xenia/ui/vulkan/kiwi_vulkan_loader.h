// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

#ifndef XENIA_UI_VULKAN_KIWI_VULKAN_LOADER_H_
#define XENIA_UI_VULKAN_KIWI_VULKAN_LOADER_H_

namespace xe {
namespace ui {
namespace vulkan {

// Android: the Vulkan library the process uses, loaded once and never closed:
// the custom driver (vulkan_custom_driver_dir, through libadrenotools) when
// one is chosen, else the system loader. The driver environment
// (kiwi_vulkan_driver_env, Turnip's sysmem mode) is set before it loads, since
// Turnip reads it once per process. Everything that talks to the driver
// (guest RAM allocated before the emulator's own instance, the instance
// itself) goes through it, so memory exported by one is importable by the
// other. Null if no library could be loaded.
void* KiwiOpenVulkanLoader();

}  // namespace vulkan
}  // namespace ui
}  // namespace xe

#endif  // XENIA_UI_VULKAN_KIWI_VULKAN_LOADER_H_
