// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

#include "xenia/gpu/vulkan/kiwi_depth_unorm24_probe.h"

#include <array>
#include <cstdint>
#include <string>

#include "third_party/fmt/include/fmt/format.h"
#include "xenia/base/logging.h"
#include "xenia/ui/vulkan/vulkan_util.h"

namespace xe {
namespace gpu {
namespace vulkan {

namespace {
#include "xenia/gpu/vulkan/kiwi_shaders/kiwi_depth_unorm24_probe_frag.h"
#include "xenia/gpu/vulkan/kiwi_shaders/kiwi_depth_unorm24_probe_vert.h"

// Both halves of the range, and values whose conversion differs between the
// 2^24 - 1 and 2^24 scales.
constexpr std::array<uint32_t, 8> kProbeValues = {
    0x000001, 0x3FFFFF, 0x7FFFFF, 0x800000,
    0x83B130, 0xAAAAAA, 0xC00000, 0xFFFFFE,
};

// Owns everything the probe creates, destroyed in reverse order.
class ProbeResources {
 public:
  explicit ProbeResources(const ui::vulkan::VulkanDevice* vulkan_device)
      : vulkan_device_(vulkan_device),
        dfn_(vulkan_device->functions()),
        device_(vulkan_device->device()) {}
  ~ProbeResources() {
    using ui::vulkan::util::DestroyAndNullHandle;
    DestroyAndNullHandle(dfn_.vkDestroyFence, device_, fence);
    DestroyAndNullHandle(dfn_.vkDestroyCommandPool, device_, command_pool);
    DestroyAndNullHandle(dfn_.vkDestroyPipeline, device_, pipeline);
    DestroyAndNullHandle(dfn_.vkDestroyPipelineLayout, device_,
                         pipeline_layout);
    DestroyAndNullHandle(dfn_.vkDestroyShaderModule, device_, fragment_shader);
    DestroyAndNullHandle(dfn_.vkDestroyShaderModule, device_, vertex_shader);
    DestroyAndNullHandle(dfn_.vkDestroyFramebuffer, device_, framebuffer);
    DestroyAndNullHandle(dfn_.vkDestroyRenderPass, device_, render_pass);
    DestroyAndNullHandle(dfn_.vkDestroyImageView, device_, image_view);
    DestroyAndNullHandle(dfn_.vkDestroyImage, device_, image);
    DestroyAndNullHandle(dfn_.vkFreeMemory, device_, image_memory);
    DestroyAndNullHandle(dfn_.vkDestroyBuffer, device_, buffer);
    DestroyAndNullHandle(dfn_.vkFreeMemory, device_, buffer_memory);
  }

  const ui::vulkan::VulkanDevice* vulkan_device_;
  const ui::vulkan::VulkanDevice::Functions& dfn_;
  VkDevice device_;

