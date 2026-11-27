// SPDX-FileCopyrightText: Copyright 2026 Ghael-V (Lemon FG, by its author)
// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

#include "xenia/ui/vulkan/kiwi_frame_gen.h"

#include <algorithm>
#include <bit>
#include <deque>

#include "xenia/base/logging.h"
#include "xenia/ui/vulkan/vulkan_util.h"

namespace xe {
namespace ui {
namespace vulkan {

namespace {
#include "xenia/ui/vulkan/kiwi_fg/kiwi_fg_generate_cs.h"
#include "xenia/ui/vulkan/kiwi_fg/kiwi_fg_luma_cs.h"
#include "xenia/ui/vulkan/kiwi_fg/kiwi_fg_median_cs.h"
#include "xenia/ui/vulkan/kiwi_fg/kiwi_fg_project_cs.h"
#include "xenia/ui/vulkan/kiwi_fg/kiwi_fg_search_cs.h"
#include "xenia/ui/vulkan/kiwi_fg/kiwi_fg_still_cs.h"

constexpr VkFormat kFrameFormat = VK_FORMAT_R8G8B8A8_UNORM;
constexpr VkFormat kWorkFormat = VK_FORMAT_R16G16B16A16_SFLOAT;
constexpr uint32_t kBlock = 8;

// Penalties of the motion search, in SAD units (0-255 scale) per pixel of
// vector length, per block row: chosen on captured gameplay, see
// lemon_fg_search.comp.
constexpr float kZeroBias = 0.2f;
constexpr float kSmoothBias = 1.0f;

enum SearchMode : uint32_t {
  kModeExhaustive = 0,
  kModeFromCoarser = 1,
  kModeRefine = 2,
};

constexpr VkImageSubresourceRange kColorRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0,
                                                 1, 0, 1};

uint32_t DivCeil(uint32_t value, uint32_t divisor) {
  return (value + divisor - 1) / divisor;
}

VkExtent2D GridOf(VkExtent2D size) {
  return {DivCeil(size.width, kBlock), DivCeil(size.height, kBlock)};
}

uint32_t Bits(float value) { return std::bit_cast<uint32_t>(value); }

VkImageMemoryBarrier ImageBarrier(VkImage image, VkAccessFlags src_access,
                                  VkAccessFlags dst_access,
                                  VkImageLayout old_layout,
                                  VkImageLayout new_layout) {
  VkImageMemoryBarrier barrier = {};
  barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
  barrier.srcAccessMask = src_access;
  barrier.dstAccessMask = dst_access;
  barrier.oldLayout = old_layout;
  barrier.newLayout = new_layout;
  barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  barrier.image = image;
  barrier.subresourceRange = kColorRange;
  return barrier;
}
}  // namespace

// Writes a descriptor set binding by binding, in the order the calls are made.
class KiwiFrameGenChain::DescriptorWriter {
 public:
  explicit DescriptorWriter(VkDescriptorSet set) : set_(set) {}

  DescriptorWriter& Sampler(VkSampler sampler) {
    return Push(VK_DESCRIPTOR_TYPE_SAMPLER, sampler, VK_NULL_HANDLE);
  }
  DescriptorWriter& Sampled(const Image& image) {
    return Push(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, VK_NULL_HANDLE, image.view);
  }
  DescriptorWriter& Storage(const Image& image) {
    return Push(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_NULL_HANDLE, image.view);
  }

  void Build(const VulkanDevice* vulkan_device) {
    if (writes_.empty()) {
      return;
    }
    vulkan_device->functions().vkUpdateDescriptorSets(
        vulkan_device->device(), uint32_t(writes_.size()), writes_.data(), 0,
        nullptr);
    writes_.clear();
  }

 private:
  DescriptorWriter& Push(VkDescriptorType type, VkSampler sampler,
                         VkImageView view) {
    VkDescriptorImageInfo& info = infos_.emplace_back();
    info.sampler = sampler;
    info.imageView = view;
    info.imageLayout = view == VK_NULL_HANDLE ? VK_IMAGE_LAYOUT_UNDEFINED
                                              : VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet& write = writes_.emplace_back();
    write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    write.pNext = nullptr;
    write.dstSet = set_;
    write.dstBinding = binding_++;
    write.dstArrayElement = 0;
    write.descriptorCount = 1;
    write.descriptorType = type;
    write.pImageInfo = &info;
    write.pBufferInfo = nullptr;
    write.pTexelBufferView = nullptr;
    return *this;
  }

