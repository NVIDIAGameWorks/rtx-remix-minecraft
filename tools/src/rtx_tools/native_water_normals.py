# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Developer-only invocation of the water-normal generator exported by remixbridge.dll."""

from __future__ import annotations

import ctypes
import hashlib
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageChops, ImageStat


@dataclass(frozen=True)
class GenerationTimings:
    generation_seconds: float
    total_seconds: float


@dataclass(frozen=True)
class GeneratedTexture:
    data: ctypes.Array[ctypes.c_ubyte]
    timings: GenerationTimings


@dataclass(frozen=True)
class Comparison:
    bytes: int
    sha256: str
    reference_sha256: str
    pillow_rms: tuple[float, ...]
    pillow_max: tuple[int, ...]


def generate(bridge: Path, seed: int = 225) -> GeneratedTexture:
    if not bridge.is_file():
        raise RuntimeError(f"Bridge DLL does not exist: {bridge}")

    library = ctypes.CDLL(str(bridge))
    try:
        size_entry = library.remixbridge_water_normals_texture_size
        entry = library.remixbridge_generate_water_normals
    except AttributeError as exc:
        raise RuntimeError(f"Bridge DLL has no water-normal generator export: {bridge}") from exc
    size_entry.argtypes = []
    size_entry.restype = ctypes.c_size_t
    entry.argtypes = [
        ctypes.c_void_p,
        ctypes.c_size_t,
        ctypes.c_uint64,
        ctypes.POINTER(ctypes.c_double),
        ctypes.c_size_t,
        ctypes.c_char_p,
        ctypes.c_size_t,
    ]
    entry.restype = ctypes.c_int

    size = size_entry()
    data = (ctypes.c_ubyte * size)()
    timings = (ctypes.c_double * 2)()
    error = ctypes.create_string_buffer(1024)
    status = entry(data, size, seed & 0xFFFFFFFFFFFFFFFF, timings, len(timings), error, len(error))
    if status:
        detail = error.value.decode("utf-8", errors="replace") or "no error detail"
        raise RuntimeError(f"Native water-normal generation failed ({status}): {detail}")
    return GeneratedTexture(
        data=data,
        timings=GenerationTimings(
            generation_seconds=timings[0],
            total_seconds=timings[1],
        ),
    )


def compare(texture: GeneratedTexture, reference: Path) -> Comparison:
    if not reference.is_file():
        raise RuntimeError(f"Reference DDS does not exist: {reference}")

    generated = bytes(texture.data)
    generated_hash = hashlib.sha256(generated).hexdigest().upper()
    reference_hash = hashlib.sha256()
    with reference.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            reference_hash.update(chunk)

    Image.MAX_IMAGE_PIXELS = None
    with Image.open(reference) as expected, Image.frombytes("LA", (12 * 1024, 10 * 1024), generated) as raw:
        red, green = raw.split()
        blue = Image.new("L", raw.size)
        actual = Image.merge("RGB", (red, green, blue))
        if expected.mode != actual.mode or expected.size != actual.size:
            raise RuntimeError(
                f"Pillow image mismatch: reference {expected.mode} {expected.size}, "
                f"generated {actual.mode} {actual.size}"
            )
        difference = ImageChops.difference(expected, actual)
        statistics = ImageStat.Stat(difference)
        rms = tuple(statistics.rms)
        maximum = tuple(high for _, high in statistics.extrema)

    return Comparison(
        bytes=len(generated),
        sha256=generated_hash,
        reference_sha256=reference_hash.hexdigest().upper(),
        pillow_rms=rms,
        pillow_max=maximum,
    )
