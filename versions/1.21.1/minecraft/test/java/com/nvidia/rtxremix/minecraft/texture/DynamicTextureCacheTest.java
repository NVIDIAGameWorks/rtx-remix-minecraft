/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * Pins how glyph pages are served beside the snapshot cache and which snapshots survive
 * the world clear. A page has no snapshot to capture: it is read live from its mirror,
 * and it belongs to the font rather than to a world. A snapshot follows its texture: it
 * survives while the texture is registered and goes when the texture does.
 */
class DynamicTextureCacheTest {

    @Test
    void aGlyphPageIsReadLiveAndOutlivesClear() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("minecraft", "default/0");
        GlyphPageMirror page = new GlyphPageMirror(false);
        DynamicTextureCache.onRegisterGlyphPage(id, page);

        assertTrue(DynamicTextureCache.isGlyphPage(id));
        DynamicTextureCache.Pixels empty = DynamicTextureCache.get(id);
        assertSame(page.data(), empty.data());
        assertEquals(GlyphPageMirror.PAGE_SIZE, empty.width());
        assertEquals(GlyphPageMirror.PAGE_SIZE, empty.height());
        assertEquals(0L, empty.version());

        page.blit(0, 0, 1, 1, 4, ByteBuffer.wrap(new byte[] {1, 2, 3, 4}), 4);
        DynamicTextureCache.Pixels written = DynamicTextureCache.get(id);
        assertSame(page.data(), written.data(), "the mirror is read through, not copied");
        assertEquals(page.version(), written.version());