  VkDescriptorSet set_;
  uint32_t binding_ = 0;
  // A deque keeps the infos the writes point to in place as more are added.
  std::deque<VkDescriptorImageInfo> infos_;
  std::vector<VkWriteDescriptorSet> writes_;
};

bool KiwiFrameGenChain::IsSupported(const VulkanDevice* vulkan_device) {
  // The search sums costs with subgroup adds in compute shaders; the generate
  // shader writes through an rgba8 storage image (always supported).
  const VulkanDevice::Properties& properties = vulkan_device->properties();
  constexpr VkSubgroupFeatureFlags kNeeded =
      VK_SUBGROUP_FEATURE_BASIC_BIT | VK_SUBGROUP_FEATURE_ARITHMETIC_BIT;
  return (properties.subgroupSupportedStages & VK_SHADER_STAGE_COMPUTE_BIT) &&
         (properties.subgroupSupportedOperations & kNeeded) == kNeeded;
}

std::unique_ptr<KiwiFrameGenChain> KiwiFrameGenChain::Create(
    const VulkanDevice* vulkan_device, VkExtent2D extent,
    uint32_t search_reduction) {
  std::unique_ptr<KiwiFrameGenChain> chain(
      new KiwiFrameGenChain(vulkan_device));
  if (!chain->Initialize(extent, search_reduction)) {
    return nullptr;
  }
  return chain;
}

bool KiwiFrameGenChain::Initialize(VkExtent2D extent,
                                   uint32_t search_reduction) {
  const VulkanDevice::Functions& dfn = vulkan_device_->functions();
  const VkDevice device = vulkan_device_->device();

  extent_ = {std::max(1u, extent.width), std::max(1u, extent.height)};
  search_reduction_ = std::clamp(search_reduction, 2u, 8u);
  level_size_[0] = {std::max(1u, DivCeil(extent_.width, search_reduction_)),
                    std::max(1u, DivCeil(extent_.height, search_reduction_))};
  for (uint32_t level = 1; level < kLevels; ++level) {
    level_size_[level] = {std::max(1u, DivCeil(level_size_[level - 1].width, 2)),
                          std::max(1u, DivCeil(level_size_[level - 1].height, 2))};
  }
  for (uint32_t level = 0; level < kLevels; ++level) {
    grid_[level] = GridOf(level_size_[level]);
  }
  still_size_ = {std::max(1u, DivCeil(extent_.width, 2)),
                 std::max(1u, DivCeil(extent_.height, 2))};

  // Pool: every set is allocated once and freed with the pool.
  VkDescriptorPoolSize pool_sizes[] = {
      {VK_DESCRIPTOR_TYPE_SAMPLER, 128},
      {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, 256},
      {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 128},
  };
  VkDescriptorPoolCreateInfo pool_info = {};
  pool_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
  pool_info.maxSets = 128;
  pool_info.poolSizeCount = uint32_t(std::size(pool_sizes));
  pool_info.pPoolSizes = pool_sizes;
  if (dfn.vkCreateDescriptorPool(device, &pool_info, nullptr,
                                 &descriptor_pool_) != VK_SUCCESS) {
    XELOGE("Lemon FG: failed to create the descriptor pool");
    return false;
  }

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
    XELOGE("Lemon FG: failed to create the sampler");
    return false;
  }

  constexpr auto kSampler = VK_DESCRIPTOR_TYPE_SAMPLER;
  constexpr auto kSampled = VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
  constexpr auto kStorage = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
  if (!CreatePass(luma_pass_, kiwi_fg_luma_cs, sizeof(kiwi_fg_luma_cs),
                  {kSampler, kSampled, kStorage}, sizeof(uint32_t) * 5) ||
      !CreatePass(search_pass_, kiwi_fg_search_cs, sizeof(kiwi_fg_search_cs),
                  {kSampler, kSampled, kSampled, kSampled, kSampled, kStorage},
                  sizeof(uint32_t) * 11) ||
      !CreatePass(median_pass_, kiwi_fg_median_cs, sizeof(kiwi_fg_median_cs),
                  {kSampler, kSampled, kStorage}, sizeof(uint32_t) * 2) ||
      !CreatePass(project_pass_, kiwi_fg_project_cs,
                  sizeof(kiwi_fg_project_cs), {kSampler, kSampled, kStorage},
                  sizeof(uint32_t) * 3) ||
      !CreatePass(still_pass_, kiwi_fg_still_cs, sizeof(kiwi_fg_still_cs),
                  {kSampler, kSampled, kSampled, kStorage},
                  sizeof(uint32_t) * 5) ||
      !CreatePass(generate_pass_, kiwi_fg_generate_cs,
                  sizeof(kiwi_fg_generate_cs),
                  {kSampler, kSampled, kSampled, kSampled, kSampled, kStorage},
                  sizeof(uint32_t) * 9)) {
    XELOGE("Lemon FG: failed to create the compute passes");
    return false;
  }

