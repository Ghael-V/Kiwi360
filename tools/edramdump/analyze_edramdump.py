# SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
# SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
"""Compares the EDRAM dumps written by debug.kiwi.dump_edram.

Each dump is the whole EDRAM (2048 tiles of 80x16 32-bit samples, guest
layout) copied after a transfer into a depth render target. This views the
region base..base+rows*pitch as an image (1x layout: one sample per pixel) and
reports where the depth bits (upper 24) change between consecutive dumps, plus
writes PNGs of the depth and of the differences.

usage: analyze_edramdump.py <dir> <session> [base_tiles] [pitch_tiles] [tile_rows]
"""

import pathlib
import sys

import numpy as np
from PIL import Image

TILE_W, TILE_H = 80, 16
TILE_SAMPLES = TILE_W * TILE_H


def load(path, base, pitch, rows):
    words = np.fromfile(path, dtype="<u4")
    tiles = words.reshape(-1, TILE_H, TILE_W)
    region = tiles[base:base + pitch * rows]
    image = region.reshape(rows, pitch, TILE_H, TILE_W)
    image = image.transpose(0, 2, 1, 3).reshape(rows * TILE_H, pitch * TILE_W)
    return image


def depth_png(depth24, path):
    valid = depth24[depth24 > 0]
    lo = float(valid.min()) if valid.size else 0.0
    hi = float(valid.max()) if valid.size else 1.0
    scaled = (depth24.astype(np.float64) - lo) / max(hi - lo, 1.0)
    Image.fromarray((np.clip(scaled, 0, 1) * 255).astype(np.uint8)).save(path)


def main():
    directory = pathlib.Path(sys.argv[1])
    session = int(sys.argv[2])
    base = int(sys.argv[3]) if len(sys.argv) > 3 else 0
    pitch = int(sys.argv[4]) if len(sys.argv) > 4 else 16
    rows = int(sys.argv[5]) if len(sys.argv) > 5 else 45
    index = (directory / f"s{session:02}_index.txt").read_text().splitlines()
    out = directory / f"s{session:02}_analysis"
    out.mkdir(exist_ok=True)
    previous = None
    for line in index:
        number = int(line.split()[0])
        image = load(directory / f"s{session:02}_{number:02}.bin", base, pitch,
                     rows)
        depth = image >> 8
        stencil = image & 0xFF
        depth_png(depth, out / f"{number:02}_depth.png")
        summary = (f"{number:02} nonzero_depth={np.count_nonzero(depth)} "
                   f"stencil_values={np.unique(stencil).size}")
        if previous is not None:
            changed = (depth != (previous >> 8))
            stencil_changed = (stencil != (previous & 0xFF))
            ys, xs = np.nonzero(changed)
            box = (f"x {xs.min()}..{xs.max()} y {ys.min()}..{ys.max()}"
                   if ys.size else "-")
            tiles_changed = np.unique((ys // TILE_H) * pitch + xs // TILE_W)
            summary += (f" depth_changed={ys.size} ({box}) tiles="
                        f"{tiles_changed.size} stencil_changed="
                        f"{np.count_nonzero(stencil_changed)}")
            Image.fromarray(changed.astype(np.uint8) * 255).save(
                out / f"{number:02}_depth_diff.png")
        print(summary)
        print("    " + line[3:][:400])
        previous = image


if __name__ == "__main__":
    main()
