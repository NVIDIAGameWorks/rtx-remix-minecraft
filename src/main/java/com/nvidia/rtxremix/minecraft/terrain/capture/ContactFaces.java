/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

/**
 * Geometry for the faces of see-through blocks that touch an opaque block.
 *
 * <p>Vanilla culls such a face because the neighbour hides it. A path tracer needs it: glass that
 * sits on stone is a closed shell only if the face against the stone exists, so a ray that enters the
 * glass has a surface to leave through and the glass can absorb along the whole way. The kept face is
 * moved a hair into its block so that it is never coplanar with the neighbour's own face, which would
 * make the order of the two hits arbitrary.
 */
final class ContactFaces {

    /** Distance in blocks a kept contact face is moved into its block. */
    static final float INSET = 0.004f;

    private static final float PLANE_TOLERANCE = 1.0e-4f;

    private ContactFaces() {}

    /**
     * Moves a quad that lies on one face of a block into the block, along the face's axis.
     *
     * @param positions four xyz vertices, already offset by the block origin
     * @param axis 0 for x, 1 for y, 2 for z
     * @param sign +1 when the face looks toward +axis, -1 when it looks toward -axis
     * @param blockMin the block's minimum coordinate on that axis
     * @param inset how far to move the quad
     * @return true when the quad lies on that face of the block and was moved
     */
    static boolean insetOntoBlockFace(float[] positions, int axis, int sign, float blockMin, float inset) {
        float plane = sign > 0 ? blockMin + 1.0f : blockMin;
        for (int v = 0; v < 4; v++) {
            if (Math.abs(positions[v * 3 + axis] - plane) > PLANE_TOLERANCE) {
                return false;
            }
        }
        for (int v = 0; v < 4; v++) {
            positions[v * 3 + axis] -= sign * inset;
        }
        return true;
    }
}
