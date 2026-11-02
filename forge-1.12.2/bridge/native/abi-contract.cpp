// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
#include <cstddef>
#include "remix_c.h"

static_assert(sizeof(remixapi_HardcodedVertex) == 64, "Java vertex stride must match C API");
static_assert(sizeof(remixapi_Transform) == 48, "Java transform stride must match C API");
static_assert(offsetof(remixapi_HardcodedVertex, normal) == 12, "Vertex normal offset changed");
static_assert(offsetof(remixapi_HardcodedVertex, texcoord) == 24, "Vertex UV offset changed");
static_assert(offsetof(remixapi_HardcodedVertex, color) == 32, "Vertex color offset changed");
