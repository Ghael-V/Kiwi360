// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

#ifndef XENIA_UI_VULKAN_KIWI_POST_EFFECTS_H_
#define XENIA_UI_VULKAN_KIWI_POST_EFFECTS_H_

#include <array>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "xenia/ui/vulkan/vulkan_device.h"

namespace xe {
namespace ui {
namespace vulkan {

// The user's chain of image effects (kiwi_post_effects), run on every
// presented frame, generated ones included, at the guest output resolution
// before the presenter's own scaling and anti-aliasing. One compute pass per
// enabled effect, ping-ponging between two RGBA8 images.
class KiwiPostEffects {
 public:
  static constexpr uint32_t kMaxEffects = 16;
  static constexpr uint32_t kParams = 8;

  struct Effect {
    int32_t id = -1;
    std::array<float, kParams> params = {};
  };

  // The enabled effects of a chain in the frontend's encoding:
  // "id:v1,v2;id2:...", "!" before the id of a disabled effect. Unknown ids
  // and entries past kMaxEffects are ignored; missing values are 0 (the
  // frontend always writes them all).
  static std::vector<Effect> Parse(const std::string& chain);

  static std::unique_ptr<KiwiPostEffects> Create(
      const VulkanDevice* vulkan_device);
  ~KiwiPostEffects();

  KiwiPostEffects(const KiwiPostEffects&) = delete;
  KiwiPostEffects& operator=(const KiwiPostEffects&) = delete;

  VkExtent2D extent() const { return extent_; }
  // (Re)creates the work images; the caller must have awaited any GPU use of
  // the old ones.
  bool Resize(VkExtent2D extent);

  // Runs [effects] on [source] (the chain's extent, in
  // VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL with its writes visible to
  // transfers). Returns the result, in VK_IMAGE_LAYOUT_GENERAL with its writes
  // visible to transfers.
  VkImage Apply(VkCommandBuffer command_buffer, VkImage source,
                const std::vector<Effect>& effects, float seconds,
                uint32_t frame);

 private:
  struct Image {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
  };

  explicit KiwiPostEffects(const VulkanDevice* vulkan_device)
      : vulkan_device_(vulkan_device) {}
  bool Initialize();
  bool CreateImage(Image& image);
  void DestroyImage(Image& image);
  void DestroyImages();

  const VulkanDevice* vulkan_device_;
  VkExtent2D extent_ = {};
  // The images are in VK_IMAGE_LAYOUT_GENERAL once initialized.
  bool images_initialized_ = false;

  VkSampler sampler_ = VK_NULL_HANDLE;
  VkDescriptorPool descriptor_pool_ = VK_NULL_HANDLE;
  VkDescriptorSetLayout set_layout_ = VK_NULL_HANDLE;
  VkPipelineLayout pipeline_layout_ = VK_NULL_HANDLE;
  VkShaderModule module_ = VK_NULL_HANDLE;
  VkPipeline pipeline_ = VK_NULL_HANDLE;

  std::array<Image, 2> images_;
  // sets_[i]: read images_[i], write images_[1 - i].
  std::array<VkDescriptorSet, 2> sets_ = {};
};

}  // namespace vulkan
}  // namespace ui
}  // namespace xe

#endif  // XENIA_UI_VULKAN_KIWI_POST_EFFECTS_H_