  VkImage image = VK_NULL_HANDLE;
  VkDeviceMemory image_memory = VK_NULL_HANDLE;
  VkImageView image_view = VK_NULL_HANDLE;
  VkBuffer buffer = VK_NULL_HANDLE;
  VkDeviceMemory buffer_memory = VK_NULL_HANDLE;
  uint32_t buffer_memory_type = UINT32_MAX;
  VkRenderPass render_pass = VK_NULL_HANDLE;
  VkFramebuffer framebuffer = VK_NULL_HANDLE;
  VkShaderModule vertex_shader = VK_NULL_HANDLE;
  VkShaderModule fragment_shader = VK_NULL_HANDLE;
  VkPipelineLayout pipeline_layout = VK_NULL_HANDLE;
  VkPipeline pipeline = VK_NULL_HANDLE;
  VkCommandPool command_pool = VK_NULL_HANDLE;
  VkFence fence = VK_NULL_HANDLE;
};

}  // namespace

std::optional<bool> KiwiProbeDepthUnorm24TransferExact(
    const ui::vulkan::VulkanDevice* vulkan_device) {
  const ui::vulkan::VulkanDevice::Functions& dfn = vulkan_device->functions();
  const VkDevice device = vulkan_device->device();
  constexpr VkFormat kFormat = VK_FORMAT_D24_UNORM_S8_UINT;
  constexpr uint32_t kWidth = uint32_t(kProbeValues.size());
  ProbeResources r(vulkan_device);

  VkImageCreateInfo image_create_info = {};
  image_create_info.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
  image_create_info.imageType = VK_IMAGE_TYPE_2D;
  image_create_info.format = kFormat;
  image_create_info.extent = {kWidth, 1, 1};
  image_create_info.mipLevels = 1;
  image_create_info.arrayLayers = 1;
  image_create_info.samples = VK_SAMPLE_COUNT_1_BIT;
  image_create_info.tiling = VK_IMAGE_TILING_OPTIMAL;
  image_create_info.usage = VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT |
                            VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
  image_create_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
  image_create_info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
  if (!ui::vulkan::util::CreateDedicatedAllocationImage(
          vulkan_device, image_create_info,
          ui::vulkan::util::MemoryPurpose::kDeviceLocal, r.image,
          r.image_memory)) {
    return std::nullopt;
  }
  VkImageViewCreateInfo view_create_info = {};
  view_create_info.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
  view_create_info.image = r.image;
  view_create_info.viewType = VK_IMAGE_VIEW_TYPE_2D;
  view_create_info.format = kFormat;
  view_create_info.subresourceRange = ui::vulkan::util::InitializeSubresourceRange(
      VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT);
  if (dfn.vkCreateImageView(device, &view_create_info, nullptr,
                            &r.image_view) != VK_SUCCESS) {
    return std::nullopt;
  }
  if (!ui::vulkan::util::CreateDedicatedAllocationBuffer(
          vulkan_device, sizeof(uint32_t) * kWidth,
          VK_BUFFER_USAGE_TRANSFER_DST_BIT,
          ui::vulkan::util::MemoryPurpose::kReadback, r.buffer,
          r.buffer_memory, &r.buffer_memory_type)) {
    return std::nullopt;
  }

  VkAttachmentDescription attachment = {};
  attachment.format = kFormat;
  attachment.samples = VK_SAMPLE_COUNT_1_BIT;
  attachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
  attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
  attachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
  attachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
  attachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
  attachment.finalLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
  VkAttachmentReference attachment_reference = {
      0, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL};
  VkSubpassDescription subpass = {};
  subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
  subpass.pDepthStencilAttachment = &attachment_reference;
  // Depth writes done before the copy that follows the render pass.
  VkSubpassDependency dependency = {};
  dependency.srcSubpass = 0;
  dependency.dstSubpass = VK_SUBPASS_EXTERNAL;
  dependency.srcStageMask = VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT |
                            VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
  dependency.dstStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT;
  dependency.srcAccessMask = VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
  dependency.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
  VkRenderPassCreateInfo render_pass_create_info = {};
  render_pass_create_info.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
  render_pass_create_info.attachmentCount = 1;
  render_pass_create_info.pAttachments = &attachment;
  render_pass_create_info.subpassCount = 1;
  render_pass_create_info.pSubpasses = &subpass;
  render_pass_create_info.dependencyCount = 1;
  render_pass_create_info.pDependencies = &dependency;
  if (dfn.vkCreateRenderPass(device, &render_pass_create_info, nullptr,
                             &r.render_pass) != VK_SUCCESS) {
    return std::nullopt;
  }
  VkFramebufferCreateInfo framebuffer_create_info = {};
  framebuffer_create_info.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
  framebuffer_create_info.renderPass = r.render_pass;
  framebuffer_create_info.attachmentCount = 1;
  framebuffer_create_info.pAttachments = &r.image_view;
  framebuffer_create_info.width = kWidth;
  framebuffer_create_info.height = 1;
  framebuffer_create_info.layers = 1;
  if (dfn.vkCreateFramebuffer(device, &framebuffer_create_info, nullptr,
                              &r.framebuffer) != VK_SUCCESS) {
    return std::nullopt;
  }

  r.vertex_shader = ui::vulkan::util::CreateShaderModule(
      vulkan_device, kiwi_depth_unorm24_probe_vert,
      sizeof(kiwi_depth_unorm24_probe_vert));
  r.fragment_shader = ui::vulkan::util::CreateShaderModule(
      vulkan_device, kiwi_depth_unorm24_probe_frag,
      sizeof(kiwi_depth_unorm24_probe_frag));
  if (r.vertex_shader == VK_NULL_HANDLE ||
      r.fragment_shader == VK_NULL_HANDLE) {
    return std::nullopt;
  }
  VkPushConstantRange push_constant_range = {
      VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(uint32_t) * kWidth};
  VkPipelineLayoutCreateInfo layout_create_info = {};
  layout_create_info.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
  layout_create_info.pushConstantRangeCount = 1;
  layout_create_info.pPushConstantRanges = &push_constant_range;
  if (dfn.vkCreatePipelineLayout(device, &layout_create_info, nullptr,
                                 &r.pipeline_layout) != VK_SUCCESS) {
    return std::nullopt;
  }

  VkPipelineShaderStageCreateInfo stages[2] = {};
  stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
  stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
  stages[0].module = r.vertex_shader;
  stages[0].pName = "main";
  stages[1] = stages[0];
  stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
  stages[1].module = r.fragment_shader;
  VkPipelineVertexInputStateCreateInfo vertex_input = {};
  vertex_input.sType =
      VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
  VkPipelineInputAssemblyStateCreateInfo input_assembly = {};
  input_assembly.sType =
      VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
  input_assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
  VkViewport viewport = {0.0f, 0.0f, float(kWidth), 1.0f, 0.0f, 1.0f};
  VkRect2D scissor = {{0, 0}, {kWidth, 1}};
  VkPipelineViewportStateCreateInfo viewport_state = {};
  viewport_state.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
  viewport_state.viewportCount = 1;
  viewport_state.pViewports = &viewport;
  viewport_state.scissorCount = 1;
  viewport_state.pScissors = &scissor;
  VkPipelineRasterizationStateCreateInfo rasterization = {};
  rasterization.sType =
      VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
  rasterization.polygonMode = VK_POLYGON_MODE_FILL;
  rasterization.cullMode = VK_CULL_MODE_NONE;
  rasterization.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
  rasterization.lineWidth = 1.0f;
  VkPipelineMultisampleStateCreateInfo multisample = {};
  multisample.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
  multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
  VkPipelineDepthStencilStateCreateInfo depth_stencil = {};
  depth_stencil.sType =
      VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO;
  depth_stencil.depthTestEnable = VK_TRUE;
  depth_stencil.depthWriteEnable = VK_TRUE;
  depth_stencil.depthCompareOp = VK_COMPARE_OP_ALWAYS;
  VkPipelineColorBlendStateCreateInfo color_blend = {};
  color_blend.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
  VkGraphicsPipelineCreateInfo pipeline_create_info = {};
  pipeline_create_info.sType =
      VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
  pipeline_create_info.stageCount = 2;
  pipeline_create_info.pStages = stages;
  pipeline_create_info.pVertexInputState = &vertex_input;
  pipeline_create_info.pInputAssemblyState = &input_assembly;
  pipeline_create_info.pViewportState = &viewport_state;
  pipeline_create_info.pRasterizationState = &rasterization;
  pipeline_create_info.pMultisampleState = &multisample;
  pipeline_create_info.pDepthStencilState = &depth_stencil;
  pipeline_create_info.pColorBlendState = &color_blend;
  pipeline_create_info.layout = r.pipeline_layout;
  pipeline_create_info.renderPass = r.render_pass;
  if (dfn.vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, 1,
                                    &pipeline_create_info, nullptr,
                                    &r.pipeline) != VK_SUCCESS) {
    return std::nullopt;
  }

