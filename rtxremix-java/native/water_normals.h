/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace water_normals {

struct Stats {
    double slopeRms;
    double encodedMax;
    double generationSeconds;
    double elapsedSeconds;
};

// Size of the canonical 12x10 RG8_UNORM flipbook.
std::size_t textureSize();

// Generates the canonical 120-frame, 12x10, 1024-pixel RG8_UNORM flipbook
// directly into caller-owned memory.
// This function is independent of the Remix runtime and does not load d3d9.dll.
int generate(
    std::uint8_t* output,
    std::size_t outputSize,
    std::uint64_t seed,
    Stats* stats,
    std::string* error);

} // namespace water_normals

#if defined(_WIN32)
#define REMIXBRIDGE_EXPORT extern "C" __declspec(dllexport)
#else
#define REMIXBRIDGE_EXPORT extern "C"
#endif

REMIXBRIDGE_EXPORT std::size_t remixbridge_water_normals_texture_size();

// Plain entry point for developer tooling. Returns zero on success. The caller
// owns output, which must hold remixbridge_water_normals_texture_size() bytes.
// On failure, error receives a NUL-terminated UTF-8 message when errorCapacity
// is nonzero.
REMIXBRIDGE_EXPORT int remixbridge_generate_water_normals(
    void* output,
    std::size_t outputSize,
    std::uint64_t seed,
    double* timings,
    std::size_t timingCount,
    char* error,
    std::size_t errorCapacity);
