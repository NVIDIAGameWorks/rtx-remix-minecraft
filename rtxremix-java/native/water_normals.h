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
    double generationSeconds;
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