  const uint32_t queue_family = vulkan_device->queue_family_graphics_compute();
  VkCommandPoolCreateInfo pool_create_info = {};
  pool_create_info.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
  pool_create_info.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
  pool_create_info.queueFamilyIndex = queue_family;
  if (dfn.vkCreateCommandPool(device, &pool_create_info, nullptr,
                              &r.command_pool) != VK_SUCCESS) {
    return std::nullopt;
  }
  VkCommandBufferAllocateInfo allocate_info = {};
  allocate_info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
  allocate_info.commandPool = r.command_pool;
  allocate_info.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
  allocate_info.commandBufferCount = 1;
  VkCommandBuffer command_buffer;
  if (dfn.vkAllocateCommandBuffers(device, &allocate_info, &command_buffer) !=
      VK_SUCCESS) {
    return std::nullopt;
  }
  VkCommandBufferBeginInfo begin_info = {};
  begin_info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
  begin_info.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
  dfn.vkBeginCommandBuffer(command_buffer, &begin_info);
  VkClearValue clear_value = {};
  clear_value.depthStencil = {0.0f, 0};
  VkRenderPassBeginInfo render_pass_begin = {};
  render_pass_begin.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
  render_pass_begin.renderPass = r.render_pass;
  render_pass_begin.framebuffer = r.framebuffer;
  render_pass_begin.renderArea = scissor;
  render_pass_begin.clearValueCount = 1;
  render_pass_begin.pClearValues = &clear_value;
  dfn.vkCmdBeginRenderPass(command_buffer, &render_pass_begin,
                           VK_SUBPASS_CONTENTS_INLINE);
  dfn.vkCmdBindPipeline(command_buffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                        r.pipeline);
  dfn.vkCmdPushConstants(command_buffer, r.pipeline_layout,
                         VK_SHADER_STAGE_FRAGMENT_BIT, 0,
                         sizeof(uint32_t) * kWidth, kProbeValues.data());
  dfn.vkCmdDraw(command_buffer, 3, 1, 0, 0);
  dfn.vkCmdEndRenderPass(command_buffer);
  VkBufferImageCopy copy_region = {};
  copy_region.imageSubresource.aspectMask = VK_IMAGE_ASPECT_DEPTH_BIT;
  copy_region.imageSubresource.layerCount = 1;
  copy_region.imageExtent = {kWidth, 1, 1};
  dfn.vkCmdCopyImageToBuffer(command_buffer, r.image,
                             VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, r.buffer, 1,
                             &copy_region);
  VkBufferMemoryBarrier host_barrier = {};
  host_barrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
  host_barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
  host_barrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
  host_barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  host_barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  host_barrier.buffer = r.buffer;
  host_barrier.size = VK_WHOLE_SIZE;
  dfn.vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                           VK_PIPELINE_STAGE_HOST_BIT, 0, 0, nullptr, 1,
                           &host_barrier, 0, nullptr);
  if (dfn.vkEndCommandBuffer(command_buffer) != VK_SUCCESS) {
    return std::nullopt;
  }

