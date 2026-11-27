// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

#include "xenia/ui/vulkan/kiwi_post_effects.h"

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <atomic>
#include <iterator>
#include <mutex>

#include "xenia/base/logging.h"
#include "xenia/ui/kiwi_post_effects_chain.h"
#include "xenia/ui/vulkan/vulkan_util.h"

namespace xe {
namespace ui {

namespace {
std::mutex published_mutex;
std::string published_chain;
std::atomic<uint32_t> published_generation{0};
}  // namespace

namespace vulkan {

namespace {
#include "xenia/ui/vulkan/kiwi_fx/kiwi_post_effects_cs.h"

constexpr VkFormat kFormat = VK_FORMAT_R8G8B8A8_UNORM;
constexpr uint32_t kGroup = 8;
constexpr VkImageSubresourceRange kColorRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0,
                                                 1, 0, 1};

// The shader's effect ids, in the order of kiwi_post_effects.comp.
constexpr const char* kEffectIds[] = {
    "sharpen", "bloom",   "blur",  "crt",    "scanlines", "vignette", "chroma",
    "grain",   "grade",   "filmic", "levels", "split",    "deband",   "denoise",
    "cartoon", "cel",     "lens",  "pixelate", "mono",
};

struct Push {
  float size[4];
  float time[4];
  float params[KiwiPostEffects::kParams];
  int32_t effect;
};
static_assert(sizeof(Push) == 68);

std::string Trim(const std::string& text) {
  const size_t begin = text.find_first_not_of(" \t");
  if (begin == std::string::npos) {
    return {};
  }
  return text.substr(begin, text.find_last_not_of(" \t") - begin + 1);
}
}  // namespace

std::vector<KiwiPostEffects::Effect> KiwiPostEffects::Parse(
    const std::string& chain) {
  std::vector<Effect> effects;
  size_t start = 0;
  while (start <= chain.size() && effects.size() < kMaxEffects) {
    size_t end = chain.find(';', start);
    if (end == std::string::npos) {
      end = chain.size();
    }
    const std::string entry = Trim(chain.substr(start, end - start));
    start = end + 1;
    if (entry.empty() || entry[0] == '!') {
      continue;
    }
    const size_t colon = entry.find(':');
    const std::string id = Trim(entry.substr(0, colon));
    const auto known = std::find_if(
        std::begin(kEffectIds), std::end(kEffectIds),
        [&id](const char* name) { return id == name; });
    if (known == std::end(kEffectIds)) {
      continue;
    }
    Effect effect;
    effect.id = int32_t(known - std::begin(kEffectIds));
    if (colon != std::string::npos) {
      const std::string values = entry.substr(colon + 1);
      size_t value_start = 0;
      for (uint32_t i = 0; i < kParams && value_start <= values.size(); ++i) {
        size_t value_end = values.find(',', value_start);
        if (value_end == std::string::npos) {
          value_end = values.size();
        }
        const std::string value =
            Trim(values.substr(value_start, value_end - value_start));
        // strtof follows the C locale, which Android leaves at "C" for
        // native code: the decimal separator is always '.'.
        effect.params[i] = value.empty() ? 0.0f : std::strtof(value.c_str(),
                                                              nullptr);
        value_start = value_end + 1;
      }
    }
    effects.push_back(effect);
  }
  return effects;
}

std::unique_ptr<KiwiPostEffects> KiwiPostEffects::Create(
    const VulkanDevice* vulkan_device) {
  std::unique_ptr<KiwiPostEffects> effects(new KiwiPostEffects(vulkan_device));
  if (!effects->Initialize()) {
    return nullptr;
  }
  return effects;
}

bool KiwiPostEffects::Initialize() {
  const VulkanDevice::Functions& dfn = vulkan_device_->functions();
  const VkDevice device = vulkan_device_->device();

  VkSamplerCreateInfo sampler_info = {};
  sampler_info.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
  sampler_info.magFilter = VK_FILTER_LINEAR;
  sampler_info.minFilter = VK_FILTER_LINEAR;
  sampler_info.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
  sampler_info.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
  sampler_info.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
  sampler_info.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
  sampler_info.maxAnisotropy = 1.0f;
  sampler_info.compareOp = VK_COMPARE_OP_NEVER;
  sampler_info.borderColor = VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK;
  if (dfn.vkCreateSampler(device, &sampler_info, nullptr, &sampler_) !=
      VK_SUCCESS) {
    XELOGE("Kiwi360 effects: failed to create the sampler");
    return false;
  }

  VkDescriptorPoolSize pool_sizes[] = {
      {VK_DESCRIPTOR_TYPE_SAMPLER, 2},
      {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, 2},
      {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 2},
  };
  VkDescriptorPoolCreateInfo pool_info = {};
  pool_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
  pool_info.maxSets = 2;
  pool_info.poolSizeCount = uint32_t(std::size(pool_sizes));
  pool_info.pPoolSizes = pool_sizes;
  if (dfn.vkCreateDescriptorPool(device, &pool_info, nullptr,
                                 &descriptor_pool_) != VK_SUCCESS) {
    XELOGE("Kiwi360 effects: failed to create the descriptor pool");
    return false;
  }

  const VkDescriptorType types[] = {VK_DESCRIPTOR_TYPE_SAMPLER,
                                    VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE,
                                    VK_DESCRIPTOR_TYPE_STORAGE_IMAGE};
  VkDescriptorSetLayoutBinding bindings[3] = {};
  for (uint32_t i = 0; i < 3; ++i) {
    bindings[i].binding = i;
    bindings[i].descriptorType = types[i];
    bindings[i].descriptorCount = 1;
    bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
  }
  VkDescriptorSetLayoutCreateInfo set_layout_info = {};
  set_layout_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
  set_layout_info.bindingCount = 3;
  set_layout_info.pBindings = bindings;
  if (dfn.vkCreateDescriptorSetLayout(device, &set_layout_info, nullptr,
                                      &set_layout_) != VK_SUCCESS) {
    return false;
  }
  VkPushConstantRange range = {VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(Push)};
  VkPipelineLayoutCreateInfo pipeline_layout_info = {};
  pipeline_layout_info.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
  pipeline_layout_info.setLayoutCount = 1;
  pipeline_layout_info.pSetLayouts = &set_layout_;
  pipeline_layout_info.pushConstantRangeCount = 1;
  pipeline_layout_info.pPushConstantRanges = &range;
  if (dfn.vkCreatePipelineLayout(device, &pipeline_layout_info, nullptr,
                                 &pipeline_layout_) != VK_SUCCESS) {
    return false;
  }
  module_ = util::CreateShaderModule(vulkan_device_, kiwi_post_effects_cs,
                                     sizeof(kiwi_post_effects_cs));
  if (module_ == VK_NULL_HANDLE) {
    XELOGE("Kiwi360 effects: failed to create the shader module");
    return false;
  }
  VkComputePipelineCreateInfo pipeline_info = {};
  pipeline_info.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
  pipeline_info.stage.sType =
      VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
  pipeline_info.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
  pipeline_info.stage.module = module_;
  pipeline_info.stage.pName = "main";
  pipeline_info.layout = pipeline_layout_;
  pipeline_info.basePipelineIndex = -1;
  if (dfn.vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipeline_info,
                                   nullptr, &pipeline_) != VK_SUCCESS) {
    XELOGE("Kiwi360 effects: failed to create the pipeline");
    return false;
  }