  bool images_ok = true;
  for (Image& frame : frames_) {
    images_ok &= CreateImage(frame, extent_, kFrameFormat);
  }
  for (auto& slot : pyramid_) {
    for (uint32_t level = 0; level < kLevels; ++level) {
      images_ok &= CreateImage(slot[level], level_size_[level], kWorkFormat);
    }
  }
  for (uint32_t level = 0; level < kLevels; ++level) {
    for (Image& image : fields_[level]) {
      images_ok &= CreateImage(image, grid_[level], kWorkFormat);
    }
  }
  for (Image& image : flows_) {
    images_ok &= CreateImage(image, grid_[0], kWorkFormat);
  }
  images_ok &= CreateImage(offsets_, grid_[0], kWorkFormat);
  images_ok &= CreateImage(offsets_filtered_, grid_[0], kWorkFormat);
  for (Image& image : still_masks_) {
    images_ok &= CreateImage(image, still_size_, kWorkFormat);
  }
  for (Image& image : outputs_) {
    images_ok &= CreateImage(image, extent_, kFrameFormat);
  }
  if (!images_ok) {
    XELOGE("Lemon FG: failed to create the images");
    return false;
  }

  for (uint32_t slot = 0; slot < 2; ++slot) {
    luma_sets_[slot] = AllocateSet(luma_pass_);
    DescriptorWriter(luma_sets_[slot])
        .Sampler(sampler_)
        .Sampled(frames_[slot])
        .Storage(pyramid_[slot][0])
        .Build(vulkan_device_);
    for (uint32_t level = 1; level < kLevels; ++level) {
      VkDescriptorSet& set = down_sets_[slot][level - 1];
      set = AllocateSet(luma_pass_);
      DescriptorWriter(set)
          .Sampler(sampler_)
          .Sampled(pyramid_[slot][level - 1])
          .Storage(pyramid_[slot][level])
          .Build(vulkan_device_);
    }

    // The pair ending at this slot: A is the other slot's frame, C this one's.
    const auto& luma_a = pyramid_[1 - slot];
    const auto& luma_c = pyramid_[slot];
    auto& steps = search_steps_[slot];
    uint32_t next = 0;
    const auto add_step = [&](uint32_t level, uint32_t mode, const Image& in,
                              VkExtent2D in_grid, const Image& out) {
      SearchStep& step = steps[next++];
      step.set = AllocateSet(search_pass_);
      step.grid = grid_[level];
      DescriptorWriter(step.set)
          .Sampler(sampler_)
          .Sampled(luma_a[level])
          .Sampled(luma_c[level])
          .Sampled(in)
          .Sampled(flows_[1 - slot])
          .Storage(out)
          .Build(vulkan_device_);
      step.push = {
          level_size_[level].width,
          level_size_[level].height,
          grid_[level].width,
          grid_[level].height,
          in_grid.width,
          in_grid.height,
          mode,
          0,  // use_temporal, set when recording
          Bits(1.0f / float(1u << level)),
          Bits(kZeroBias),
          Bits(kSmoothBias),
      };
    };
    // Coarsest level: exhaustive, then two passes; middle levels: two passes;
    // the finest: one.
    auto& coarsest = fields_[kLevels - 1];
    add_step(kLevels - 1, kModeExhaustive, coarsest[1], grid_[kLevels - 1],
             coarsest[0]);
    add_step(kLevels - 1, kModeRefine, coarsest[0], grid_[kLevels - 1],
             coarsest[1]);
    add_step(kLevels - 1, kModeRefine, coarsest[1], grid_[kLevels - 1],
             coarsest[0]);
    const Image* result = &coarsest[0];
    for (uint32_t level = kLevels - 1; level-- > 0;) {
      auto& current = fields_[level];
      add_step(level, kModeFromCoarser, *result, grid_[level + 1], current[0]);
      result = &current[0];
      if (level > 0) {
        add_step(level, kModeRefine, current[0], grid_[level], current[1]);
        result = &current[1];
      }
    }

    flow_median_sets_[slot] = AllocateSet(median_pass_);
    DescriptorWriter(flow_median_sets_[slot])
        .Sampler(sampler_)
        .Sampled(*result)
        .Storage(flows_[slot])
        .Build(vulkan_device_);

    // Still mask: raw from the frames, then majority and grow, ping-ponging
    // between the two images.
    for (uint32_t pass = 0; pass < kStillPasses; ++pass) {
      VkDescriptorSet& set = still_sets_[slot][pass];
      set = AllocateSet(still_pass_);
      const Image& in_a =
          pass == 0 ? frames_[1 - slot] : still_masks_[pass % 2 == 1 ? 0 : 1];
      const Image& in_c = pass == 0 ? frames_[slot] : in_a;
      DescriptorWriter(set)
          .Sampler(sampler_)
          .Sampled(in_a)
          .Sampled(in_c)
          .Storage(still_masks_[pass % 2 == 0 ? 0 : 1])
          .Build(vulkan_device_);
    }

    project_sets_[slot] = AllocateSet(project_pass_);
    DescriptorWriter(project_sets_[slot])
        .Sampler(sampler_)
        .Sampled(flows_[slot])
        .Storage(offsets_)
        .Build(vulkan_device_);

    for (uint32_t output = 0; output < kOutputs; ++output) {
      VkDescriptorSet& set = generate_sets_[slot][output];
      set = AllocateSet(generate_pass_);
      DescriptorWriter(set)
          .Sampler(sampler_)
          .Sampled(frames_[1 - slot])
          .Sampled(frames_[slot])
          .Sampled(offsets_filtered_)
          .Sampled(still_masks_[kStillFinal])
          .Storage(outputs_[output])
          .Build(vulkan_device_);
    }
  }
  offsets_median_set_ = AllocateSet(median_pass_);
  DescriptorWriter(offsets_median_set_)
      .Sampler(sampler_)
      .Sampled(offsets_)
      .Storage(offsets_filtered_)
      .Build(vulkan_device_);

