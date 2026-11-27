// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

// Kiwi360 measurement prototype: guest frame statistics, logged once per second
// as "Kiwi360 stats: ..." so adb logcat can collect them without any UI, plus
// a "Kiwi360 tex: ..." table of the most reloaded textures every 5 seconds.

#ifndef XENIA_GPU_KIWI_FRAME_STATS_H_
#define XENIA_GPU_KIWI_FRAME_STATS_H_

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>

#if defined(__ANDROID__)
#include <dlfcn.h>
#include <sys/system_properties.h>
#endif

#include "xenia/base/cvar.h"
#include "xenia/base/kiwi_cvar_set.h"
#include "xenia/base/logging.h"

DECLARE_bool(kiwi_auto_log_slow_frames);

namespace xe {
namespace gpu {

enum class KiwiTextureLoadReason : uint32_t {
  kNew,       // First load of a texture object.
  kGpuWrite,  // Its memory was written by the GPU (resolve, memexport).
  kCpuWrite,  // Its memory was written by the CPU (page watch fired).
  kCount,
};

struct KiwiTextureEntry {
  uint32_t base_address = 0;
  uint32_t width = 0;
  uint32_t height = 0;
  uint32_t format = 0;
  bool tiled = false;
  bool scaled_resolve = false;
  uint32_t loads = 0;
  uint64_t bytes = 0;
  std::array<uint32_t, size_t(KiwiTextureLoadReason::kCount)> reasons{};
};

// Per-window event counters. Only touched from the command processor thread.
struct KiwiFrameCounters {
  uint32_t texture_uploads = 0;
  uint32_t texture_blit_copies = 0;
  uint64_t texture_upload_bytes = 0;
  std::array<uint32_t, size_t(KiwiTextureLoadReason::kCount)> load_reasons{};
  uint32_t draws = 0;
  uint32_t copies = 0;  // Resolves (IssueCopy).
  uint32_t rt_transfer_batches = 0;
  uint32_t rt_transfers = 0;
  uint64_t rt_transfer_tiles = 0;  // EDRAM tiles (80x16 samples each).
  uint32_t render_passes = 0;      // Host render passes ended.
  // CPU invalidations whose guest bytes turned out unchanged (load skipped) or
  // changed (loaded).
  uint32_t texture_reloads_unchanged = 0;
  uint32_t texture_reloads_changed = 0;
  uint32_t submissions = 0;
};

inline KiwiFrameCounters& KiwiCounters();

// Live measurement knobs, re-read once per guest frame from Android system
// properties so adb can change them without restarting the title:
//   adb shell setprop debug.kiwi.skip_from 100   (skip draws [from, to) of
//   adb shell setprop debug.kiwi.skip_to 400      every frame; -1 = off)
//   adb shell setprop debug.kiwi.log_draws N      (log one frame's draws each
//                                                  time N changes)
struct KiwiKnobs {
  // Set to request a RenderDoc capture of the next frame.
  bool capture_requested = false;
  int32_t capture_generation = 0;
  uint32_t captures_triggered = 0;
  uint32_t stable_frames = 0;
  // debug.kiwi.skip_vs: comma separated vertex shader hashes whose draws are
  // dropped, to find which draws make a GPU stall or hang.
  std::array<uint64_t, 8> skip_vs = {};
  uint32_t skip_vs_count = 0;
  // debug.kiwi.keep_vs: comma separated vertex shader hashes drawn even when
  // skip_vs_mask / skip_vs_value matches them, to test one shader of a group.
  std::array<uint64_t, 5> keep_vs = {};
  uint32_t keep_vs_count = 0;
  // debug.kiwi.skip_vs_mask / skip_vs_value (hex): drop draws whose vertex
  // shader hash has (hash & mask) == value, to bisect shaders by hash bits.
  uint64_t skip_vs_mask = 0;
  uint64_t skip_vs_value = 0;
  int32_t skip_from = -1;
  int32_t skip_to = -1;
  int32_t log_draws_generation = 0;
  bool log_this_frame = false;
  // debug.kiwi.dump_edram: each time it changes, the next frame copies the
  // whole EDRAM (guest layout) after every transfer into a depth render target.
  int32_t dump_edram_generation = 0;
  bool dump_edram_this_frame = false;
  // debug.kiwi.dump_edram_from / _to: draws [from, to) of that frame also get
  // a copy before their render target update (the previous draw's results,
  // owners unchanged); transfers are only dumped inside the range. -1 = all.
  int32_t dump_edram_from = -1;
  int32_t dump_edram_to = -1;
  // debug.kiwi.dump_mem_addr (hex) / dump_mem_len (bytes): a guest memory
  // range copied from the GPU's view next to each "before draw" EDRAM dump.
  uint32_t dump_mem_addr = 0;
  uint32_t dump_mem_len = 0;
  uint32_t frame_draw_index = 0;
};

inline KiwiKnobs& Knobs() {
  static KiwiKnobs knobs;
  return knobs;
}

inline int32_t KiwiReadIntProperty(const char* name, int32_t default_value) {
#if defined(__ANDROID__)
  char value[PROP_VALUE_MAX];
  if (__system_property_get(name, value) > 0) {
    return int32_t(std::strtol(value, nullptr, 10));
  }
#endif
  return default_value;
}

// Once per guest frame.
inline void KiwiRefreshKnobs() {
  KiwiKnobs& knobs = Knobs();
  int32_t skip_from = KiwiReadIntProperty("debug.kiwi.skip_from", -1);
  int32_t skip_to = KiwiReadIntProperty("debug.kiwi.skip_to", -1);
  if (skip_from != knobs.skip_from || skip_to != knobs.skip_to) {
    XELOGI("Kiwi360 knobs: skipping draws [{}, {}) per frame", skip_from,
           skip_to);
  }
  knobs.skip_from = skip_from;
  knobs.skip_to = skip_to;
#if defined(__ANDROID__)
  {
    char value[PROP_VALUE_MAX] = {};
    __system_property_get("debug.kiwi.skip_vs", value);
    uint32_t count = 0;
    std::array<uint64_t, 8> hashes = {};
    const char* cursor = value;
    while (*cursor && count < hashes.size()) {
      char* end = nullptr;
      uint64_t hash = std::strtoull(cursor, &end, 16);
      if (end == cursor) {
        break;
      }
      hashes[count++] = hash;
      cursor = *end == ',' ? end + 1 : end;
    }
    if (count != knobs.skip_vs_count || hashes != knobs.skip_vs) {
      XELOGI("Kiwi360 knobs: skipping draws of {} vertex shaders", count);
    }
    knobs.skip_vs = hashes;
    knobs.skip_vs_count = count;
    char keep_text[PROP_VALUE_MAX] = {};
    __system_property_get("debug.kiwi.keep_vs", keep_text);
    uint32_t keep_count = 0;
    std::array<uint64_t, 5> keep = {};
    const char* keep_cursor = keep_text;
    while (*keep_cursor && keep_count < keep.size()) {
      char* end = nullptr;
      uint64_t hash = std::strtoull(keep_cursor, &end, 16);
      if (end == keep_cursor) {
        break;
      }
      keep[keep_count++] = hash;
      keep_cursor = *end == ',' ? end + 1 : end;
    }
    knobs.keep_vs = keep;
    knobs.keep_vs_count = keep_count;
    char mask_text[PROP_VALUE_MAX] = {};
    char value_text[PROP_VALUE_MAX] = {};
    __system_property_get("debug.kiwi.skip_vs_mask", mask_text);
    __system_property_get("debug.kiwi.skip_vs_value", value_text);
    uint64_t skip_mask = std::strtoull(mask_text, nullptr, 16);
    uint64_t skip_value = std::strtoull(value_text, nullptr, 16);
    if (skip_mask != knobs.skip_vs_mask || skip_value != knobs.skip_vs_value) {
      XELOGI("Kiwi360 knobs: skipping vertex shaders with hash & {:X} == {:X}",
             skip_mask, skip_value);
    }
    knobs.skip_vs_mask = skip_mask;
    knobs.skip_vs_value = skip_value;
  }
#endif
  int32_t log_generation = KiwiReadIntProperty("debug.kiwi.log_draws", 0);
  knobs.log_this_frame = log_generation != knobs.log_draws_generation;
  knobs.log_draws_generation = log_generation;
  int32_t dump_edram_generation =
      KiwiReadIntProperty("debug.kiwi.dump_edram", 0);
  knobs.dump_edram_this_frame =
      dump_edram_generation != knobs.dump_edram_generation;
  knobs.dump_edram_generation = dump_edram_generation;
  knobs.dump_edram_from = KiwiReadIntProperty("debug.kiwi.dump_edram_from", -1);
  knobs.dump_edram_to = KiwiReadIntProperty("debug.kiwi.dump_edram_to", -1);
#if defined(__ANDROID__)
  {
    char addr_text[PROP_VALUE_MAX] = {};
    __system_property_get("debug.kiwi.dump_mem_addr", addr_text);
    knobs.dump_mem_addr = uint32_t(std::strtoul(addr_text, nullptr, 16));
    knobs.dump_mem_len =
        uint32_t(KiwiReadIntProperty("debug.kiwi.dump_mem_len", 0));
  }
#endif
  if (knobs.dump_edram_this_frame) {
    XELOGI("Kiwi360 EDRAM dump: recording one frame");
  }
#if defined(__ANDROID__)
  {
    // debug.kiwi.set_cvar "name=value": sets any cvar live, each time the
    // property changes (for A/B tests without relaunching the game).
    static char last_assignment[PROP_VALUE_MAX] = {};
    char assignment[PROP_VALUE_MAX] = {};
    __system_property_get("debug.kiwi.set_cvar", assignment);
    if (std::strcmp(assignment, last_assignment) != 0) {
      std::memcpy(last_assignment, assignment, sizeof(assignment));
      const char* equals = std::strchr(assignment, '=');
      if (equals) {
        std::string name(assignment, size_t(equals - assignment));
        std::string value(equals + 1);
        XELOGI("Kiwi360 set_cvar: {} = {} ({})", name, value,
               xe::KiwiSetCvarFromString(name, value) ? "set" : "no such cvar");
      }
    }
  }
#endif
  int32_t capture_generation = KiwiReadIntProperty("debug.kiwi.capture", 0);
  if (capture_generation != knobs.capture_generation) {
    knobs.capture_generation = capture_generation;
    knobs.capture_requested = true;
    XELOGI("Kiwi360 capture: requested by property");
  }
  if (knobs.log_this_frame) {
    XELOGI("Kiwi360 draws: logging one frame");
  }
  knobs.frame_draw_index = 0;
}

// Counts the draw and returns its index within the guest frame.
inline uint32_t KiwiNoteDraw() {
  ++KiwiCounters().draws;
  return Knobs().frame_draw_index++;
}

inline bool KiwiShouldSkipDraw(uint32_t draw_index) {
  const KiwiKnobs& knobs = Knobs();
  return knobs.skip_from >= 0 && int32_t(draw_index) >= knobs.skip_from &&
         (knobs.skip_to < 0 || int32_t(draw_index) < knobs.skip_to);
}

inline bool KiwiShouldSkipVertexShader(uint64_t hash) {
  const KiwiKnobs& knobs = Knobs();
  if (knobs.skip_vs_mask && (hash & knobs.skip_vs_mask) == knobs.skip_vs_value) {
    bool keep = false;
    for (uint32_t i = 0; i < knobs.keep_vs_count; ++i) {
      keep |= knobs.keep_vs[i] == hash;
    }
    if (!keep) {
      return true;
    }
  }
  for (uint32_t i = 0; i < knobs.skip_vs_count; ++i) {
    if (knobs.skip_vs[i] == hash) {
      return true;
    }
  }
  return false;
}

inline bool KiwiLogDrawsThisFrame() { return Knobs().log_this_frame; }
inline bool KiwiDumpEdramThisFrame() { return Knobs().dump_edram_this_frame; }
// Index of the draw being processed (KiwiNoteDraw already advanced it).
inline uint32_t KiwiCurrentDrawIndex() {
  uint32_t next = Knobs().frame_draw_index;
  return next ? next - 1 : 0;
}
inline bool KiwiDumpEdramRangeSet() { return Knobs().dump_edram_from >= 0; }
inline uint32_t KiwiDumpMemAddress() { return Knobs().dump_mem_addr; }
inline uint32_t KiwiDumpMemLength() { return Knobs().dump_mem_len; }
inline bool KiwiDumpEdramForDraw() {
  const KiwiKnobs& knobs = Knobs();
  if (!knobs.dump_edram_this_frame) {
    return false;
  }
  int32_t draw = int32_t(KiwiCurrentDrawIndex());
  return (knobs.dump_edram_from < 0 || draw >= knobs.dump_edram_from) &&
         (knobs.dump_edram_to < 0 || draw < knobs.dump_edram_to);
}
inline void KiwiNoteCopy() { ++KiwiCounters().copies; }
// Who ends host render passes: return addresses, logged as offsets into the
// library every 5 s for llvm-symbolizer.
struct KiwiCallerTable {
  static constexpr size_t kCapacity = 32;
  std::array<const void*, kCapacity> callers{};
  std::array<uint32_t, kCapacity> counts{};
  size_t count = 0;
};

inline KiwiCallerTable& KiwiRenderPassEndCallers() {
  static KiwiCallerTable table;
  return table;
}

inline void KiwiNoteRenderPassEnd(const void* caller) {
  ++KiwiCounters().render_passes;
  KiwiCallerTable& table = KiwiRenderPassEndCallers();
  for (size_t i = 0; i < table.count; ++i) {
    if (table.callers[i] == caller) {
      ++table.counts[i];
      return;
    }
  }
  if (table.count < table.callers.size()) {
    table.callers[table.count] = caller;
    table.counts[table.count++] = 1;
  }
}

// Barriers that ended a render pass, by kind and masks.
struct KiwiBarrierKey {
  uint32_t kind;  // 0 shared memory buffer, 1 other buffer, 2 image.
  uint32_t src_stage, dst_stage, src_access, dst_access;
  uint32_t old_layout, new_layout;
  bool operator==(const KiwiBarrierKey& o) const {
    return kind == o.kind && src_stage == o.src_stage &&
           dst_stage == o.dst_stage && src_access == o.src_access &&
           dst_access == o.dst_access && old_layout == o.old_layout &&
           new_layout == o.new_layout;
  }
};

struct KiwiBarrierTable {
  static constexpr size_t kCapacity = 32;
  std::array<KiwiBarrierKey, kCapacity> keys{};
  std::array<uint32_t, kCapacity> counts{};
  size_t count = 0;
};

inline KiwiBarrierTable& KiwiPassBreakingBarriers() {
  static KiwiBarrierTable table;
  return table;
}

inline void KiwiNotePassBreakingBarrier(const KiwiBarrierKey& key) {
  KiwiBarrierTable& table = KiwiPassBreakingBarriers();
  for (size_t i = 0; i < table.count; ++i) {
    if (table.keys[i] == key) {
      ++table.counts[i];
      return;
    }
  }
  if (table.count < table.keys.size()) {
    table.keys[table.count] = key;
    table.counts[table.count++] = 1;
  }
}

inline void KiwiDumpPassBreakingBarriers() {
  KiwiBarrierTable& table = KiwiPassBreakingBarriers();
  for (size_t i = 0; i < table.count; ++i) {
    const KiwiBarrierKey& k = table.keys[i];
    XELOGI(
        "Kiwi360 barrier: kind={} src_stage={:X} dst_stage={:X} "
        "src_access={:X} dst_access={:X} layout={}->{} count={}",
        k.kind == 0 ? "shared" : (k.kind == 1 ? "buffer" : "image"),
        k.src_stage, k.dst_stage, k.src_access, k.dst_access, k.old_layout,
        k.new_layout, table.counts[i]);
  }
  table.count = 0;
}

inline void KiwiDumpRenderPassEndCallers() {
  KiwiCallerTable& table = KiwiRenderPassEndCallers();
  for (size_t i = 0; i < table.count; ++i) {
    uintptr_t offset = uintptr_t(table.callers[i]);
#if defined(__ANDROID__)
    Dl_info info;
    if (dladdr(table.callers[i], &info) && info.dli_fbase) {
      offset -= uintptr_t(info.dli_fbase);
    }
#endif
    XELOGI("Kiwi360 passend: caller=+{:X} count={}", offset, table.counts[i]);
  }
  table.count = 0;
}
inline void KiwiNoteSubmission() { ++KiwiCounters().submissions; }
inline void KiwiNoteRenderTargetTransferBatch() {
  ++KiwiCounters().rt_transfer_batches;
}
inline void KiwiNoteRenderTargetTransfer(uint32_t tiles) {
  KiwiFrameCounters& counters = KiwiCounters();
  ++counters.rt_transfers;
  counters.rt_transfer_tiles += tiles;
}

struct KiwiTextureTable {
  static constexpr size_t kCapacity = 128;
  std::array<KiwiTextureEntry, kCapacity> entries;
  size_t count = 0;
  uint32_t dropped = 0;
};

inline KiwiFrameCounters& KiwiCounters() {
  static KiwiFrameCounters counters;
  return counters;
}

inline KiwiTextureTable& KiwiTextures() {
  static KiwiTextureTable table;
  return table;
}

inline void KiwiNoteTextureUpload(uint64_t bytes, bool blit_copy) {
  KiwiFrameCounters& counters = KiwiCounters();
  ++counters.texture_uploads;
  counters.texture_upload_bytes += bytes;
  if (blit_copy) {
    ++counters.texture_blit_copies;
  }
}

inline void KiwiNoteTextureLoad(uint32_t base_address, uint32_t width,
                                uint32_t height, uint32_t format, bool tiled,
                                bool scaled_resolve, uint64_t guest_bytes,
                                KiwiTextureLoadReason reason) {
  ++KiwiCounters().load_reasons[size_t(reason)];
  KiwiTextureTable& table = KiwiTextures();
  KiwiTextureEntry* entry = nullptr;
  for (size_t i = 0; i < table.count; ++i) {
    KiwiTextureEntry& candidate = table.entries[i];
    if (candidate.base_address == base_address && candidate.width == width &&
        candidate.height == height && candidate.format == format) {
      entry = &candidate;
      break;
    }
  }
  if (!entry) {
    if (table.count >= table.entries.size()) {
      ++table.dropped;
      return;
    }
    entry = &table.entries[table.count++];
    *entry = KiwiTextureEntry();
    entry->base_address = base_address;
    entry->width = width;
    entry->height = height;
    entry->format = format;
    entry->tiled = tiled;
    entry->scaled_resolve = scaled_resolve;
  }
  ++entry->loads;
  entry->bytes += guest_bytes;
  ++entry->reasons[size_t(reason)];
}

inline void KiwiDumpTextureTable(double window_ms) {
  KiwiTextureTable& table = KiwiTextures();
  std::sort(table.entries.begin(), table.entries.begin() + table.count,
            [](const KiwiTextureEntry& a, const KiwiTextureEntry& b) {
              return a.loads > b.loads;
            });
  XELOGI("Kiwi360 tex: window_ms={:.0f} distinct={} dropped={}", window_ms,
         table.count, table.dropped);
  size_t shown = std::min(table.count, size_t(16));
  for (size_t i = 0; i < shown; ++i) {
    const KiwiTextureEntry& e = table.entries[i];
    XELOGI(
        "Kiwi360 tex: addr={:08X} {}x{} fmt={} tiled={} scaled={} loads={} "
        "kb={} new={} gpu={} cpu={}",
        e.base_address, e.width, e.height, e.format, e.tiled ? 1 : 0,
        e.scaled_resolve ? 1 : 0, e.loads, e.bytes / 1024,
        e.reasons[size_t(KiwiTextureLoadReason::kNew)],
        e.reasons[size_t(KiwiTextureLoadReason::kGpuWrite)],
        e.reasons[size_t(KiwiTextureLoadReason::kCpuWrite)]);
  }
  table.count = 0;
  table.dropped = 0;
}

// Read by the HUD from the UI thread (kiwi_perf_android.cc).
struct KiwiHudCounters {
  std::atomic<uint64_t> guest_frames{0};
  // Longest guest frame of the last completed 1 s window.
  std::atomic<uint64_t> worst_frame_ns{0};
};

inline KiwiHudCounters& KiwiHud() {
  static KiwiHudCounters hud;
  return hud;
}

// Call once per guest swap, from the command processor thread only.
inline void KiwiNoteGuestSwap() {
  KiwiHud().guest_frames.fetch_add(1, std::memory_order_relaxed);
  KiwiRefreshKnobs();
  using clock = std::chrono::steady_clock;
  static clock::time_point window_start = clock::now();
  static clock::time_point table_window_start = window_start;
  static clock::time_point last_swap = window_start;
  static uint32_t frames = 0;
  static double worst_ms = 0.0;
  static uint32_t slow_frames = 0;  // Longer than 1/25 s.

  const clock::time_point now = clock::now();
  const double frame_ms =
      std::chrono::duration<double, std::milli>(now - last_swap).count();
  last_swap = now;
  ++frames;
  worst_ms = std::max(worst_ms, frame_ms);
  if (frame_ms > 40.0) {
    ++slow_frames;
  }
  // Kiwi360: a frame far over budget gets the next one logged draw by draw
  // (at most every 3 s), to see what a GPU stall or hang is made of.
  {
    static auto kiwi_last_auto_log = clock::time_point();
    KiwiKnobs& knobs = Knobs();
    if (cvars::kiwi_auto_log_slow_frames && frame_ms > 150.0 &&
        now - kiwi_last_auto_log > std::chrono::seconds(3)) {
      kiwi_last_auto_log = now;
      knobs.log_this_frame = true;
      XELOGW("Kiwi360 draws: frame took {:.0f} ms, logging the next one",
             frame_ms);
      // A stall after a long smooth stretch (not a loading screen) gets a
      // RenderDoc capture too, when the layer is there - at most 3.
      if (knobs.stable_frames >= 300 && knobs.captures_triggered < 3) {
        knobs.capture_requested = true;
        XELOGW("Kiwi360 capture: requested after a {:.0f} ms frame", frame_ms);
      }
    }
    knobs.stable_frames = frame_ms < 50.0 ? knobs.stable_frames + 1 : 0;
  }

  const double window_ms =
      std::chrono::duration<double, std::milli>(now - window_start).count();
  if (window_ms >= 1000.0) {
    KiwiHud().worst_frame_ns.store(uint64_t(worst_ms * 1e6),
                                   std::memory_order_relaxed);
    KiwiFrameCounters& counters = KiwiCounters();
    XELOGI(
        "Kiwi360 stats: fps={:.1f} avg_ms={:.2f} worst_ms={:.2f} slow={} "
        "tex_uploads={} tex_blit_copies={} tex_upload_kb={} "
        "tex_new={} tex_gpu={} tex_cpu={} tex_cpu_unchanged={} "
        "tex_cpu_changed={}",
        frames * 1000.0 / window_ms, window_ms / frames, worst_ms, slow_frames,
        counters.texture_uploads, counters.texture_blit_copies,
        counters.texture_upload_bytes / 1024,
        counters.load_reasons[size_t(KiwiTextureLoadReason::kNew)],
        counters.load_reasons[size_t(KiwiTextureLoadReason::kGpuWrite)],
        counters.load_reasons[size_t(KiwiTextureLoadReason::kCpuWrite)],
        counters.texture_reloads_unchanged, counters.texture_reloads_changed);
    XELOGI(
        "Kiwi360 gpu: draws/f={:.0f} copies/f={:.1f} xfer_batches/f={:.1f} "
        "xfers/f={:.1f} xfer_tiles/f={:.0f} passes/f={:.1f} submits/f={:.1f}",
        counters.draws / double(frames), counters.copies / double(frames),
        counters.rt_transfer_batches / double(frames),
        counters.rt_transfers / double(frames),
        counters.rt_transfer_tiles / double(frames),
        counters.render_passes / double(frames),
        counters.submissions / double(frames));
    counters = KiwiFrameCounters();
    window_start = now;
    frames = 0;
    worst_ms = 0.0;
    slow_frames = 0;
  }

  const double table_window_ms =
      std::chrono::duration<double, std::milli>(now - table_window_start)
          .count();
  if (table_window_ms >= 5000.0) {
    KiwiDumpTextureTable(table_window_ms);
    KiwiDumpRenderPassEndCallers();
    KiwiDumpPassBreakingBarriers();
    table_window_start = now;
  }
}

}  // namespace gpu
}  // namespace xe

#endif  // XENIA_GPU_KIWI_FRAME_STATS_H_
