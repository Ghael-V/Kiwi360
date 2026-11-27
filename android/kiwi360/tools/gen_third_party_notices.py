# SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
# SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
"""Builds app/src/main/assets/licenses/THIRD_PARTY_NOTICES.txt.

Lists every third-party component that ends up in the APK with the license
text taken from its own source tree, so the About screen and the release
package carry them. The list follows what libxenia-app.so links and includes
(ninja -t deps in build-edge-android), plus the app's own dependencies.

    python tools/gen_third_party_notices.py
"""

import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.dirname(HERE)
# The repository root: the app lives in android/kiwi360.
XENIA = os.path.dirname(os.path.dirname(APP))
TP = os.path.join(XENIA, "third_party")
OUT = os.path.join(APP, "app", "src", "main", "assets", "licenses",
                   "THIRD_PARTY_NOTICES.txt")


def read(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read().replace("\r\n", "\n").strip("\n")


def tp(*parts):
    return read(os.path.join(TP, *parts))


def header_block(path, start, end):
    """The lines of a header from the first matching `start` to `end`."""
    text = read(path)
    m = re.search(start + r".*?" + end, text, re.S)
    if not m:
        sys.exit("license block not found in " + path)
    return m.group(0)


def mesa_mit():
    text = read(os.path.join(HERE, "licenses", "mesa_MIT.txt"))
    return text.split("License-Text:", 1)[1].strip("\n")


LGPL_NOTE = (
    "Used under the GNU Lesser General Public License 2.1 as separate shared "
    "libraries in the APK ({libs}), which can be replaced with modified "
    "versions. Their complete source code, with the build files Kiwi360 uses, "
    "is distributed together with this APK as Kiwi360-<version>-lgpl-source.zip."
)

# (name, what it is used for, license, text)
COMPONENTS = [
    ("Xenia and Xenia Edge", "Emulator core Kiwi360 is based on", "BSD-3-Clause",
     lambda: read(os.path.join(XENIA, "LICENSE"))),
    ("FFmpeg", "XMA audio decoding", "LGPL-2.1-or-later",
     lambda: LGPL_NOTE.format(libs="libkiwi_avutil.so, libkiwi_avcodec.so, "
                              "libkiwi_avformat.so, libkiwi_swresample.so")
     + "\n\n" + tp("FFmpeg", "COPYING.LGPLv2.1")),
    ("libmspack", "LZX decompression", "LGPL-2.1",
     lambda: LGPL_NOTE.format(libs="libkiwi_mspack.so") + "\n\n"
     + tp("mspack", "COPYING.LIB")),
    ("Lemon-Ade (Mesa Turnip)", "Bundled Vulkan driver for Adreno GPUs", "MIT",
     lambda: "Mesa 3D Graphics Library, Turnip Vulkan driver, with the "
     "Lemon-Ade changes. Copyright the Mesa contributors.\n\n" + mesa_mit()),
    ("libadrenotools", "Loading custom Vulkan drivers", "BSD-2-Clause",
     lambda: tp("libadrenotools", "LICENSE")),
    ("SDL", "Platform layer", "Zlib", lambda: tp("SDL3", "LICENSE.txt")),
    ("Vulkan-Headers", "Vulkan API", "Apache-2.0 OR MIT",
     lambda: tp("Vulkan-Headers", "LICENSE.md")),
    ("Vulkan Memory Allocator", "GPU memory", "MIT",
     lambda: tp("VulkanMemoryAllocator", "LICENSE.txt")),
    ("glslang", "SPIR-V", "BSD-3-Clause and others",
     lambda: tp("glslang", "LICENSE.txt")),
    ("Capstone", "Disassembly", "BSD-3-Clause",
     lambda: tp("capstone", "LICENSES", "LICENSE.TXT")),
    ("xbyak_aarch64", "ARM64 code generation", "Apache-2.0",
     lambda: tp("xbyak_aarch64", "LICENSE")),
    ("LLVM (support headers)", "Utilities", "Apache-2.0 WITH LLVM-exception",
     lambda: tp("llvm", "LICENSE.txt")),
    ("Mbed TLS", "Cryptography", "Apache-2.0",
     lambda: tp("mbedtls", "LICENSE")),
    ("{fmt}", "Formatting", "MIT", lambda: tp("fmt", "LICENSE")),
    ("toml++", "Configuration files", "MIT",
     lambda: tp("tomlplusplus", "LICENSE")),
    ("cxxopts", "Command line options", "MIT", lambda: tp("cxxopts", "LICENSE")),
    ("date", "Dates", "MIT", lambda: tp("date", "LICENSE.txt")),
    ("Asio", "Networking", "BSL-1.0",
     lambda: tp("asio", "COPYING") + "\n\n" + tp("asio", "LICENSE_1_0.txt")),
    ("Boost.Context", "Fibers", "BSL-1.0",
     lambda: "Copyright Oliver Kowalke.\n\n" + tp("asio", "LICENSE_1_0.txt")),
    ("UTF8-CPP", "Text encoding", "BSL-1.0", lambda: tp("utfcpp", "LICENSE")),
    ("RapidJSON", "JSON", "MIT", lambda: tp("rapidjson", "license.txt")),
    ("pugixml", "XML", "MIT", lambda: tp("pugixml", "LICENSE.md")),
    ("Dear ImGui", "Debug UI", "MIT", lambda: tp("imgui", "LICENSE.txt")),
    ("discord-rpc", "Rich presence", "MIT",
     lambda: tp("discord-rpc", "LICENSE")),
    ("zstd", "Compression", "BSD-3-Clause", lambda: tp("zstd", "LICENSE")),
    ("zlib-ng", "Compression", "Zlib", lambda: tp("zlib-ng", "LICENSE.md")),
    ("Snappy", "Compression", "BSD-3-Clause", lambda: tp("snappy", "COPYING")),
    ("xxHash", "Hashing", "BSD-2-Clause", lambda: tp("xxhash", "LICENSE")),
    ("ZArchive", "Archives", "MIT-0", lambda: tp("zarchive", "LICENSE")),
    ("disruptorplus", "Queues", "MIT",
     lambda: tp("disruptorplus", "LICENSE.txt")),
    ("half", "Half-precision floats", "MIT", lambda: tp("half", "LICENSE.txt")),
    ("stb", "Images", "MIT OR Unlicense",
     lambda: header_block(os.path.join(TP, "stb", "stb_image.h"),
                          r"This software is available under 2 licenses",
                          r"-{20,}\n\*/")),
    ("AMD FidelityFX CAS", "Sharpening", "MIT",
     lambda: tp("FidelityFX-CAS", "LICENSE.txt")),
    ("AMD FidelityFX FSR", "Upscaling", "MIT",
     lambda: tp("FidelityFX-FSR", "license.txt")),
    ("NVIDIA FXAA", "Anti-aliasing", "BSD-3-Clause", lambda: tp("fxaa", "LICENSE")),
    ("miniaudio", "Audio", "Unlicense OR MIT-0",
     lambda: tp("miniaudio", "LICENSE")),
    ("MicroProfile", "Profiling", "Unlicense",
     lambda: header_block(os.path.join(TP, "microprofile", "microprofile.h"),
                          r"// This is free and unencumbered", r"unlicense\.org/>")),
    ("RenderDoc in-application API", "Debugging", "MIT",
     lambda: header_block(os.path.join(TP, "renderdoc", "renderdoc_app.h"),
                          r"/\*{10,}", r"\*{10,}/")),
    ("AndroidX, Jetpack Compose and Kotlin", "App user interface",
     "Apache-2.0",
     lambda: "Copyright The Android Open Source Project and JetBrains s.r.o.\n\n"
     + tp("Vulkan-Headers", "LICENSES", "Apache-2.0.txt")),
    ("DM Sans", "Interface font", "OFL-1.1",
     lambda: read(os.path.join(APP, "app", "src", "main", "assets", "licenses",
                               "OFL-DMSans.txt"))),
]


def main():
    rule = "=" * 72
    parts = ["Kiwi360 third-party notices\n\n"
             "Kiwi360 includes the following components, each under its own "
             "license.\n"]
    for name, use, spdx, text in COMPONENTS:
        parts.append("%s\n%s\n%s. License: %s\n%s\n\n%s\n"
                     % (rule, name, use, spdx, rule, text()))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(parts))
    print("%s: %d components, %d KB" % (OUT, len(COMPONENTS),
                                        os.path.getsize(OUT) // 1024))


if __name__ == "__main__":
    main()
