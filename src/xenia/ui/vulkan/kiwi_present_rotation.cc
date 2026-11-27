// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

#include "xenia/ui/vulkan/kiwi_present_rotation.h"

#include "xenia/base/logging.h"
#include "xenia/ui/vulkan/vulkan_util.h"

namespace xe {
namespace ui {
namespace vulkan {

namespace {
#include "xenia/ui/vulkan/kiwi_rotate/kiwi_rotate_ps.h"
#include "xenia/ui/vulkan/kiwi_rotate/kiwi_rotate_vs.h"
}  // namespace

std::unique_ptr<KiwiPresentRotation> KiwiPresentRotation::Create(
    const VulkanDevice* const vulkan_device) {
  std::unique_ptr<KiwiPresentRotation> rotation(
      new KiwiPresentRotation(vulkan_device));
  if (!rotation->Initialize()) {
    return nullptr;
  }
  return rotation;
}

KiwiPresentRotation::~KiwiPresentRotation() {
  Release();
  const VulkanDevice::Functions& dfn = vulkan_device_->functions();
  const VkDevice device = vulkan_device_->device();
  util::DestroyAndNullHandle(dfn.vkDestroyRenderPass, device,
                             source_render_pass_);
  util::DestroyAndNullHandle(dfn.vkDestroyDescriptorPool, device,
                             descriptor_pool_);
  util::DestroyAndNullHandle(dfn.vkDestroyPipelineLayout, device,
                             pipeline_layout_);
  util::DestroyAndNullHandle(dfn.vkDestroyDescriptorSetLayout, device,
                             descriptor_set_layout_);
  util::DestroyAndNullHandle(dfn.vkDestroySampler, device, sampler_);
  util::DestroyAndNullHandle(dfn.vkDestroyShaderModule, device,
                             fragment_shader_);
  util::DestroyAndNullHandle(dfn.vkDestroyShaderModule, device,
                             vertex_shader_);
}

uint32_t KiwiPresentRotation::QuarterTurns(
    const VkSurfaceTransformFlagBitsKHR transform) {
  switch (transform) {
    case VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR:
      return 1;
    case VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR:
      return 2;
    case VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR:
      return 3;
    default:
      return 0;
  }
}

bool KiwiPresentRotation::Initialize() {
  const VulkanDevice::Functions& dfn = vulkan_device_->functions();
  const VkDevice device = vulkan_device_->device();

  vertex_shader_ = util::CreateShaderModule(vulkan_device_, kiwi_rotate_vs,
                                            sizeof(kiwi_rotate_vs));
  fragment_shader_ = util::CreateShaderModule(vulkan_device_, kiwi_rotate_ps,
                                              sizeof(kiwi_rotate_ps));
  if (vertex_shader_ == VK_NULL_HANDLE || fragment_shader_ == VK_NULL_HANDLE) {
    XELOGE("KiwiPresentRotation: Failed to create the shader modules");
    return false;
  }

  VkSamplerCreateInfo sampler_create_info = {
      VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
  sampler_create_info.magFilter = VK_FILTER_NEAREST;
  sampler_create_info.minFilter = VK_FILTER_NEAREST;
  sampler_create_info.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
  sampler_create_info.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
  sampler_create_info.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
  sampler_create_info.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
  sampler_create_info.maxLod = 0.0f;
  if (dfn.vkCreateSampler(device, &sampler_create_info, nullptr, &sampler_) !=
      VK_SUCCESS) {
    XELOGE("KiwiPresentRotation: Failed to create the sampler");
    return false;
  }

  VkDescriptorSetLayoutBinding binding = {};
  binding.binding = 0;
  binding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
  binding.descriptorCount = 1;
  binding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
  binding.pImmutableSamplers = &sampler_;
  VkDescriptorSetLayoutCreateInfo set_layout_create_info = {
      VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
  set_layout_create_info.bindingCount = 1;
  set_layout_create_info.pBindings = &binding;
  if (dfn.vkCreateDescriptorSetLayout(device, &set_layout_create_info, nullptr,
                                      &descriptor_set_layout_) != VK_SUCCESS) {
    XELOGE("KiwiPresentRotation: Failed to create the descriptor set layout");
    return false;
  }

  VkPushConstantRange push_constant_range;
  push_constant_range.stageFlags = VK_SHADER_STAGE_VERTEX_BIT;
  push_constant_range.offset = 0;
  push_constant_range.size = sizeof(uint32_t);
  VkPipelineLayoutCreateInfo pipeline_layout_create_info = {
      VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
  pipeline_layout_create_info.setLayoutCount = 1;
  pipeline_layout_create_info.pSetLayouts = &descriptor_set_layout_;
  pipeline_layout_create_info.pushConstantRangeCount = 1;
  pipeline_layout_create_info.pPushConstantRanges = &push_constant_range;
  if (dfn.vkCreatePipelineLayout(device, &pipeline_layout_create_info, nullptr,
                                 &pipeline_layout_) != VK_SUCCESS) {
    XELOGE("KiwiPresentRotation: Failed to create the pipeline layout");
    return false;
  }

  VkDescriptorPoolSize pool_size;
  pool_size.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
  pool_size.descriptorCount = 1;
  VkDescriptorPoolCreateInfo pool_create_info = {
      VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
  pool_create_info.maxSets = 1;
  pool_create_info.poolSizeCount = 1;
  pool_create_info.pPoolSizes = &pool_size;
  if (dfn.vkCreateDescriptorPool(device, &pool_create_info, nullptr,
                                 &descriptor_pool_) != VK_SUCCESS) {
    XELOGE("KiwiPresentRotation: Failed to create the descriptor pool");
    return false;
  }
  VkDescriptorSetAllocateInfo set_allocate_info = {
      VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
  set_allocate_info.descriptorPool = descriptor_pool_;
  set_allocate_info.descriptorSetCount = 1;
  set_allocate_info.pSetLayouts = &descriptor_set_layout_;
  if (dfn.vkAllocateDescriptorSets(device, &set_allocate_info,
                                   &descriptor_set_) != VK_SUCCESS) {
    XELOGE("KiwiPresentRotation: Failed to allocate the descriptor set");
    return false;
  }
  return true;
}

void KiwiPresentRotation::Release() {
  const VulkanDevice::Functions& dfn = vulkan_device_->functions();
  const VkDevice device = vulkan_device_->device();
  util::DestroyAndNullHandle(dfn.vkDestroyPipeline, device, pipeline_);
  pipeline_render_pass_ = VK_NULL_HANDLE;
  util::DestroyAndNullHandle(dfn.vkDestroyFramebuffer, device,
                             source_framebuffer_);
  util::DestroyAndNullHandle(dfn.vkDestroyImageView, device, source_view_);
  util::DestroyAndNullHandle(dfn.vkDestroyImage, device, source_image_);
  util::DestroyAndNullHandle(dfn.vkFreeMemory, device, source_memory_);
  source_extent_ = {};
}

bool KiwiPresentRotation::Prepare(const VkFormat format,
                                  const bool clear_load_op,
                                  const VkExtent2D logical_extent,
                                  const VkRenderPass swapchain_render_pass) {
  Release();
  const VulkanDevice::Functions& dfn = vulkan_device_->functions();
  const VkDevice device = vulkan_device_->device();

  // The source pass must be compatible with the swapchain render pass (same
  // format and sample count), so pipelines created for the swapchain, the
  // guest output and the UI ones, can draw into it. Only the layouts differ.
  if (source_render_pass_ != VK_NULL_HANDLE &&
      (source_format_ != format || source_clear_load_op_ != clear_load_op)) {
    util::DestroyAndNullHandle(dfn.vkDestroyRenderPass, device,
                               source_render_pass_);
  }
  if (source_render_pass_ == VK_NULL_HANDLE) {
    VkAttachmentDescription attachment;
    attachment.flags = 0;
    attachment.format = format;
    attachment.samples = VK_SAMPLE_COUNT_1_BIT;
    attachment.loadOp = clear_load_op ? VK_ATTACHMENT_LOAD_OP_CLEAR
                                      : VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    attachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    attachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    attachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    attachment.finalLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    VkAttachmentReference color_attachment;
    color_attachment.attachment = 0;
    color_attachment.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    VkSubpassDescription subpass = {};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 1;
    subpass.pColorAttachments = &color_attachment;
    VkSubpassDependency dependencies[2];
    // After the previous frame's rotation pass has read the source.
    dependencies[0].srcSubpass = VK_SUBPASS_EXTERNAL;
    dependencies[0].dstSubpass = 0;
    dependencies[0].srcStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT |
                                   VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dependencies[0].dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dependencies[0].srcAccessMask = 0;
    // UI drawing blends, so reads are included.
    dependencies[0].dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_READ_BIT |
                                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    dependencies[0].dependencyFlags = 0;
    // Before this frame's rotation pass reads it.
    dependencies[1].srcSubpass = 0;
    dependencies[1].dstSubpass = VK_SUBPASS_EXTERNAL;
    dependencies[1].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dependencies[1].dstStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
    dependencies[1].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    dependencies[1].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    dependencies[1].dependencyFlags = 0;
    VkRenderPassCreateInfo render_pass_create_info = {
        VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
    render_pass_create_info.attachmentCount = 1;
    render_pass_create_info.pAttachments = &attachment;
    render_pass_create_info.subpassCount = 1;
    render_pass_create_info.pSubpasses = &subpass;
    render_pass_create_info.dependencyCount = 2;
    render_pass_create_info.pDependencies = dependencies;
    if (dfn.vkCreateRenderPass(device, &render_pass_create_info, nullptr,
                               &source_render_pass_) != VK_SUCCESS) {
      XELOGE("KiwiPresentRotation: Failed to create the source render pass");
      source_render_pass_ = VK_NULL_HANDLE;
      return false;
    }
    source_format_ = format;
    source_clear_load_op_ = clear_load_op;
  }

  VkImageCreateInfo image_create_info = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
  image_create_info.imageType = VK_IMAGE_TYPE_2D;
  image_create_info.format = format;
  image_create_info.extent.width = logical_extent.width;
  image_create_info.extent.height = logical_extent.height;
  image_create_info.extent.depth = 1;
  image_create_info.mipLevels = 1;
  image_create_info.arrayLayers = 1;
  image_create_info.samples = VK_SAMPLE_COUNT_1_BIT;
  image_create_info.tiling = VK_IMAGE_TILING_OPTIMAL;
  image_create_info.usage =
      VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
  image_create_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
  image_create_info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
  if (!util::CreateDedicatedAllocationImage(
          vulkan_device_, image_create_info, util::MemoryPurpose::kDeviceLocal,
          source_image_, source_memory_)) {
    XELOGE("KiwiPresentRotation: Failed to create the {}x{} source image",
           logical_extent.width, logical_extent.height);
    source_image_ = VK_NULL_HANDLE;
    source_memory_ = VK_NULL_HANDLE;
    return false;
  }

  VkImageViewCreateInfo view_create_info = {
      VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
  view_create_info.image = source_image_;
  view_create_info.viewType = VK_IMAGE_VIEW_TYPE_2D;
  view_create_info.format = format;
  view_create_info.components = {
      VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY,
      VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY};
  view_create_info.subresourceRange =
      util::InitializeSubresourceRange(VK_IMAGE_ASPECT_COLOR_BIT);
  if (dfn.vkCreateImageView(device, &view_create_info, nullptr,
                            &source_view_) != VK_SUCCESS) {
    XELOGE("KiwiPresentRotation: Failed to create the source image view");
    source_view_ = VK_NULL_HANDLE;
    Release();
    return false;
  }

  VkDescriptorImageInfo descriptor_image_info;
  descriptor_image_info.sampler = VK_NULL_HANDLE;
  descriptor_image_info.imageView = source_view_;
  descriptor_image_info.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
  VkWriteDescriptorSet descriptor_write = {
      VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
  descriptor_write.dstSet = descriptor_set_;
  descriptor_write.dstBinding = 0;
  descriptor_write.descriptorCount = 1;
  descriptor_write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
  descriptor_write.pImageInfo = &descriptor_image_info;
  dfn.vkUpdateDescriptorSets(device, 1, &descriptor_write, 0, nullptr);

  VkPipelineShaderStageCreateInfo stages[2] = {};
  stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
  stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
  stages[0].module = vertex_shader_;
  stages[0].pName = "main";
  stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
  stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
  stages[1].module = fragment_shader_;
  stages[1].pName = "main";
  VkPipelineVertexInputStateCreateInfo vertex_input = {
      VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
  VkPipelineInputAssemblyStateCreateInfo input_assembly = {
      VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
  input_assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
  VkPipelineViewportStateCreateInfo viewport_state = {
      VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
  viewport_state.viewportCount = 1;
  viewport_state.scissorCount = 1;
  VkPipelineRasterizationStateCreateInfo rasterization = {
      VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
  rasterization.polygonMode = VK_POLYGON_MODE_FILL;
  rasterization.cullMode = VK_CULL_MODE_NONE;
  rasterization.frontFace = VK_FRONT_FACE_CLOCKWISE;
  rasterization.lineWidth = 1.0f;
  VkPipelineMultisampleStateCreateInfo multisample = {
      VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
  multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
  VkPipelineColorBlendAttachmentState blend_attachment = {};
  blend_attachment.colorWriteMask =
      VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
      VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
  VkPipelineColorBlendStateCreateInfo color_blend = {
      VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
  color_blend.attachmentCount = 1;
  color_blend.pAttachments = &blend_attachment;
  static const VkDynamicState kDynamicStates[] = {VK_DYNAMIC_STATE_VIEWPORT,
                                                  VK_DYNAMIC_STATE_SCISSOR};
  VkPipelineDynamicStateCreateInfo dynamic_state = {
      VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
  dynamic_state.dynamicStateCount = uint32_t(xe::countof(kDynamicStates));
  dynamic_state.pDynamicStates = kDynamicStates;
  VkGraphicsPipelineCreateInfo pipeline_create_info = {
      VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
  pipeline_create_info.stageCount = 2;
  pipeline_create_info.pStages = stages;
  pipeline_create_info.pVertexInputState = &vertex_input;
  pipeline_create_info.pInputAssemblyState = &input_assembly;
  pipeline_create_info.pViewportState = &viewport_state;
  pipeline_create_info.pRasterizationState = &rasterization;
  pipeline_create_info.pMultisampleState = &multisample;
  pipeline_create_info.pColorBlendState = &color_blend;
  pipeline_create_info.pDynamicState = &dynamic_state;
  pipeline_create_info.layout = pipeline_layout_;
  pipeline_create_info.renderPass = swapchain_render_pass;
  pipeline_create_info.subpass = 0;
  pipeline_create_info.basePipelineIndex = -1;
  if (dfn.vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, 1,
                                    &pipeline_create_info, nullptr,
                                    &pipeline_) != VK_SUCCESS) {
    XELOGE("KiwiPresentRotation: Failed to create the rotation pipeline");
    pipeline_ = VK_NULL_HANDLE;
    Release();
    return false;
  }
  pipeline_render_pass_ = swapchain_render_pass;

  // Last, as prepared() is keyed on it.
  VkFramebufferCreateInfo framebuffer_create_info = {
      VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
  framebuffer_create_info.renderPass = source_render_pass_;
  framebuffer_create_info.attachmentCount = 1;
  framebuffer_create_info.pAttachments = &source_view_;
  framebuffer_create_info.width = logical_extent.width;
  framebuffer_create_info.height = logical_extent.height;
  framebuffer_create_info.layers = 1;
  if (dfn.vkCreateFramebuffer(device, &framebuffer_create_info, nullptr,
                              &source_framebuffer_) != VK_SUCCESS) {
    XELOGE("KiwiPresentRotation: Failed to create the source framebuffer");
    source_framebuffer_ = VK_NULL_HANDLE;
    Release();
    return false;
  }
  source_extent_ = logical_extent;
  return true;
}

void KiwiPresentRotation::Draw(const VkCommandBuffer command_buffer,
                               const VkRenderPass swapchain_render_pass,
                               const VkFramebuffer swapchain_framebuffer,
                               const VkExtent2D physical_extent,
                               const uint32_t quarter_turns) const {
  assert_true(prepared());
  assert_true(swapchain_render_pass == pipeline_render_pass_);
  const VulkanDevice::Functions& dfn = vulkan_device_->functions();
  VkClearValue clear_value = {};
  clear_value.color.float32[3] = 1.0f;
  VkRenderPassBeginInfo begin_info = {VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
  begin_info.renderPass = swapchain_render_pass;
  begin_info.framebuffer = swapchain_framebuffer;
  begin_info.renderArea.extent = physical_extent;
  // Ignored unless the swapchain pass clears.
  begin_info.clearValueCount = 1;
  begin_info.pClearValues = &clear_value;
  dfn.vkCmdBeginRenderPass(command_buffer, &begin_info,
                           VK_SUBPASS_CONTENTS_INLINE);
  VkViewport viewport;
  viewport.x = 0.0f;
  viewport.y = 0.0f;
  viewport.width = float(physical_extent.width);
  viewport.height = float(physical_extent.height);
  viewport.minDepth = 0.0f;
  viewport.maxDepth = 1.0f;
  dfn.vkCmdSetViewport(command_buffer, 0, 1, &viewport);
  VkRect2D scissor;
  scissor.offset.x = 0;
  scissor.offset.y = 0;
  scissor.extent = physical_extent;
  dfn.vkCmdSetScissor(command_buffer, 0, 1, &scissor);
  dfn.vkCmdBindPipeline(command_buffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                        pipeline_);
  dfn.vkCmdBindDescriptorSets(command_buffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                              pipeline_layout_, 0, 1, &descriptor_set_, 0,
                              nullptr);
  dfn.vkCmdPushConstants(command_buffer, pipeline_layout_,
                         VK_SHADER_STAGE_VERTEX_BIT, 0, sizeof(uint32_t),
                         &quarter_turns);
  dfn.vkCmdDraw(command_buffer, 3, 1, 0, 0);
  dfn.vkCmdEndRenderPass(command_buffer);
}

}  // namespace vulkan
}  // namespace ui
}  // namespace xe
