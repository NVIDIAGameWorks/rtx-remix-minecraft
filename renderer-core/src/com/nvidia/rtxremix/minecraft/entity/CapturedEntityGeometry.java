/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

/**
 * Zero-copy view of one entity layer's captured vertices and rigid parts.
 *
 * <p>Side and sprite values are opaque identity keys. The loader adapter owns
 * their concrete types; the canonical submitter only compares and returns them
 * when asking the adapter-backed geometry buffer to rebuild a rigid cuboid.
 */
public interface CapturedEntityGeometry {
    int MAX_VERTICES = 4096;

    int getPartCount();
    int getPartStart(int part);
    int getPartVertexCount(int part);
    int getPartBone(int part);
    long getPartHash(int part);
    Object getPartSides(int part);
    Object getPartSprite(int part);
    int getPartTint(int part);
    int getPartBlockItemLayer(int part);
    int cardFaceOfPartStartingAt(int start);

    int getVertexCount();
    float[] getPx();
    float[] getPy();
    float[] getPz();
    float[] getNx();
    float[] getNy();
    float[] getNz();
    float[] getTu();
    float[] getTv();
    int[] getColors();
    int[] getTexcoordBoundsMin();
    int[] getTexcoordBoundsMax();

    void clear();
    void finishOpenVertex();
    void emitCuboid(Object sides, int color, int boneIndex, Object sprite);
    void emitCuboid(
            Object sides,
            int color,
            int boneIndex,
            Object sprite,
            float shellOffset);
}