        DynamicTextureCache.clear();
        assertTrue(DynamicTextureCache.isGlyphPage(id));
        assertSame(page.data(), DynamicTextureCache.get(id).data());
    }

    @Test
    void aPageReRegisteredUnderItsIdReplacesItsPredecessor() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("minecraft", "default/1");
        GlyphPageMirror replaced = new GlyphPageMirror(false);
        GlyphPageMirror fresh = new GlyphPageMirror(true);
        DynamicTextureCache.onRegisterGlyphPage(id, replaced);

        DynamicTextureCache.onRegisterGlyphPage(id, fresh);

        assertSame(fresh.data(), DynamicTextureCache.get(id).data());
    }

    @Test
    void clearKeepsTheSnapshotAndRegistrationOfALiveTexture() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("minecraft", "skins/live");
        AbstractTexture texture = newTexture();
        DynamicTextureCache.onRegisterTexture(id, texture, null);
        try (NativeImage image = new NativeImage(1, 1, true)) {
            assertTrue(DynamicTextureCache.captureFromUpload(texture, image));
        }
        DynamicTextureCache.Pixels captured = DynamicTextureCache.get(id);
        assertNotNull(captured);

        DynamicTextureCache.clear();

        DynamicTextureCache.Pixels kept = DynamicTextureCache.get(id);
        assertNotNull(kept, "a skin is uploaded once per session and cannot be re-captured");
        assertSame(captured.data(), kept.data());
        assertEquals(captured.version(), kept.version());
        try (NativeImage image = new NativeImage(1, 1, true)) {
            assertTrue(DynamicTextureCache.captureFromUpload(texture, image),
                    "a registration belongs to the live texture, not to the cleared snapshots");
        }
    }

    @Test
    void clearDropsAClosedImageTextureEvenWhileTheManagerKeepsItRegistered() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("minecraft", "dynamic/closed-map");
        TextureManager manager = new TextureManager(null);
        DynamicTexture texture = new DynamicTexture(new NativeImage(1, 1, true));
        manager.register(id, texture);
        DynamicTextureCache.onRegisterTexture(id, texture, texture.getPixels());
        assertNotNull(DynamicTextureCache.get(id));

        texture.close();
        assertNull(texture.getPixels());
        assertSame(texture, manager.getTexture(id));
        DynamicTextureCache.clear();

        assertNull(DynamicTextureCache.get(id));
    }

    @Test
    void clearKeepsALiveImageTextureWhenAClosedTextureSharesItsId() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("minecraft", "dynamic/replaced-map");
        DynamicTexture closed = new DynamicTexture(new NativeImage(1, 1, true));
        DynamicTextureCache.onRegisterTexture(id, closed, closed.getPixels());
        closed.close();
        try (DynamicTexture live = new DynamicTexture(new NativeImage(1, 1, true))) {
            DynamicTextureCache.onRegisterTexture(id, live, live.getPixels());
            DynamicTextureCache.Pixels captured = DynamicTextureCache.get(id);

            DynamicTextureCache.clear();

            assertSame(captured, DynamicTextureCache.get(id));
        }
    }

    @Test
    void capacityPruningDropsClosedImageTexturesWithoutWaitingForCollection() {
        ResourceLocation closedId = ResourceLocation.fromNamespaceAndPath("minecraft", "dynamic/pruned-map");
        DynamicTexture closed = new DynamicTexture(new NativeImage(1, 1, true));
        DynamicTextureCache.onRegisterTexture(closedId, closed, closed.getPixels());
        closed.close();
        List<AbstractTexture> live = new ArrayList<>();
        for (int i = 0; i < DynamicTextureCache.capacity(); i++) {
            AbstractTexture texture = newTexture();
            live.add(texture);
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("minecraft", "skins/closed-map-neighbour-" + i);
            try (NativeImage image = new NativeImage(1, 1, true)) {
                DynamicTextureCache.onRegisterTexture(id, texture, image);
            }
        }

        assertNull(closed.getPixels());
        assertNull(DynamicTextureCache.get(closedId));
        assertEquals(DynamicTextureCache.capacity(), live.size());
    }

    /**
     * Relies on the JVM collecting an unreachable texture; the assumption below skips the
     * test rather than failing it when a bounded {@code System.gc()} loop does not.
     */
    @Test
    void clearDropsTheSnapshotOfATextureTheGameReleased() throws InterruptedException {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("minecraft", "skins/released");
        AbstractTexture texture = newTexture();
        DynamicTextureCache.onRegisterTexture(id, texture, null);
        try (NativeImage image = new NativeImage(1, 1, true)) {
            assertTrue(DynamicTextureCache.captureFromUpload(texture, image));
        }
        assertNotNull(DynamicTextureCache.get(id));

        WeakReference<AbstractTexture> collected = new WeakReference<>(texture);
        texture = null;
        for (int attempt = 0; attempt < 20 && collected.get() != null; attempt++) {
            System.gc();
            Thread.sleep(10L);
        }
        assumeTrue(collected.get() == null, "the JVM did not collect the released texture");

        DynamicTextureCache.clear();

        assertNull(DynamicTextureCache.get(id),
                "a snapshot vanilla can no longer refresh has nothing to keep it alive");
    }

    @Test
    void unknownIdsHaveNoPixels() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/cow/cow.png");

        assertNull(DynamicTextureCache.get(id));
        assertFalse(DynamicTextureCache.isGlyphPage(id));
    }

    /**
     * A downloaded skin uploads once per session, so a snapshot the capacity
     * bound dropped would be gone until restart. Every texture here stays
     * strongly referenced, so none has been released and none may be pruned.
     */
    @Test
    void capacityPruningNeverDropsTheSnapshotOfALiveTexture() {
        int count = DynamicTextureCache.capacity() + 8;
        List<AbstractTexture> live = new ArrayList<>(count);
        List<ResourceLocation> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("minecraft", "skins/crowd-" + i);
            AbstractTexture texture = newTexture();
            live.add(texture);
            ids.add(id);
            DynamicTextureCache.onRegisterTexture(id, texture, null);
            try (NativeImage image = new NativeImage(1, 1, true)) {
                assertTrue(DynamicTextureCache.captureFromUpload(texture, image));
            }
        }

        for (ResourceLocation id : ids) {
            assertNotNull(DynamicTextureCache.get(id), id + " was pruned while its texture is live");
        }
        assertEquals(count, live.size());
    }

    private static AbstractTexture newTexture() {
        return new AbstractTexture() {
            @Override
            public void load(ResourceManager manager) {}
        };
    }
}
