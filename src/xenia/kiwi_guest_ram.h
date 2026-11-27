// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

// Kiwi360: guest physical RAM allocated by the Vulkan driver.
//
// On unified memory hosts without VK_EXT_external_memory_host (Adreno, Mali)
// Xenia can't import guest RAM into the GPU, so every CPU write is uploaded
// and every GPU write (resolve, memory export) read back - each copy ending
// the render pass on tiled GPUs. Instead, before the guest memory is mapped,
// the driver allocates the physical memory and exports it as a file
// descriptor. Memory maps the guest physical views from that descriptor, and
// the GPU shared memory buffer imports the same allocation: one copy of the
// data, seen by both.

#ifndef XENIA_KIWI_GUEST_RAM_H_
#define XENIA_KIWI_GUEST_RAM_H_

#include <cstdint>

namespace xe {
namespace kiwi {

struct GuestRamAllocation {
  // OPAQUE_FD of the allocation, owned here; -1 if there is none.
  int fd = -1;
  // What vkAllocateMemory used, which an import must repeat.
  uint64_t allocation_size = 0;
  uint32_t memory_type_index = 0;
  // The guest physical bytes (the start of the allocation).
  uint64_t size = 0;
};

// Allocates and exports `size` bytes once; later calls return the same
// allocation. Returns null if the host can't (or it's disabled by cvar).
const GuestRamAllocation* GetOrCreateGpuGuestRam(uint64_t size);

// The allocation, if created.
const GuestRamAllocation* GetGpuGuestRam();

}  // namespace kiwi
}  // namespace xe

#endif  // XENIA_KIWI_GUEST_RAM_H_
