# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Generates a BC5 DDS reference for the animated water normal flipbook.

The surface is a linear deep-water ocean in the manner of Tessendorf's "Simulating Ocean Water": Gaussian random
amplitudes under an isotropic Phillips spectrum on a periodic grid, so every frame tiles, with each wave's angular
frequency snapped to a whole number of cycles per loop, so the frame after the last is exactly the first. Normals come
from the spectrum's analytic slope. The frames fill a sheet row by row, which is the sprite-sheet order
BlockTextureManager plays, and the reference sheet is stored as BC5 with a full mip chain. The game instead generates
raw RG8 pixels through the native bridge and uploads them directly to Remix; this implementation remains as an
independent visual-regression oracle.

The normals are written in Remix's internal encoding:
octahedral, in a tangent space whose +Y points down the image, the direction of increasing v along which Remix builds a
surface's bitangent.

Randomness comes from SplitMix64 rather than numpy's generators, whose streams numpy does not promise to keep, so a
seed names the same surface across numpy versions.
"""

from __future__ import annotations

import math
import struct
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path

import numpy as np

# BlockTextureManager's spriteSheetCol, spriteSheetRow, and spriteSheetFps, and the world size of one frame
# (WATER_TILE_SIZE, in blocks, which the surface treats as metres).
SHEET_COLS = 12
SHEET_ROWS = 10
SHEET_FPS = 20
TILE_METRES = 10.0

GRAVITY = 9.81
DXGI_FORMAT_BC5_UNORM = 83


@dataclass(frozen=True)
class WaterParams:
    # Of the seeds tried, 225's longest waves share the slope most like the water this replaced.
    seed: int = 225
    frame_size: int = 1024
    cols: int = SHEET_COLS
    rows: int = SHEET_ROWS
    fps: int = SHEET_FPS
    tile_metres: float = TILE_METRES
    # Waves move at this fraction of real deep-water speed.
    speed: float = 0.72
    # Wave height falls with wavenumber k as k^-spectrum_power. Phillips's 4 gives every octave of wavelengths the same
    # share of the slope; more keeps the slope in the long waves and calms the short ones.
    spectrum_power: float = 4.2
    # Waves much longer than swell_metres or shorter than ripple_metres fade out.
    swell_metres: float = 3.0
    ripple_metres: float = 0.016
    # Root-mean-square slope along each axis, which sets how strongly the normals tilt.
    slope_rms: float = 0.0325

    @property
    def frames(self) -> int:
        return self.cols * self.rows


def splitmix64(seed: int, count: int) -> np.ndarray:
    """The first `count` outputs of SplitMix64 seeded with `seed`, as uint64."""
    with np.errstate(over="ignore"):
        z = np.uint64(seed & 0xFFFFFFFFFFFFFFFF) + np.arange(1, count + 1, dtype=np.uint64) * np.uint64(
            0x9E3779B97F4A7C15
        )
        z = (z ^ (z >> np.uint64(30))) * np.uint64(0xBF58476D1CE4E5B9)
        z = (z ^ (z >> np.uint64(27))) * np.uint64(0x94D049BB133111EB)
    return z ^ (z >> np.uint64(31))


def gaussian(seed: int, shape: tuple[int, ...]) -> np.ndarray:
    """Standard normal samples from SplitMix64 by the Box-Muller transform."""
    count = math.prod(shape)
    bits = splitmix64(seed, 2 * count)
    u = ((bits >> np.uint64(11)).astype(np.float64) + 0.5) * 2.0**-53
    return (np.sqrt(-2.0 * np.log(u[:count])) * np.cos(2.0 * np.pi * u[count:])).reshape(shape)


@dataclass(frozen=True)
class Ocean:
    kx: np.ndarray
    ky: np.ndarray
    h0: np.ndarray
    h0_conj_neg: np.ndarray
    cycles: np.ndarray
    frames: int


def build_ocean(params: WaterParams) -> Ocean:
    """The surface's wave amplitudes at time zero and each wave's whole number of cycles per loop."""
    n = params.frame_size
    k1 = 2.0 * np.pi / params.tile_metres * np.fft.fftfreq(n, 1.0 / n)
    kx, ky = np.meshgrid(k1, k1)
    k = np.hypot(kx, ky)
    spectrum = np.zeros_like(k)
    live = k > 0
    # The Nyquist row and column have no consistent slope, so they carry nothing.
    live[n // 2, :] = False
    live[:, n // 2] = False
    kl = k[live]
    spectrum[live] = (
        np.exp(-1.0 / (kl * params.swell_metres) ** 2 - (kl * params.ripple_metres) ** 2) / kl**params.spectrum_power
    )

    xi = gaussian(params.seed, (2, n, n))
    h0 = (xi[0] + 1j * xi[1]) * np.sqrt(spectrum / 2.0)
    h0_conj_neg = np.conj(np.roll(np.flip(h0, (0, 1)), 1, (0, 1)))

    # Scale to the requested slope. Averaged over the loop, a wave's power is |h0(k)|^2 + |h0(-k)|^2.
    power = np.abs(h0) ** 2 + np.abs(h0_conj_neg) ** 2
    slope_var = (np.sum(kx**2 * power) + np.sum(ky**2 * power)) / (2.0 * n**4)
    scale = params.slope_rms / math.sqrt(slope_var)

    loop_seconds = params.frames / params.fps
    omega = params.speed * np.sqrt(GRAVITY * k)
    cycles = np.where(live, np.maximum(1.0, np.round(omega * loop_seconds / (2.0 * np.pi))), 0.0)
    return Ocean(kx, ky, h0 * scale, h0_conj_neg * scale, cycles, params.frames)


def slopes(ocean: Ocean, frame: int) -> np.ndarray:
    """The surface's slope along image columns and down image rows at `frame`, shape (n, n, 2). Frame `frames` is
    frame 0 again."""
    phase = np.exp(2j * np.pi * ocean.cycles * (frame / ocean.frames))
    h = ocean.h0 * phase + ocean.h0_conj_neg * np.conj(phase)
    # Both slopes are real, so one inverse transform carries dh/dx in its real part and dh/dy in its imaginary part.
    s = np.fft.ifft2(1j * ocean.kx * h - ocean.ky * h)
    return np.stack([s.real, s.imag], -1)


def octahedral(slope: np.ndarray) -> np.ndarray:
    """The octahedral encoding, in [-1, 1], of the surface normal (-dh/dx, -dh/dy, 1) with +Y down the image."""
    return -slope / (np.sum(np.abs(slope), -1, keepdims=True) + 1.0)


def halve(a: np.ndarray) -> np.ndarray:
    """A 2x2 box downsample that drops an odd last row or column and keeps a dimension of one."""
    h, w = a.shape[:2]
    if h > 1:
        a = a[: h // 2 * 2].reshape(h // 2, 2, *a.shape[1:]).mean(1)
    if w > 1:
        a = a[:, : w // 2 * 2].reshape(a.shape[0], w // 2, 2, *a.shape[2:]).mean(2)
    return a


def mip_count(width: int, height: int) -> int:
    return max(width, height).bit_length()


def mip_dims(width: int, height: int) -> Iterator[tuple[int, int]]:
    for level in range(mip_count(width, height)):
        yield max(1, width >> level), max(1, height >> level)


def _encode_channel(v: np.ndarray) -> np.ndarray:
    """BC4 blocks for values in [0, 255], shape (..., 16) -> (..., 8) uint8."""
    a0 = np.clip(np.round(v.max(-1)), 0, 255)
    a1 = np.clip(np.round(v.min(-1)), 0, 255)
    # a0 > a1 selects the eight-value palette; a0 == a1 decodes index 0 as a0 either way.
    weights = np.array([0, 7, 1, 2, 3, 4, 5, 6], np.float64) / 7.0
    palette = a0[..., None] * (1.0 - weights) + a1[..., None] * weights
    index = np.argmin(np.abs(v[..., :, None] - palette[..., None, :]), -1).astype(np.uint64)
    bits = np.zeros(v.shape[:-1], np.uint64)
    for i in range(16):
        bits |= index[..., i] << np.uint64(3 * i)
    out = np.empty((*v.shape[:-1], 8), np.uint8)
    out[..., 0] = a0
    out[..., 1] = a1
    for j in range(6):
        out[..., 2 + j] = (bits >> np.uint64(8 * j)) & np.uint64(0xFF)
    return out


def encode_bc5(xy: np.ndarray) -> np.ndarray:
    """BC5 UNORM blocks, shape (blocks_high, blocks_wide, 16), for two channels in [-1, 1] of shape (h, w, 2)."""
    h, w = xy.shape[:2]
    bh, bw = -(-h // 4), -(-w // 4)
    v = np.pad((xy * 0.5 + 0.5) * 255.0, ((0, bh * 4 - h), (0, bw * 4 - w), (0, 0)), mode="edge")
    blocks = v.reshape(bh, 4, bw, 4, 2).transpose(0, 2, 1, 3, 4).reshape(bh, bw, 16, 2)
    return np.concatenate([_encode_channel(blocks[..., 0]), _encode_channel(blocks[..., 1])], -1)


def dds_header(width: int, height: int, mips: int, dxgi_format: int) -> bytes:
    """A DX10-extended DDS header for a single 2D block-compressed texture."""
    linear_size = -(-width // 4) * -(-height // 4) * 16
    flags = 0x1 | 0x2 | 0x4 | 0x1000 | 0x20000 | 0x80000  # caps, height, width, pixel format, mip count, linear size
    caps = 0x8 | 0x1000 | 0x400000  # complex, texture, mipmap
    header = struct.pack("<7I44x", 124, flags, height, width, linear_size, 1, mips)
    pixel_format = struct.pack("<2I4s5I", 32, 0x4, b"DX10", 0, 0, 0, 0, 0)
    tail = struct.pack("<5I", caps, 0, 0, 0, 0)
    dx10 = struct.pack("<5I", dxgi_format, 3, 0, 1, 0)
    return b"DDS " + header + pixel_format + tail + dx10


@dataclass(frozen=True)
class FlipbookStats:
    slope_rms: float
    encoded_max: float
    # BC5 error at full resolution, in 8-bit steps.
    encode_rms: float


def write_flipbook(path: Path, params: WaterParams) -> FlipbookStats:
    """Generates the flipbook and writes it to `path` as a BC5 DDS."""
    n, cols, rows = params.frame_size, params.cols, params.rows
    width, height = cols * n, rows * n
    levels = list(mip_dims(width, height))
    # Levels where a frame still spans whole 4x4 blocks are encoded frame by frame; the rest collect in a small float
    # sheet that is encoded once every frame is in.
    blocked = [lvl for lvl, _ in enumerate(levels) if (n >> lvl) >= 4]
    first_tail = len(blocked)
    mip_blocks = [np.empty((-(-h // 4), -(-w // 4), 16), np.uint8) for w, h in levels[:first_tail]]
    tail_size = n >> first_tail
    tail_sheet = np.empty((rows * tail_size, cols * tail_size, 2))

    ocean = build_ocean(params)
    slope_sq = 0.0
    xy_max = 0.0
    encode_sq = 0.0
    for frame in range(params.frames):
        row, col = divmod(frame, cols)
        slope = slopes(ocean, frame)
        slope_sq += float(np.mean(slope**2))
        xy = octahedral(slope)
        xy_max = max(xy_max, float(np.abs(xy).max()))
        for level in range(first_tail):
            size = n >> level
            blocks = encode_bc5(xy)
            if level == 0:
                encode_sq += float(np.mean((decode_bc5(blocks, size, size) - xy) ** 2))
            b = size // 4
            mip_blocks[level][row * b : (row + 1) * b, col * b : (col + 1) * b] = blocks
            xy = halve(xy)
        tail_sheet[row * tail_size : (row + 1) * tail_size, col * tail_size : (col + 1) * tail_size] = xy

    with path.open("wb") as out:
        out.write(dds_header(width, height, len(levels), DXGI_FORMAT_BC5_UNORM))
        for blocks in mip_blocks:
            out.write(blocks.tobytes())
        sheet = tail_sheet
        for _ in levels[first_tail:]:
            out.write(encode_bc5(sheet).tobytes())
            sheet = halve(sheet)
    return FlipbookStats(
        slope_rms=math.sqrt(slope_sq / params.frames),
        encoded_max=xy_max,
        encode_rms=math.sqrt(encode_sq / params.frames) * 127.5,
    )


def _decode_channel(blocks: np.ndarray) -> np.ndarray:
    a0 = blocks[..., 0].astype(np.float64)
    a1 = blocks[..., 1].astype(np.float64)
    bits = np.zeros(blocks.shape[:-1], np.uint64)
    for j in range(6):
        bits |= blocks[..., 2 + j].astype(np.uint64) << np.uint64(8 * j)
    index = np.stack([(bits >> np.uint64(3 * i)) & np.uint64(7) for i in range(16)], -1).astype(np.intp)
    eight = np.array([0, 7, 1, 2, 3, 4, 5, 6], np.float64) / 7.0
    six = np.array([0, 5, 1, 2, 3, 4, 0, 0], np.float64) / 5.0
    w = np.where((a0 > a1)[..., None], eight, six)
    palette = a0[..., None] * (1.0 - w) + a1[..., None] * w
    palette[..., 6] = np.where(a0 > a1, palette[..., 6], 0.0)
    palette[..., 7] = np.where(a0 > a1, palette[..., 7], 255.0)
    return np.take_along_axis(palette, index, -1)


def decode_bc5(blocks: np.ndarray, width: int, height: int) -> np.ndarray:
    """The two channels in [-1, 1], shape (height, width, 2), of BC5 UNORM blocks."""
    bh, bw = blocks.shape[:2]
    v = np.stack([_decode_channel(blocks[..., :8]), _decode_channel(blocks[..., 8:])], -1)
    v = v.reshape(bh, bw, 4, 4, 2).transpose(0, 2, 1, 3, 4).reshape(bh * 4, bw * 4, 2)
    return v[:height, :width] / 127.5 - 1.0