  if (!ok_) {
    XELOGE("Lemon FG: failed to allocate the descriptor sets");
    return false;
  }
  XELOGI("Lemon FG: chain for {}x{}, motion search at 1/{}", extent_.width,
         extent_.height, search_reduction_);
  return true;
}

KiwiFrameGenChain::~KiwiFrameGenChain() {
  const VulkanDevice::Functions& dfn = vulkan_device_->functions();
  const VkDevice device = vulkan_device_->device();
  for (Image& image : frames_) DestroyImage(image);
  for (auto& slot : pyramid_) {
    for (Image& image : slot) DestroyImage(image);
  }
  for (auto& level : fields_) {
    for (Image& image : level) DestroyImage(image);
  }
  for (Image& image : flows_) DestroyImage(image);
  DestroyImage(offsets_);
  DestroyImage(offsets_filtered_);
  for (Image& image : still_masks_) DestroyImage(image);
  for (Image& image : outputs_) DestroyImage(image);
  DestroyPass(luma_pass_);
  DestroyPass(search_pass_);
  DestroyPass(median_pass_);
  DestroyPass(project_pass_);
  DestroyPass(still_pass_);
  DestroyPass(generate_pass_);
  util::DestroyAndNullHandle(dfn.vkDestroySampler, device, sampler_);
  // Frees every set allocated from it.
  util::DestroyAndNullHandle(dfn.vkDestroyDescriptorPool, device,
                             descriptor_pool_);
}

bool KiwiFrameGenChain::CreateImage(Image& image, VkExtent2D extent,
                                    VkFormat format) {
  image.extent = {std::max(1u, extent.width), std::max(1u, extent.height)};
  VkImageCreateInfo create_info = {};
  create_info.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
  create_info.imageType = VK_IMAGE_TYPE_2D;
  create_info.format = format;
  create_info.extent = {image.extent.width, image.extent.height, 1};
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
  view_info.format = format;
  view_info.subresourceRange = kColorRange;
  return vulkan_device_->functions().vkCreateImageView(
             vulkan_device_->device(), &view_info, nullptr, &image.view) ==
         VK_SUCCESS;
}

void KiwiFrameGenChain::DestroyImage(Image& image) {
  const VulkanDevice::Functions& dfn = vulkan_device_->functions();
  const VkDevice device = vulkan_device_->device();
  util::DestroyAndNullHandle(dfn.vkDestroyImageView, device, image.view);
  util::DestroyAndNullHandle(dfn.vkDestroyImage, device, image.image);
  util::DestroyAndNullHandle(dfn.vkFreeMemory, device, image.memory);
}

bool KiwiFrameGenChain::CreatePass(
    Pass& pass, const uint32_t* code, size_t code_size,
    std::initializer_list<VkDescriptorType> bindings, uint32_t push_size) {
  const VulkanDevice::Functions& dfn = vulkan_device_->functions();
  const VkDevice device = vulkan_device_->device();
  pass.push_size = push_size;
  pass.module = util::CreateShaderModule(vulkan_device_, code, code_size);
  if (pass.module == VK_NULL_HANDLE) {
    return false;
  }
  std::vector<VkDescriptorSetLayoutBinding> layout_bindings;
  uint32_t binding_index = 0;
  for (VkDescriptorType type : bindings) {
    VkDescriptorSetLayoutBinding& binding = layout_bindings.emplace_back();
    binding.binding = binding_index++;
    binding.descriptorType = type;
    binding.descriptorCount = 1;
    binding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    binding.pImmutableSamplers = nullptr;
  }
  VkDescriptorSetLayoutCreateInfo set_layout_info = {};
  set_layout_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
  set_layout_info.bindingCount = uint32_t(layout_bindings.size());
  set_layout_info.pBindings = layout_bindings.data();
  if (dfn.vkCreateDescriptorSetLayout(device, &set_layout_info, nullptr,
                                      &pass.set_layout) != VK_SUCCESS) {
    return false;
  }
  VkPushConstantRange range = {VK_SHADER_STAGE_COMPUTE_BIT, 0, push_size};
  VkPipelineLayoutCreateInfo pipeline_layout_info = {};
  pipeline_layout_info.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
  pipeline_layout_info.setLayoutCount = 1;
  pipeline_layout_info.pSetLayouts = &pass.set_layout;
  pipeline_layout_info.pushConstantRangeCount = 1;
  pipeline_layout_info.pPushConstantRanges = &range;
  if (dfn.vkCreatePipelineLayout(device, &pipeline_layout_info, nullptr,
                                 &pass.pipeline_layout) != VK_SUCCESS) {
    return false;
  }
  VkComputePipelineCreateInfo pipeline_info = {};
  pipeline_info.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
  pipeline_info.stage.sType =
      VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
  pipeline_info.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
  pipeline_info.stage.module = pass.module;
  pipeline_info.stage.pName = "main";
  pipeline_info.layout = pass.pipeline_layout;
  pipeline_info.basePipelineIndex = -1;
  return dfn.vkCreateComputePipelines(device, VK_NULL_HANDLE, 1,
                                      &pipeline_info, nullptr,
                                      &pass.pipeline) == VK_SUCCESS;
}

void KiwiFrameGenChain::DestroyPass(Pass& pass) {
  const VulkanDevice::Functions& dfn = vulkan_device_->functions();
  const VkDevice device = vulkan_device_->device();
  util::DestroyAndNullHandle(dfn.vkDestroyPipeline, device, pass.pipeline);
  util::DestroyAndNullHandle(dfn.vkDestroyPipelineLayout, device,
                             pass.pipeline_layout);
  util::DestroyAndNullHandle(dfn.vkDestroyDescriptorSetLayout, device,
                             pass.set_layout);
  util::DestroyAndNullHandle(dfn.vkDestroyShaderModule, device, pass.module);
}

VkDescriptorSet KiwiFrameGenChain::AllocateSet(const Pass& pass) {
  VkDescriptorSetAllocateInfo allocate_info = {};
  allocate_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
  allocate_info.descriptorPool = descriptor_pool_;
  allocate_info.descriptorSetCount = 1;
  allocate_info.pSetLayouts = &pass.set_layout;
  VkDescriptorSet set = VK_NULL_HANDLE;
  if (vulkan_device_->functions().vkAllocateDescriptorSets(
          vulkan_device_->device(), &allocate_info, &set) != VK_SUCCESS) {
    ok_ = false;
  }
  return set;
}

void KiwiFrameGenChain::Run(VkCommandBuffer command_buffer, const Pass& pass,
                            VkDescriptorSet set, const void* push,
                            uint32_t groups_x, uint32_t groups_y) {
  const VulkanDevice::Functions& dfn = vulkan_device_->functions();
  dfn.vkCmdBindPipeline(command_buffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                        pass.pipeline);
  dfn.vkCmdBindDescriptorSets(command_buffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                              pass.pipeline_layout, 0, 1, &set, 0, nullptr);
  dfn.vkCmdPushConstants(command_buffer, pass.pipeline_layout,
                         VK_SHADER_STAGE_COMPUTE_BIT, 0, pass.push_size, push);
  dfn.vkCmdDispatch(command_buffer, groups_x, groups_y, 1);
  VkMemoryBarrier barrier = {};
  barrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
  barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
  barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
  dfn.vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                           VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &barrier,
                           0, nullptr, 0, nullptr);
}

