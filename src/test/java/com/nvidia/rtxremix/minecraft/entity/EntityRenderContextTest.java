/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import net.minecraft.util.Hand;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityRenderContextTest {

    private final EntityRenderContext context = EntityRenderContext.get();

    @BeforeEach
    @AfterEach
    void closeAnyOpenCapture() {
        context.reset();
    }

    @Test
    void nestedBeginKeepsTheOuterPassFlagAndBoneCounter() {
        // A spawner's display entity renders through the dispatcher from inside
        // the block entity's capture; its bones must land in the outer capture.
        context.begin(true);
        context.pushBone(new Matrix4f());
        context.pushBone(new Matrix4f());

        context.begin(false);
        context.pushBone(new Matrix4f());
        context.end();

        assertTrue(context.isActive());
        assertTrue(context.isRemixPass(), "the inner boundary must not overwrite the pass flag");
        assertEquals(3, context.getBoneCount(), "inner bones append to the outer capture");

        context.end();
        assertFalse(context.isActive());
        assertFalse(context.isRemixPass());
    }

    @Test
    void depthZeroBeginResetsBonesAndItemState() {
        // State written outside any boundary must not leak into the next capture.
        context.setActiveHand(Hand.MAIN_HAND);
        context.setBlockItemLayer(BlockTextureManager.L_GLASS);
        context.pushBone(new Matrix4f());

        context.begin(true);

        assertEquals(0, context.getBoneCount());
        assertEquals(EntityRenderContext.NO_BLOCK_ITEM, context.getBlockItemLayer());
        context.pushBone(new Matrix4f());
        assertNull(context.handForTransform(0), "the active hand was cleared before the first bone");
    }

    @Test
    void resetClosesAnyDepthAndKeepsTheBoneCountReadable() {
        context.begin(true);
        context.begin(true);
        context.pushBone(new Matrix4f());

        context.reset();

        assertFalse(context.isActive());
        assertFalse(context.isRemixPass());
        assertEquals(1, context.getBoneCount(), "the owner snapshots bones after the capture closes");

        context.begin(true);
        assertEquals(0, context.getBoneCount(), "the next outermost begin restarts numbering");
    }

    @Test
    void endAtDepthZeroIsHarmless() {
        context.end();
        assertFalse(context.isActive());

        // Depth did not go negative: one begin still opens, one end still closes.
        context.begin(true);
        assertTrue(context.isActive());
        context.end();
        assertFalse(context.isActive());
    }

    @Test
    void nestedItemScopesRestoreFactsAndTheOuterEmissionGuard() {
        ItemCaptureData outer = new ItemCaptureData();
        ItemCaptureData inner = new ItemCaptureData();
        context.begin(true);
        assertNull(context.enterItemCapture(outer));
        assertTrue(context.claimItemEffects());
        assertFalse(context.claimItemEffects(), "multiple model layers must not duplicate flames");

        boolean emittedBefore = context.hasEmittedItemEffects();
        ItemCaptureData previous = context.enterItemCapture(inner);
        try {
            assertSame(outer, previous);
            assertSame(inner, context.getItemCaptureData());
            assertTrue(context.claimItemEffects(), "a nested item owns its own effects");
        } finally {
            context.restoreItemCapture(previous, emittedBefore);
        }

        assertSame(outer, context.getItemCaptureData());
        assertFalse(context.claimItemEffects(), "returning to the outer item must not emit twice");
    }

    @Test
    void throwingItemRenderRestoresThePreviousScope() {
        ItemCaptureData outer = new ItemCaptureData();
        context.begin(true);
        context.enterItemCapture(outer);
        assertThrows(IllegalStateException.class, () -> {
            boolean emittedBefore = context.hasEmittedItemEffects();
            ItemCaptureData previous = context.enterItemCapture(new ItemCaptureData());
            try {
                context.claimItemEffects();
                throw new IllegalStateException("renderer failed");
            } finally {
                context.restoreItemCapture(previous, emittedBefore);
            }
        });
        assertSame(outer, context.getItemCaptureData());
        assertTrue(context.claimItemEffects(), "inner failure must not consume the outer effect");
    }

    @Test
    void resetDropsTheItemScopeAndEmissionGuard() {
        context.begin(true);
        context.enterItemCapture(new ItemCaptureData());
        assertTrue(context.claimItemEffects());
        context.reset();
        assertNull(context.getItemCaptureData());
        assertFalse(context.hasEmittedItemEffects());
        assertFalse(context.claimItemEffects());
        context.begin(true);
        context.enterItemCapture(new ItemCaptureData());
        assertTrue(context.claimItemEffects());
    }
}
