# SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
# SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
add_library(xenia-apu-kiwi-aaudio STATIC)
xe_platform_sources(xenia-apu-kiwi-aaudio ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(xenia-apu-kiwi-aaudio PUBLIC xenia-base xenia-apu
  xenia-apu-nop aaudio)
xe_target_defaults(xenia-apu-kiwi-aaudio)
