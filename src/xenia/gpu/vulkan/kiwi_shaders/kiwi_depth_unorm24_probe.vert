// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Full-target triangle for the 24-bit unorm depth write probe.
#version 450
void main() {
  vec2 position = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
  gl_Position = vec4(position * 2.0 - 1.0, 0.0, 1.0);
}