void KiwiFrameGenChain::InitializeImages(VkCommandBuffer command_buffer) {
  // Every image lives in GENERAL from here on.
  std::vector<VkImageMemoryBarrier> barriers;
  const auto add = [&](Image& image) {
    barriers.push_back(ImageBarrier(image.image, 0,
                                    VK_ACCESS_SHADER_READ_BIT |
                                        VK_ACCESS_SHADER_WRITE_BIT |
                                        VK_ACCESS_TRANSFER_WRITE_BIT,
                                    VK_IMAGE_LAYOUT_UNDEFINED,
                                    VK_IMAGE_LAYOUT_GENERAL));
    image.layout = VK_IMAGE_LAYOUT_GENERAL;
  };
  for (Image& image : frames_) add(image);
  for (auto& slot : pyramid_) {
    for (Image& image : slot) add(image);
  }
  for (auto& level : fields_) {
    for (Image& image : level) add(image);
  }
  for (Image& image : flows_) add(image);
  add(offsets_);
  add(offsets_filtered_);
  for (Image& image : still_masks_) add(image);
  for (Image& image : outputs_) add(image);
  vulkan_device_->functions().vkCmdPipelineBarrier(
      command_buffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
      0, nullptr, 0, nullptr, uint32_t(barriers.size()), barriers.data());
  initialized_ = true;
}

