// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

#ifndef XENIA_UI_VULKAN_KIWI_PRESENT_ROTATION_H_
#define XENIA_UI_VULKAN_KIWI_PRESENT_ROTATION_H_

#include <cstdint>
#include <memory>

#include "xenia/ui/vulkan/vulkan_api.h"
#include "xenia/ui/vulkan/vulkan_device.h"

namespace xe {
namespace ui {
namespace vulkan {

// Present pre-rotation. When the display is mounted rotated against the window
// (a landscape game on a portrait panel), the compositor has to turn every
// frame. Display hardware can only rotate some buffer layouts while scanning
// out (on Snapdragon, UBWC-compressed ones), so for others, such as the linear
// swapchain images of Turnip, the whole screen falls back to GPU composition
// on every refresh. That costs bandwidth, and it preempts the emulator's GPU
// work each time.
//
// Instead, the presenter paints in the logical orientation into a source image
// of this class, then this draws it turned into a swapchain created in the
// physical orientation with a matching preTransform, so the compositor can scan
// it out unchanged.
//
// Everything is used from the painting thread. The source image is reused by
// every frame: its render pass orders the next frame's writes after the
// previous rotation pass in queue order.
class KiwiPresentRotation {
 public:
  static std::unique_ptr<KiwiPresentRotation> Create(
      const VulkanDevice* vulkan_device);
  ~KiwiPresentRotation();

  // Quarter turns clockwise for a preTransform, or 0 if it is not a pure
  // rotation (identity, mirroring, inherit).
  static uint32_t QuarterTurns(VkSurfaceTransformFlagBitsKHR transform);

  // (Re)creates the source for painting into, compatible with a swapchain
  // render pass of this format and load operation, and the rotation pipeline
  // for that swapchain render pass. The previous source and pipeline must not
  // be in use on the GPU anymore. Returns false if anything failed, leaving
  // nothing prepared.
  bool Prepare(VkFormat format, bool clear_load_op, VkExtent2D logical_extent,
               VkRenderPass swapchain_render_pass);
  // Drops the source and the pipeline, which must not be in use on the GPU.
  void Release();
  bool prepared() const { return source_framebuffer_ != VK_NULL_HANDLE; }

  VkRenderPass source_render_pass() const { return source_render_pass_; }
  VkFramebuffer source_framebuffer() const { return source_framebuffer_; }

  // Records the rotation pass into the swapchain image, after the source pass
  // has ended.
  void Draw(VkCommandBuffer command_buffer, VkRenderPass swapchain_render_pass,
            VkFramebuffer swapchain_framebuffer, VkExtent2D physical_extent,
            uint32_t quarter_turns) const;

 private:
  explicit KiwiPresentRotation(const VulkanDevice* vulkan_device)
      : vulkan_device_(vulkan_device) {}
  bool Initialize();

  const VulkanDevice* vulkan_device_;

  // Connection-independent.
  VkShaderModule vertex_shader_ = VK_NULL_HANDLE;
  VkShaderModule fragment_shader_ = VK_NULL_HANDLE;
  VkSampler sampler_ = VK_NULL_HANDLE;
  VkDescriptorSetLayout descriptor_set_layout_ = VK_NULL_HANDLE;
  VkPipelineLayout pipeline_layout_ = VK_NULL_HANDLE;
  VkDescriptorPool descriptor_pool_ = VK_NULL_HANDLE;
  VkDescriptorSet descriptor_set_ = VK_NULL_HANDLE;

  // Prepared for the current swapchain.
  VkFormat source_format_ = VK_FORMAT_UNDEFINED;
  bool source_clear_load_op_ = false;
  VkRenderPass source_render_pass_ = VK_NULL_HANDLE;
  VkExtent2D source_extent_ = {};
  VkImage source_image_ = VK_NULL_HANDLE;
  VkDeviceMemory source_memory_ = VK_NULL_HANDLE;
  VkImageView source_view_ = VK_NULL_HANDLE;
  VkFramebuffer source_framebuffer_ = VK_NULL_HANDLE;
  VkRenderPass pipeline_render_pass_ = VK_NULL_HANDLE;
  VkPipeline pipeline_ = VK_NULL_HANDLE;
};

}  // namespace vulkan
}  // namespace ui
}  // namespace xe

#endif  // XENIA_UI_VULKAN_KIWI_PRESENT_ROTATION_H_
