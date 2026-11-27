# SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
# SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
add_library(xenia-hid-kiwi-android STATIC)
xe_platform_sources(xenia-hid-kiwi-android ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(xenia-hid-kiwi-android PUBLIC xenia-base xenia-hid xenia-ui)
xe_target_defaults(xenia-hid-kiwi-android)
