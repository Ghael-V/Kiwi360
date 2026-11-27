// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

#include "xenia/kiwi_guest_ram.h"

#include "xenia/base/cvar.h"
#include "xenia/base/logging.h"
#include "xenia/base/platform.h"

DEFINE_bool(kiwi_gpu_guest_ram, true,
            "Allocate guest physical RAM through the Vulkan driver and share it "
            "with the GPU (zero copy) where guest RAM can't be imported. "
            "Android only.",
            "Kiwi360");

#if XE_PLATFORM_ANDROID

#include <dlfcn.h>

#include "xenia/ui/vulkan/kiwi_vulkan_loader.h"
#include <unistd.h>

#include <cstring>
#include <vector>

#include "third_party/Vulkan-Headers/include/vulkan/vulkan.h"

namespace xe {
namespace kiwi {

namespace {

GuestRamAllocation g_allocation;
bool g_attempted = false;

// A throwaway instance and device only for the allocation. An OPAQUE_FD keeps
// the memory payload alive after its VkDeviceMemory and device are destroyed,
// and the emulator's own device imports it later.
bool Allocate(uint64_t size, GuestRamAllocation& out) {
  // The same driver the emulator's own device will use: memory from another
  // driver (the system one while a custom Turnip runs the game) has memory
  // types the game's device doesn't have and can't be imported.
  void* library = ui::vulkan::KiwiOpenVulkanLoader();
  if (!library) {
    XELOGW("Kiwi360 guest RAM: no Vulkan library");
    return false;
  }
  auto get_instance_proc_addr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
      dlsym(library, "vkGetInstanceProcAddr"));
  if (!get_instance_proc_addr) {
    return false;
  }
#define KIWI_GLOBAL(name) \
  auto name =             \
      reinterpret_cast<PFN_##name>(get_instance_proc_addr(nullptr, #name))
  KIWI_GLOBAL(vkCreateInstance);
#undef KIWI_GLOBAL
  if (!vkCreateInstance) {
    return false;
  }
  VkApplicationInfo app = {VK_STRUCTURE_TYPE_APPLICATION_INFO};
  app.pApplicationName = "kiwi360-guest-ram";
  app.apiVersion = VK_API_VERSION_1_1;
  VkInstanceCreateInfo instance_info = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
  instance_info.pApplicationInfo = &app;
  VkInstance instance;
  if (vkCreateInstance(&instance_info, nullptr, &instance) != VK_SUCCESS) {
    XELOGW("Kiwi360 guest RAM: vkCreateInstance failed");
    return false;
  }
#define KIWI_INSTANCE(name) \
  auto name =               \
      reinterpret_cast<PFN_##name>(get_instance_proc_addr(instance, #name))
  KIWI_INSTANCE(vkDestroyInstance);
  KIWI_INSTANCE(vkEnumeratePhysicalDevices);
  KIWI_INSTANCE(vkGetPhysicalDeviceMemoryProperties);
  KIWI_INSTANCE(vkEnumerateDeviceExtensionProperties);
  KIWI_INSTANCE(vkCreateDevice);
  KIWI_INSTANCE(vkGetDeviceProcAddr);
#undef KIWI_INSTANCE

