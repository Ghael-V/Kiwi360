// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

// Kiwi360: the game library's title name and icon, read straight from a disc
// image or XEX without booting it. The name and icon live in the title's
// XDBF (SPA) resource inside the executable image, which is encrypted and
// usually LZX-compressed: this unpacks the image in host memory with the
// same primitives the loader uses, and needs no emulated memory.

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <optional>
#include <span>
#include <string>
#include <vector>

#include "third_party/fmt/include/fmt/format.h"
#include "xenia/base/filesystem.h"
#include "xenia/base/mapped_memory.h"
#include "xenia/cpu/lzx.h"
#include "xenia/kernel/util/game_info_database.h"
#include "xenia/kernel/util/xex2_info.h"
#include "xenia/kernel/util/xex2_util.h"
#include "xenia/kernel/xam/xdbf/spa_info.h"
#include "xenia/vfs/gdfx_util.h"
#include "xenia/xbox.h"

// Defined in xex_module.cc.
void aes_decrypt_buffer(const uint8_t* session_key, const uint8_t* input_buffer,
                        const size_t input_size, uint8_t* output_buffer,
                        const size_t output_size);

namespace {

using namespace xe;

#define KIWI_TITLE_FAIL(...) \
  __android_log_print(ANDROID_LOG_WARN, "Kiwi360Title", __VA_ARGS__)

// The executable image key of retail titles; development titles use zeros.
constexpr uint8_t kRetailKey[16] = {0x20, 0xB1, 0x85, 0xA5, 0x9D, 0x28,
                                    0xFD, 0xC3, 0x40, 0x58, 0x3F, 0xBB,
                                    0x08, 0x96, 0xBF, 0x91};
constexpr uint8_t kDevkitKey[16] = {};

constexpr uint32_t kXex2Magic = 0x58455832;  // 'XEX2'
constexpr uint32_t kMaxImageSize = 256 * 1024 * 1024;

struct XexView {
  const uint8_t* data;
  size_t size;
};

// Decrypts and decompresses the executable image. False if anything is out of
// bounds or the key is wrong (the image then doesn't start with "MZ").
bool UnpackImage(XexView xex, const uint8_t* key, std::vector<uint8_t>& image) {
  if (xex.size < sizeof(xex2_header)) {
    return false;
  }
  auto header = reinterpret_cast<const xex2_header*>(xex.data);
  const uint32_t header_size = header->header_size;
  const uint32_t security_offset = header->security_offset;
  if (header->magic != kXex2Magic || header_size > xex.size ||
      security_offset + sizeof(xex2_security_info) > header_size ||
      sizeof(xex2_header) + header->header_count * sizeof(xex2_opt_header) >
          header_size) {
    return false;
  }
  xex2_opt_file_format_info* format = nullptr;
  if (!GetXexOptHeader(header, XEX_HEADER_FILE_FORMAT_INFO, &format) ||
      reinterpret_cast<const uint8_t*>(format) + sizeof(*format) >
          xex.data + header_size) {
    return false;
  }
  auto security =
      reinterpret_cast<const xex2_security_info*>(xex.data + security_offset);
  const uint32_t image_size = security->image_size;
  if (!image_size || image_size > kMaxImageSize) {
    return false;
  }

  uint8_t session_key[16];
  aes_decrypt_buffer(key, reinterpret_cast<const uint8_t*>(security->aes_key),
                     16, session_key, 16);

  const uint8_t* source = xex.data + header_size;
  size_t source_size = xex.size - header_size;
  std::vector<uint8_t> decrypted;
  switch (uint32_t(format->encryption_type)) {
    case XEX_ENCRYPTION_NONE:
      break;
    case XEX_ENCRYPTION_NORMAL:
      // One CBC stream over the whole payload, as the loader decrypts it.
      decrypted.resize(source_size);
      aes_decrypt_buffer(session_key, source, source_size, decrypted.data(),
                         source_size);
      source = decrypted.data();
      break;
    default:
      return false;
  }

  image.assign(image_size, 0);
  switch (uint32_t(format->compression_type)) {
    case XEX_COMPRESSION_NONE:
      std::memcpy(image.data(), source, std::min<size_t>(source_size, image_size));
      break;
    case XEX_COMPRESSION_BASIC: {
      const uint32_t info_size = format->info_size;
      if (info_size < 8 ||
          reinterpret_cast<const uint8_t*>(format) + info_size >
              xex.data + header_size) {
        return false;
      }
      const uint32_t block_count = (info_size - 8) / 8;
      size_t s = 0;
      size_t d = 0;
      for (uint32_t i = 0; i < block_count; ++i) {
        const uint32_t data_size = format->compression_info.basic.blocks[i].data_size;
        const uint32_t zero_size = format->compression_info.basic.blocks[i].zero_size;
        if (data_size > source_size - s || d > image_size ||
            data_size > image_size - d) {
          return false;
        }
        std::memcpy(image.data() + d, source + s, data_size);
        s += data_size;
        d += size_t(data_size) + zero_size;
      }
      break;
    }
    case XEX_COMPRESSION_NORMAL: {
      // Blocks: 4-byte size and 20-byte hash of the next block, then chunks
      // of 2-byte length + LZX data, ending with a zero length.
      std::vector<uint8_t> compressed;
      compressed.reserve(source_size);
      const xex2_compressed_block_info* block =
          &format->compression_info.normal.first_block;
      size_t offset = 0;
      uint32_t block_size = block->block_size;
      while (block_size) {
        if (block_size < 24 || block_size > source_size - offset) {
          return false;
        }
        const uint8_t* p = source + offset;
        const uint8_t* end = p + block_size;
        const uint32_t next_size = xe::load_and_swap<uint32_t>(p);
        p += 24;
        while (p + 2 <= end) {
          const size_t chunk = (size_t(p[0]) << 8) | p[1];
          p += 2;
          if (!chunk) {
            break;
          }
          if (chunk > size_t(end - p)) {
            return false;
          }
          compressed.insert(compressed.end(), p, p + chunk);
          p += chunk;
        }
        offset += block_size;
        block_size = next_size;
      }
      if (lzx_decompress(compressed.data(), compressed.size(), image.data(),
                         image.size(),
                         format->compression_info.normal.window_size, nullptr,
                         0) != 0) {
        KIWI_TITLE_FAIL("LZX failed: %zu compressed bytes, window %u",
                        compressed.size(),
                        uint32_t(format->compression_info.normal.window_size));
        return false;
      }
      break;
    }
    default:
      return false;
  }
  const bool ok = image.size() >= 2 && image[0] == 'M' && image[1] == 'Z';
  if (!ok) {
    KIWI_TITLE_FAIL("no MZ: enc %u comp %u", uint32_t(format->encryption_type),
                    uint32_t(format->compression_type));
  }
  return ok;
}

struct TitleInfo {
  std::string name;
  std::vector<uint8_t> icon;
};

std::optional<TitleInfo> ReadXexTitleInfo(XexView xex, XLanguage language) {
  if (xex.size < sizeof(xex2_header)) {
    return std::nullopt;
  }
  auto header = reinterpret_cast<const xex2_header*>(xex.data);
  if (header->magic != kXex2Magic || header->header_size > xex.size) {
    return std::nullopt;
  }
  xex2_opt_execution_info* execution = nullptr;
  xex2_opt_resource_info* resources = nullptr;
  if (!GetXexOptHeader(header, XEX_HEADER_EXECUTION_INFO, &execution) ||
      !GetXexOptHeader(header, XEX_HEADER_RESOURCE_INFO, &resources)) {
    KIWI_TITLE_FAIL("no execution or resource header");
    return std::nullopt;
  }
  const auto* header_end = xex.data + header->header_size;
  if (reinterpret_cast<const uint8_t*>(execution) + sizeof(*execution) >
          header_end ||
      reinterpret_cast<const uint8_t*>(resources) + 4 > header_end) {
    return std::nullopt;
  }
  const std::string wanted = fmt::format("{:08X}", uint32_t(execution->title_id));
  const xex2_resource* resource = nullptr;
  const uint32_t count = (resources->size - 4) / sizeof(xex2_resource);
  for (uint32_t i = 0; i < count; ++i) {
    const auto* candidate = &resources->resources[i];
    if (reinterpret_cast<const uint8_t*>(candidate + 1) > header_end) {
      break;
    }
    if (std::string_view(candidate->name, strnlen(candidate->name, 8)) ==
        wanted) {
      resource = candidate;
      break;
    }
  }
  if (!resource) {
    KIWI_TITLE_FAIL("no %s resource among %u", wanted.c_str(), count);
    return std::nullopt;
  }

  auto security = reinterpret_cast<const xex2_security_info*>(
      xex.data + header->security_offset);
  uint32_t base_address = security->load_address;
  // Keys ending in 0x01 hold their value inline: this yields a pointer to it.
  xe::be<uint32_t>* base_override = nullptr;
  if (GetXexOptHeader(header, XEX_HEADER_IMAGE_BASE_ADDRESS, &base_override) &&
      base_override) {
    base_address = *base_override;
  }

  std::vector<uint8_t> image;
  if (!UnpackImage(xex, kRetailKey, image) &&
      !UnpackImage(xex, kDevkitKey, image)) {
    KIWI_TITLE_FAIL("could not unpack the image");
    return std::nullopt;
  }
  const uint32_t address = resource->address;
  const uint32_t size = resource->size;
  if (address < base_address || address - base_address > image.size() ||
      size > image.size() - (address - base_address)) {
    KIWI_TITLE_FAIL("resource %08X+%X outside image at %08X size %zX", address,
                    size, base_address, image.size());
    return std::nullopt;
  }
  kernel::xam::SpaInfo spa(
      std::span<uint8_t>(image.data() + (address - base_address), size));
  kernel::util::GameInfoDatabase database(&spa);
  if (!database.IsValid()) {
    KIWI_TITLE_FAIL("invalid XDBF");
    return std::nullopt;
  }
  TitleInfo info;
  info.name = database.GetTitleName(language);
  if (info.name.empty()) {
    info.name = database.GetTitleName();
  }
  info.icon = database.GetIcon();
  return info;
}

std::optional<TitleInfo> ReadTitleInfo(const std::filesystem::path& path,
                                       XLanguage language) {
  auto mapping = MappedMemory::Open(path, MappedMemory::Mode::kRead);
  if (!mapping || mapping->size() < 4) {
    KIWI_TITLE_FAIL("could not map %s", path.string().c_str());
    return std::nullopt;
  }
  const uint8_t* data = mapping->data();
  const size_t size = mapping->size();
  if (xe::load_and_swap<uint32_t>(data) == kXex2Magic) {
    return ReadXexTitleInfo({data, size}, language);
  }
  auto partition = vfs::GdfxFindPartition(data, size);
  if (!partition) {
    KIWI_TITLE_FAIL("no GDFX partition");
    return std::nullopt;
  }
  auto xex = vfs::GdfxFindFile(data, size, *partition, "default.xex");
  if (!xex || xex->offset > size || xex->length > size - xex->offset) {
    KIWI_TITLE_FAIL("no default.xex");
    return std::nullopt;
  }
  return ReadXexTitleInfo({data + xex->offset, xex->length}, language);
}

std::string JavaString(JNIEnv* env, jstring value) {
  if (!value) {
    return {};
  }
  const char* chars = env->GetStringUTFChars(value, nullptr);
  std::string result(chars ? chars : "");
  if (chars) {
    env->ReleaseStringUTFChars(value, chars);
  }
  return result;
}

}  // namespace

// Returns the title name (empty if none was found) and writes the icon PNG,
// if the title has one, to icon_path.
extern "C" JNIEXPORT jstring JNICALL
Java_dev_kiwi360_frontend_library_NativeTitleInfo_read(JNIEnv* env,
                                                       jclass clazz,
                                                       jstring path,
                                                       jstring locale,
                                                       jstring icon_path) {
  const auto info =
      ReadTitleInfo(xe::to_path(JavaString(env, path)),
                    XLanguageFromLocaleCode(JavaString(env, locale)));
  if (!info) {
    return env->NewStringUTF("");
  }
  if (!info->icon.empty()) {
    const auto icon_file = xe::to_path(JavaString(env, icon_path));
    if (FILE* file = xe::filesystem::OpenFile(icon_file, "wb")) {
      std::fwrite(info->icon.data(), 1, info->icon.size(), file);
      std::fclose(file);
    }
  }
  return env->NewStringUTF(info->name.c_str());
}