  const VkDescriptorSetLayout layouts[2] = {set_layout_, set_layout_};
  VkDescriptorSetAllocateInfo allocate_info = {};
  allocate_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
  allocate_info.descriptorPool = descriptor_pool_;
  allocate_info.descriptorSetCount = 2;
  allocate_info.pSetLayouts = layouts;
  return dfn.vkAllocateDescriptorSets(device, &allocate_info, sets_.data()) ==
         VK_SUCCESS;
}

KiwiPostEffects::~KiwiPostEffects() {
  const VulkanDevice::Functions& dfn = vulkan_device_->functions();
  const VkDevice device = vulkan_device_->device();
  DestroyImages();
  util::DestroyAndNullHandle(dfn.vkDestroyPipeline, device, pipeline_);
  util::DestroyAndNullHandle(dfn.vkDestroyShaderModule, device, module_);
  util::DestroyAndNullHandle(dfn.vkDestroyPipelineLayout, device,
                             pipeline_layout_);
  util::DestroyAndNullHandle(dfn.vkDestroyDescriptorSetLayout, device,
                             set_layout_);
  // Frees the sets.
  util::DestroyAndNullHandle(dfn.vkDestroyDescriptorPool, device,
                             descriptor_pool_);
  util::DestroyAndNullHandle(dfn.vkDestroySampler, device, sampler_);
}

bool KiwiPostEffects::CreateImage(Image& image) {
  VkImageCreateInfo create_info = {};
  create_info.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
  create_info.imageType = VK_IMAGE_TYPE_2D;
  create_info.format = kFormat;
  create_info.extent = {extent_.width, extent_.height, 1};
  create_info.mipLevels = 1;
  create_info.arrayLayers = 1;
  create_info.samples = VK_SAMPLE_COUNT_1_BIT;
  create_info.tiling = VK_IMAGE_TILING_OPTIMAL;
  create_info.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT |
                      VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                      VK_IMAGE_USAGE_TRANSFER_DST_BIT;
  create_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
  create_info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
  if (!util::CreateDedicatedAllocationImage(vulkan_device_, create_info,
                                            util::MemoryPurpose::kDeviceLocal,
                                            image.image, image.memory)) {
    return false;
  }
  VkImageViewCreateInfo view_info = {};
  view_info.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
  view_info.image = image.image;
  view_info.viewType = VK_IMAGE_VIEW_TYPE_2D;
  view_info.format = kFormat;
  view_info.subresourceRange = kColorRange;
  return vulkan_device_->functions().vkCreateImageView(
             vulkan_device_->device(), &view_info, nullptr, &image.view) ==
         VK_SUCCESS;
}

void KiwiPostEffects::DestroyImage(Image& image) {
  const VulkanDevice::Functions& dfn = vulkan_device_->functions();
  const VkDevice device = vulkan_device_->device();
  util::DestroyAndNullHandle(dfn.vkDestroyImageView, device, image.view);
  util::DestroyAndNullHandle(dfn.vkDestroyImage, device, image.image);
  util::DestroyAndNullHandle(dfn.vkFreeMemory, device, image.memory);
}

void KiwiPostEffects::DestroyImages() {
  for (Image& image : images_) {
    DestroyImage(image);
  }
  images_initialized_ = false;
  extent_ = {};
}

bool KiwiPostEffects::Resize(VkExtent2D extent) {
  DestroyImages();
  extent_ = {std::max(1u, extent.width), std::max(1u, extent.height)};
  for (Image& image : images_) {
    if (!CreateImage(image)) {
      XELOGE("Kiwi360 effects: failed to create a {}x{} image", extent_.width,
             extent_.height);
      DestroyImages();
      return false;
    }
  }
  VkDescriptorImageInfo infos[2][3] = {};
  VkWriteDescriptorSet writes[6] = {};
  for (uint32_t i = 0; i < 2; ++i) {
    infos[i][0].sampler = sampler_;
    infos[i][1].imageView = images_[i].view;
    infos[i][1].imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    infos[i][2].imageView = images_[1 - i].view;
    infos[i][2].imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    const VkDescriptorType types[] = {VK_DESCRIPTOR_TYPE_SAMPLER,
                                      VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE,
                                      VK_DESCRIPTOR_TYPE_STORAGE_IMAGE};
    for (uint32_t binding = 0; binding < 3; ++binding) {
      VkWriteDescriptorSet& write = writes[i * 3 + binding];
      write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
      write.dstSet = sets_[i];
      write.dstBinding = binding;
      write.descriptorCount = 1;
      write.descriptorType = types[binding];
      write.pImageInfo = &infos[i][binding];
    }
  }
  vulkan_device_->functions().vkUpdateDescriptorSets(
      vulkan_device_->device(), 6, writes, 0, nullptr);
  return true;
}

VkImage KiwiPostEffects::Apply(VkCommandBuffer command_buffer, VkImage source,
                               const std::vector<Effect>& effects,
                               float seconds, uint32_t frame) {
  const VulkanDevice::Functions& dfn = vulkan_device_->functions();

  // The frame into images_[0]: wait for the earlier frame's passes and the
  // presenter's copy out of the last result.
  VkImageMemoryBarrier barriers[2] = {};
  for (uint32_t i = 0; i < 2; ++i) {
    VkImageMemoryBarrier& barrier = barriers[i];
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.srcAccessMask = 0;
    barrier.dstAccessMask =
        i == 0 ? VK_ACCESS_TRANSFER_WRITE_BIT : VK_ACCESS_SHADER_WRITE_BIT;
    barrier.oldLayout = images_initialized_ ? VK_IMAGE_LAYOUT_GENERAL
                                            : VK_IMAGE_LAYOUT_UNDEFINED;
    barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = images_[i].image;
    barrier.subresourceRange = kColorRange;
  }
  images_initialized_ = true;
  dfn.vkCmdPipelineBarrier(
      command_buffer,
      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
      VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0,
      0, nullptr, 0, nullptr, 2, barriers);
  VkImageBlit region = {};
  region.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
  region.srcOffsets[1] = {int32_t(extent_.width), int32_t(extent_.height), 1};
  region.dstSubresource = region.srcSubresource;
  region.dstOffsets[1] = region.srcOffsets[1];
  dfn.vkCmdBlitImage(command_buffer, source,
                     VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, images_[0].image,
                     VK_IMAGE_LAYOUT_GENERAL, 1, &region, VK_FILTER_NEAREST);
  VkMemoryBarrier memory_barrier = {};
  memory_barrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
  memory_barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
  memory_barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
  dfn.vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                           VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1,
                           &memory_barrier, 0, nullptr, 0, nullptr);

