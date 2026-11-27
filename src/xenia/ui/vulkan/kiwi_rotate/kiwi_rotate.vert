// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

// Present pre-rotation: one triangle covering the swapchain image, carrying for
// each pixel where it comes from in the logically oriented source image. The
// swapchain is rotated by `rotation` quarter turns clockwise against the
// source, as VkSwapchainCreateInfoKHR::preTransform says.

#version 460 core

layout(push_constant) uniform PushConstants {
    uint rotation;
};

layout(location = 0) out vec2 source_position;

void main() {
    // (0, 0), (2, 0), (0, 2): the triangle overhangs the image on two sides.
    vec2 position = vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2));
    gl_Position = vec4(position * 2.0 - 1.0, 0.0, 1.0);
    // Inverse of rotating the source clockwise, in coordinates with y down.
    if (rotation == 1u) {
        source_position = vec2(position.y, 1.0 - position.x);
    } else if (rotation == 2u) {
        source_position = 1.0 - position;
    } else if (rotation == 3u) {
        source_position = vec2(1.0 - position.y, position.x);
    } else {
        source_position = position;
    }
}
