// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

// Present pre-rotation: copies the source texel under each swapchain pixel. The
// two images have the same size up to the swap of the axes, so no filtering.

#version 460 core

layout(set = 0, binding = 0) uniform sampler2D source;

layout(location = 0) in vec2 source_position;

layout(location = 0) out vec4 color;

void main() {
    ivec2 size = textureSize(source, 0);
    ivec2 texel = clamp(ivec2(source_position * vec2(size)), ivec2(0), size - 1);
    color = texelFetch(source, texel, 0);
}