  VkFenceCreateInfo fence_create_info = {};
  fence_create_info.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
  if (dfn.vkCreateFence(device, &fence_create_info, nullptr, &r.fence) !=
      VK_SUCCESS) {
    return std::nullopt;
  }
  VkSubmitInfo submit_info = {};
  submit_info.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
  submit_info.commandBufferCount = 1;
  submit_info.pCommandBuffers = &command_buffer;
  {
    ui::vulkan::VulkanDevice::Queue::Acquisition queue =
        vulkan_device->AcquireQueue(queue_family, 0);
    if (dfn.vkQueueSubmit(queue.queue(), 1, &submit_info, r.fence) !=
        VK_SUCCESS) {
      return std::nullopt;
    }
  }
  if (dfn.vkWaitForFences(device, 1, &r.fence, VK_TRUE, UINT64_MAX) !=
      VK_SUCCESS) {
    return std::nullopt;
  }

  void* mapping;
  if (dfn.vkMapMemory(device, r.buffer_memory, 0, VK_WHOLE_SIZE, 0,
                      &mapping) != VK_SUCCESS) {
    return std::nullopt;
  }
  ui::vulkan::util::InvalidateMappedMemoryRange(vulkan_device, r.buffer_memory,
                                                r.buffer_memory_type);
  const uint32_t* stored = static_cast<const uint32_t*>(mapping);
  bool exact = true;
  std::string results;
  for (uint32_t i = 0; i < kWidth; ++i) {
    // The depth aspect of D24_UNORM_S8_UINT copies as X8_D24: the upper 8
    // bits are undefined.
    uint32_t value = stored[i] & 0xFFFFFF;
    exact &= value == kProbeValues[i];
    results += fmt::format(" {:06X}->{:06X}", kProbeValues[i], value);
  }
  dfn.vkUnmapMemory(device, r.buffer_memory);
  XELOGI("Kiwi360 D24S8 depth write probe:{} ({})", results,
         exact ? "exact" : "not exact");
  return exact;
}

}  // namespace vulkan
}  // namespace gpu
}  // namespace xe
