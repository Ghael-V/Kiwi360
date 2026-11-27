// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Writes, for each pixel of a row, the fragment depth that the EDRAM ownership
// transfer shaders write for a guest 24-bit unorm depth value (see
// edram_transfer_shader.cc): (n + (n >> 23)) / 2^24. The probe reads the
// stored values back to check that the driver turns them into n.
#version 450
layout(push_constant) uniform Values {
  uint depth24[8];
} values;
void main() {
  uint n = values.depth24[min(uint(gl_FragCoord.x), 7u)];
  gl_FragDepth = float(n + (n >> 23u)) * (1.0 / 16777216.0);
}