  bool result = false;
  VkDevice device = VK_NULL_HANDLE;
  do {
    // The emulator uses the first device too (vulkan_device = -1) - Android
    // has one GPU.
    uint32_t count = 1;
    VkPhysicalDevice physical_device;
    VkResult enumerate =
        vkEnumeratePhysicalDevices(instance, &count, &physical_device);
    if ((enumerate != VK_SUCCESS && enumerate != VK_INCOMPLETE) || !count) {
      break;
    }
    uint32_t extension_count = 0;
    vkEnumerateDeviceExtensionProperties(physical_device, nullptr,
                                         &extension_count, nullptr);
    std::vector<VkExtensionProperties> extensions(extension_count);
    vkEnumerateDeviceExtensionProperties(physical_device, nullptr,
                                         &extension_count, extensions.data());
    bool has_fd = false;
    for (const VkExtensionProperties& extension : extensions) {
      if (!std::strcmp(extension.extensionName,
                       VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME)) {
        has_fd = true;
      }
    }
    if (!has_fd) {
      XELOGI("Kiwi360 guest RAM: VK_KHR_external_memory_fd not supported");
      break;
    }

    float priority = 1.0f;
    VkDeviceQueueCreateInfo queue_info = {
        VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    queue_info.queueFamilyIndex = 0;
    queue_info.queueCount = 1;
    queue_info.pQueuePriorities = &priority;
    const char* device_extensions[] = {VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME};
    VkDeviceCreateInfo device_info = {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    device_info.queueCreateInfoCount = 1;
    device_info.pQueueCreateInfos = &queue_info;
    device_info.enabledExtensionCount = 1;
    device_info.ppEnabledExtensionNames = device_extensions;
    if (vkCreateDevice(physical_device, &device_info, nullptr, &device) !=
        VK_SUCCESS) {
      break;
    }
#define KIWI_DEVICE(name) \
  auto name = reinterpret_cast<PFN_##name>(vkGetDeviceProcAddr(device, #name))
    KIWI_DEVICE(vkDestroyDevice);
    KIWI_DEVICE(vkCreateBuffer);
    KIWI_DEVICE(vkDestroyBuffer);
    KIWI_DEVICE(vkGetBufferMemoryRequirements);
    KIWI_DEVICE(vkAllocateMemory);
    KIWI_DEVICE(vkFreeMemory);
    KIWI_DEVICE(vkGetMemoryFdKHR);
#undef KIWI_DEVICE

    do {
      // Same usage as the shared memory buffer that will import it.
      VkExternalMemoryBufferCreateInfo external_buffer = {
          VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO};
      external_buffer.handleTypes =
          VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;
      VkBufferCreateInfo buffer_info = {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
      buffer_info.pNext = &external_buffer;
      buffer_info.size = size;
      buffer_info.usage =
          VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT |
          VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
      VkBuffer buffer;
      if (vkCreateBuffer(device, &buffer_info, nullptr, &buffer) !=
          VK_SUCCESS) {
        break;
      }
      VkMemoryRequirements requirements;
      vkGetBufferMemoryRequirements(device, buffer, &requirements);

      // The CPU and the GPU must see the same bytes: host visible and
      // coherent. Prefer device local, then cached.
      VkPhysicalDeviceMemoryProperties memory_properties;
      vkGetPhysicalDeviceMemoryProperties(physical_device, &memory_properties);
      uint32_t memory_type = UINT32_MAX;
      int best_score = -1;
      for (uint32_t i = 0; i < memory_properties.memoryTypeCount; ++i) {
        if (!(requirements.memoryTypeBits & (1u << i))) {
          continue;
        }
        VkMemoryPropertyFlags flags =
            memory_properties.memoryTypes[i].propertyFlags;
        if (!(flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) ||
            !(flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
          continue;
        }
        int score = ((flags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) ? 2 : 0) +
                    ((flags & VK_MEMORY_PROPERTY_HOST_CACHED_BIT) ? 1 : 0);
        if (score > best_score) {
          best_score = score;
          memory_type = i;
        }
      }
      if (memory_type == UINT32_MAX) {
        XELOGI("Kiwi360 guest RAM: no host visible coherent memory type");
        vkDestroyBuffer(device, buffer, nullptr);
        break;
      }

      // Dedicated: Adreno only exports allocations dedicated to a buffer.
      VkMemoryDedicatedAllocateInfo dedicated = {
          VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
      dedicated.buffer = buffer;
      VkExportMemoryAllocateInfo export_info = {
          VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO};
      export_info.pNext = &dedicated;
      export_info.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;
      VkMemoryAllocateInfo allocate_info = {
          VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
      allocate_info.pNext = &export_info;
      allocate_info.allocationSize = requirements.size;
      allocate_info.memoryTypeIndex = memory_type;
      VkDeviceMemory memory;
      VkResult allocate =
          vkAllocateMemory(device, &allocate_info, nullptr, &memory);
      if (allocate != VK_SUCCESS) {
        XELOGW("Kiwi360 guest RAM: failed to allocate {} MB ({})", size >> 20,
               int(allocate));
        vkDestroyBuffer(device, buffer, nullptr);
        break;
      }
      VkMemoryGetFdInfoKHR fd_info = {VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR};
      fd_info.memory = memory;
      fd_info.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;
      int fd = -1;
      VkResult get_fd = vkGetMemoryFdKHR
                            ? vkGetMemoryFdKHR(device, &fd_info, &fd)
                            : VK_ERROR_EXTENSION_NOT_PRESENT;
      vkFreeMemory(device, memory, nullptr);
      vkDestroyBuffer(device, buffer, nullptr);
      if (get_fd != VK_SUCCESS || fd < 0) {
        XELOGW("Kiwi360 guest RAM: vkGetMemoryFdKHR failed ({})", int(get_fd));
        break;
      }
      out.fd = fd;
      out.allocation_size = requirements.size;
      out.memory_type_index = memory_type;
      out.size = size;
      result = true;
    } while (false);
    vkDestroyDevice(device, nullptr);
  } while (false);
  vkDestroyInstance(instance, nullptr);
  // The library stays loaded, the driver may keep state for the payload.
  return result;
}

}  // namespace

const GuestRamAllocation* GetOrCreateGpuGuestRam(uint64_t size) {
  if (!g_attempted) {
    g_attempted = true;
    if (cvars::kiwi_gpu_guest_ram && Allocate(size, g_allocation)) {
      XELOGI(
          "Kiwi360 guest RAM: {} MB allocated by the Vulkan driver (fd {}, "
          "memory type {})",
          size >> 20, g_allocation.fd, g_allocation.memory_type_index);
    }
  }
  return g_allocation.fd >= 0 && g_allocation.size >= size ? &g_allocation
                                                           : nullptr;
}

const GuestRamAllocation* GetGpuGuestRam() {
  return g_allocation.fd >= 0 ? &g_allocation : nullptr;
}

}  // namespace kiwi
}  // namespace xe

#else  // XE_PLATFORM_ANDROID

namespace xe {
namespace kiwi {

const GuestRamAllocation* GetOrCreateGpuGuestRam(uint64_t size) {
  return nullptr;
}

const GuestRamAllocation* GetGpuGuestRam() { return nullptr; }

}  // namespace kiwi
}  // namespace xe

#endif  // XE_PLATFORM_ANDROID