void KiwiFrameGenChain::Ingest(VkCommandBuffer command_buffer,
                               VkImage source) {
  if (!initialized_) {
    InitializeImages(command_buffer);
  }
  const VulkanDevice::Functions& dfn = vulkan_device_->functions();
  ++frame_count_;
  Image& slot = frames_[frame_count_ % 2];
  // The slot was last read by the passes of the pair before.
  VkImageMemoryBarrier before =
      ImageBarrier(slot.image, VK_ACCESS_SHADER_READ_BIT,
                   VK_ACCESS_TRANSFER_WRITE_BIT, VK_IMAGE_LAYOUT_GENERAL,
                   VK_IMAGE_LAYOUT_GENERAL);
  dfn.vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                           VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0,
                           nullptr, 1, &before);
  // Format conversion (from the presenter's 10:10:10:2) by the blit.
  VkImageBlit region = {};
  region.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
  region.srcOffsets[1] = {int32_t(extent_.width), int32_t(extent_.height), 1};
  region.dstSubresource = region.srcSubresource;
  region.dstOffsets[1] = region.srcOffsets[1];
  dfn.vkCmdBlitImage(command_buffer, source,
                     VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, slot.image,
                     VK_IMAGE_LAYOUT_GENERAL, 1, &region, VK_FILTER_NEAREST);
  VkImageMemoryBarrier after =
      ImageBarrier(slot.image, VK_ACCESS_TRANSFER_WRITE_BIT,
                   VK_ACCESS_SHADER_READ_BIT, VK_IMAGE_LAYOUT_GENERAL,
                   VK_IMAGE_LAYOUT_GENERAL);
  dfn.vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                           VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr,
                           0, nullptr, 1, &after);
}