  dfn.vkCmdBindPipeline(command_buffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                        pipeline_);
  Push push = {};
  push.size[0] = float(extent_.width);
  push.size[1] = float(extent_.height);
  push.size[2] = 1.0f / float(extent_.width);
  push.size[3] = 1.0f / float(extent_.height);
  push.time[0] = seconds;
  push.time[1] = float(frame % 65536);
  uint32_t current = 0;
  for (const Effect& effect : effects) {
    std::memcpy(push.params, effect.params.data(), sizeof(push.params));
    push.effect = effect.id;
    dfn.vkCmdBindDescriptorSets(command_buffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                                pipeline_layout_, 0, 1, &sets_[current], 0,
                                nullptr);
    dfn.vkCmdPushConstants(command_buffer, pipeline_layout_,
                           VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(Push), &push);
    dfn.vkCmdDispatch(command_buffer, (extent_.width + kGroup - 1) / kGroup,
                      (extent_.height + kGroup - 1) / kGroup, 1);
    current = 1 - current;
    // The result feeds the next pass, which also writes over its source.
    memory_barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    memory_barrier.dstAccessMask =
        VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    dfn.vkCmdPipelineBarrier(command_buffer,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1,
                             &memory_barrier, 0, nullptr, 0, nullptr);
  }
  memory_barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
  memory_barrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
  dfn.vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                           VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1,
                           &memory_barrier, 0, nullptr, 0, nullptr);
  return images_[current].image;
}

}  // namespace vulkan

void KiwiPublishPostEffects(const std::string& chain) {
  std::lock_guard<std::mutex> lock(published_mutex);
  published_chain = chain;
  published_generation.fetch_add(1, std::memory_order_release);
}

uint32_t KiwiPublishedPostEffectsGeneration() {
  return published_generation.load(std::memory_order_acquire);
}

std::string KiwiPublishedPostEffects() {
  std::lock_guard<std::mutex> lock(published_mutex);
  return published_chain;
}

}  // namespace ui
}  // namespace xe
