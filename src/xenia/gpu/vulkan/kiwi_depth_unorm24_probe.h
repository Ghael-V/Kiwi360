// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

#ifndef XENIA_GPU_VULKAN_KIWI_DEPTH_UNORM24_PROBE_H_
#define XENIA_GPU_VULKAN_KIWI_DEPTH_UNORM24_PROBE_H_

#include <optional>

#include "xenia/ui/vulkan/vulkan_device.h"

namespace xe {
namespace gpu {
namespace vulkan {

// Renders, into a VK_FORMAT_D24_UNORM_S8_UINT image, the fragment depth values
// the EDRAM ownership transfer shaders write for a set of guest 24-bit unorm
// depth values, and reads the stored values back. Drivers differ in how they
// round fragment depth to 24-bit unorm (some scale by 2^24 - 1, as the Vulkan
// specification says, others by 2^24), and from 0x800000 up no 32-bit float
// gives the same 24-bit value with both, so on some drivers every transfer
// between D24S8 render targets adds 1 to the upper half of the depth range.
// Returns whether all values came back unchanged, or nothing if the probe
// could not run.
std::optional<bool> KiwiProbeDepthUnorm24TransferExact(
    const ui::vulkan::VulkanDevice* vulkan_device);

}  // namespace vulkan
}  // namespace gpu
}  // namespace xe

#endif  // XENIA_GPU_VULKAN_KIWI_DEPTH_UNORM24_PROBE_H_