void KiwiFrameGenChain::BuildPyramid(VkCommandBuffer command_buffer,
                                     uint32_t slot) {
  const std::array<uint32_t, 5> luma_push = {extent_.width, extent_.height,
                                             level_size_[0].width,
                                             level_size_[0].height, 0};
  Run(command_buffer, luma_pass_, luma_sets_[slot], luma_push.data(),
      DivCeil(level_size_[0].width, 8), DivCeil(level_size_[0].height, 8));
  for (uint32_t level = 1; level < kLevels; ++level) {
    const std::array<uint32_t, 5> down_push = {
        level_size_[level - 1].width, level_size_[level - 1].height,
        level_size_[level].width, level_size_[level].height, 1};
    Run(command_buffer, luma_pass_, down_sets_[slot][level - 1],
        down_push.data(), DivCeil(level_size_[level].width, 8),
        DivCeil(level_size_[level].height, 8));
  }
}

bool KiwiFrameGenChain::Analyze(VkCommandBuffer command_buffer) {
  if (frame_count_ < 2) {
    return false;
  }
  const uint32_t slot = uint32_t(frame_count_ % 2);
  if (last_pyramid_ + 1 != frame_count_) {
    // The previous frame was only copied, never analysed.
    BuildPyramid(command_buffer, 1 - slot);
  }
  BuildPyramid(command_buffer, slot);
  last_pyramid_ = frame_count_;

  const uint32_t use_temporal = last_flow_ + 1 == frame_count_ ? 1 : 0;
  for (SearchStep& step : search_steps_[slot]) {
    step.push[7] = use_temporal;
    // One workgroup per block.
    Run(command_buffer, search_pass_, step.set, step.push.data(),
        step.grid.width, step.grid.height);
  }
  const std::array<uint32_t, 2> median_push = {grid_[0].width,
                                               grid_[0].height};
  Run(command_buffer, median_pass_, flow_median_sets_[slot],
      median_push.data(), DivCeil(grid_[0].width, 8),
      DivCeil(grid_[0].height, 8));
  last_flow_ = frame_count_;

  for (uint32_t pass = 0; pass < kStillPasses; ++pass) {
    const std::array<uint32_t, 5> still_push = {still_size_.width,
                                                still_size_.height,
                                                extent_.width, extent_.height,
                                                pass};
    Run(command_buffer, still_pass_, still_sets_[slot][pass],
        still_push.data(), DivCeil(still_size_.width, 8),
        DivCeil(still_size_.height, 8));
  }
  return true;
}

VkImage KiwiFrameGenChain::Generate(VkCommandBuffer command_buffer, float t,
                                    uint32_t output) {
  const VulkanDevice::Functions& dfn = vulkan_device_->functions();
  const uint32_t slot = uint32_t(frame_count_ % 2);
  output %= kOutputs;
  Image& target = outputs_[output];

  const std::array<uint32_t, 3> project_push = {grid_[0].width,
                                                grid_[0].height, Bits(t)};
  Run(command_buffer, project_pass_, project_sets_[slot], project_push.data(),
      DivCeil(grid_[0].width, 8), DivCeil(grid_[0].height, 8));
  const std::array<uint32_t, 2> median_push = {grid_[0].width,
                                               grid_[0].height};
  Run(command_buffer, median_pass_, offsets_median_set_, median_push.data(),
      DivCeil(grid_[0].width, 8), DivCeil(grid_[0].height, 8));

  // The output may still be the source of an earlier presentation blit.
  VkImageMemoryBarrier before = ImageBarrier(
      target.image, VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT,
      target.layout, VK_IMAGE_LAYOUT_GENERAL);
  dfn.vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                           VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr,
                           0, nullptr, 1, &before);
  const std::array<uint32_t, 9> generate_push = {
      extent_.width,     extent_.height,     level_size_[0].width,
      level_size_[0].height, grid_[0].width, grid_[0].height,
      still_size_.width, still_size_.height, Bits(t)};
  Run(command_buffer, generate_pass_, generate_sets_[slot][output],
      generate_push.data(), DivCeil(extent_.width, 16),
      DivCeil(extent_.height, 8));

  VkImageMemoryBarrier after = ImageBarrier(
      target.image, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
      VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
  dfn.vkCmdPipelineBarrier(command_buffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                           VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0,
                           nullptr, 1, &after);
  target.layout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
  return target.image;
}

}  // namespace vulkan
}  // namespace ui
}  // namespace xe
