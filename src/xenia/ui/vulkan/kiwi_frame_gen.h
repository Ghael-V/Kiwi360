// SPDX-FileCopyrightText: Copyright 2026 Ghael-V (Lemon FG, by its author)
// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

#ifndef XENIA_UI_VULKAN_KIWI_FRAME_GEN_H_
#define XENIA_UI_VULKAN_KIWI_FRAME_GEN_H_

#include <array>
#include <atomic>
#include <cstdint>
#include <initializer_list>
#include <memory>
#include <vector>

#include "xenia/ui/kiwi_frame_gen_stats.h"
#include "xenia/ui/vulkan/vulkan_device.h"

namespace xe {
namespace ui {
namespace vulkan {

// Lemon FG: frame generation between two presented frames, from their colors
// only (no depth or motion vectors from the game).
//
// For every real frame: its luma at a fraction of the resolution, as a 3-level
// pyramid; block motion from the previous frame (A) to this one (C), coarse to
// fine, each block trying its neighbours' and the previous pair's vectors
// (3DRS); a mask of what stays still on screen (HUD). For every generated frame
// at time t: that motion projected to t, and A and C sampled along it and
// mixed, still content kept in place.
//
// Works on its own RGBA8 images: frames come in and generated frames go out
// with blits, so the presentation format and its storage support don't matter.
class KiwiFrameGenChain {
 public:
  static constexpr uint32_t kLevels = 3;
  // Generated frames that can be in use at once (presented while the next one
  // is generated).
  static constexpr uint32_t kOutputs = 2;

  static bool IsSupported(const VulkanDevice* vulkan_device);

  // search_reduction: motion is searched at 1/2, 1/4 or 1/8 of the resolution.
  static std::unique_ptr<KiwiFrameGenChain> Create(
      const VulkanDevice* vulkan_device, VkExtent2D extent,
      uint32_t search_reduction);
  ~KiwiFrameGenChain();

  KiwiFrameGenChain(const KiwiFrameGenChain&) = delete;
  KiwiFrameGenChain& operator=(const KiwiFrameGenChain&) = delete;

  VkExtent2D extent() const { return extent_; }
  uint32_t search_reduction() const { return search_reduction_; }

  // Copies a new real frame in. The source must be in
  // VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL with its writes visible to transfers,
  // and have the chain's extent.
  void Ingest(VkCommandBuffer command_buffer, VkImage source);
  // Motion between the last two ingested frames. False if there is only one
  // frame yet (nothing can be generated).
  bool Analyze(VkCommandBuffer command_buffer);
  // Generates the frame at t (0 = the previous frame, 1 = the latest one) into
  // output image `output`, left in VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL with
  // its writes visible to transfers. Needs a successful Analyze since the last
  // Ingest.
  VkImage Generate(VkCommandBuffer command_buffer, float t, uint32_t output);

 private:
  struct Image {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkExtent2D extent = {};
    VkImageLayout layout = VK_IMAGE_LAYOUT_UNDEFINED;
  };

  struct Pass {
    VkShaderModule module = VK_NULL_HANDLE;
    VkDescriptorSetLayout set_layout = VK_NULL_HANDLE;
    VkPipelineLayout pipeline_layout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    uint32_t push_size = 0;
  };

  // One recorded step of the motion search: a descriptor set and its push
  // constants.
  struct SearchStep {
    VkDescriptorSet set = VK_NULL_HANDLE;
    std::array<uint32_t, 11> push = {};
    VkExtent2D grid = {};
  };

  // Coarsest level: exhaustive + 2 passes; middle levels: 2 passes; finest: 1.
  static constexpr uint32_t kSearchSteps = 3 + 2 * (kLevels - 2) + 1;
  static constexpr uint32_t kStillPasses = 3;
  // still_masks_ entry the last still pass writes.
  static constexpr uint32_t kStillFinal = (kStillPasses - 1) % 2;

  explicit KiwiFrameGenChain(const VulkanDevice* vulkan_device)
      : vulkan_device_(vulkan_device) {}
  bool Initialize(VkExtent2D extent, uint32_t search_reduction);

  bool CreateImage(Image& image, VkExtent2D extent, VkFormat format);
  void DestroyImage(Image& image);
  bool CreatePass(Pass& pass, const uint32_t* code, size_t code_size,
                  std::initializer_list<VkDescriptorType> bindings,
                  uint32_t push_size);
  void DestroyPass(Pass& pass);
  VkDescriptorSet AllocateSet(const Pass& pass);

  class DescriptorWriter;

  void Run(VkCommandBuffer command_buffer, const Pass& pass,
           VkDescriptorSet set, const void* push, uint32_t groups_x,
           uint32_t groups_y);
  void InitializeImages(VkCommandBuffer command_buffer);
  void BuildPyramid(VkCommandBuffer command_buffer, uint32_t slot);

  const VulkanDevice* vulkan_device_;
  bool ok_ = true;

  VkExtent2D extent_ = {};
  uint32_t search_reduction_ = 4;
  VkExtent2D still_size_ = {};
  std::array<VkExtent2D, kLevels> level_size_ = {};
  std::array<VkExtent2D, kLevels> grid_ = {};

  VkDescriptorPool descriptor_pool_ = VK_NULL_HANDLE;
  VkSampler sampler_ = VK_NULL_HANDLE;

  Pass luma_pass_;
  Pass search_pass_;
  Pass median_pass_;
  Pass project_pass_;
  Pass still_pass_;
  Pass generate_pass_;

  std::array<Image, 2> frames_;
  std::array<std::array<Image, kLevels>, 2> pyramid_;
  // Ping-pong search fields per level.
  std::array<std::array<Image, 2>, kLevels> fields_;
  // Final, median-filtered flow of the pair ending at each frame slot. Also the
  // temporal candidates of the next pair.
  std::array<Image, 2> flows_;
  Image offsets_;
  Image offsets_filtered_;
  // Ping-pong still masks; the last pass leaves the final one in
  // still_masks_[kStillFinal].
  std::array<Image, 2> still_masks_;
  std::array<Image, kOutputs> outputs_;

  std::array<VkDescriptorSet, 2> luma_sets_ = {};
  std::array<std::array<VkDescriptorSet, kLevels - 1>, 2> down_sets_ = {};
  std::array<std::array<SearchStep, kSearchSteps>, 2> search_steps_ = {};
  std::array<VkDescriptorSet, 2> flow_median_sets_ = {};
  std::array<std::array<VkDescriptorSet, kStillPasses>, 2> still_sets_ = {};
  std::array<VkDescriptorSet, 2> project_sets_ = {};
  VkDescriptorSet offsets_median_set_ = VK_NULL_HANDLE;
  // [slot][output]
  std::array<std::array<VkDescriptorSet, kOutputs>, 2> generate_sets_ = {};

  // Real frames ingested; the latest one is in frames_[frame_count_ % 2].
  uint64_t frame_count_ = 0;
  uint64_t last_pyramid_ = UINT64_MAX;
  uint64_t last_flow_ = UINT64_MAX;
  bool initialized_ = false;
};

}  // namespace vulkan
}  // namespace ui
}  // namespace xe

#endif  // XENIA_UI_VULKAN_KIWI_FRAME_GEN_H_
