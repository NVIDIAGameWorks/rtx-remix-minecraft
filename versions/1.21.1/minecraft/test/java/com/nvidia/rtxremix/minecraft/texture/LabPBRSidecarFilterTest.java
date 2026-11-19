/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LabPBRSidecarFilterTest {

    @Test
    void sidecarsOfAStitchedSpriteAreDroppedAndDiscarded() {
        RecordingOutput atlas = atlasOf(
                "block/stone", "block/stone_n", "block/stone_s",
                "block/oak_leaves", "block/oak_leaves_sss");

        LabPBRSidecarFilter.INSTANCE.run(null, atlas);

        assertEquals(ids("block/stone", "block/oak_leaves"), atlas.sprites());
        assertEquals(ids("block/stone_n", "block/stone_s", "block/oak_leaves_sss"), atlas.discarded);
    }

    /** Vanilla's enchanting glyph particles are lettered {@code sga_a} to {@code sga_z}, with no {@code sga}. */
    @Test
    void aSuffixedSpriteWithoutItsBaseStays() {
        RecordingOutput atlas = atlasOf("sga_m", "sga_n", "sga_s", "flame");

        LabPBRSidecarFilter.INSTANCE.run(null, atlas);

        assertEquals(ids("sga_m", "sga_n", "sga_s", "flame"), atlas.sprites());
        assertEquals(Set.of(), atlas.discarded);
    }

    @Test
    void theBaseIsLookedUpInTheSidecarsOwnNamespace() {
        RecordingOutput atlas = new RecordingOutput();
        atlas.add(ResourceLocation.withDefaultNamespace("block/stone"));
        atlas.add(ResourceLocation.fromNamespaceAndPath("other", "block/stone_n"));

        LabPBRSidecarFilter.INSTANCE.run(null, atlas);

        assertEquals(Set.of(ResourceLocation.withDefaultNamespace("block/stone"),
                ResourceLocation.fromNamespaceAndPath("other", "block/stone_n")), atlas.sprites());
    }

    @Test
    void namesThatOnlyEndInTheSuffixLettersStay() {
        RecordingOutput atlas = atlasOf(
                "block/furnace_front", "block/furnace_front_on",
                "block/gras", "block/grass",
                "block/tnt_side", "block/tnt_sides");

        LabPBRSidecarFilter.INSTANCE.run(null, atlas);

        assertEquals(6, atlas.sprites().size());
    }

    @Test
    void aTextureNamedLikeASidecarKeepsItsOwnSidecars() {
        RecordingOutput atlas = atlasOf("block/pipe_s", "block/pipe_s_n");

        LabPBRSidecarFilter.INSTANCE.run(null, atlas);

        assertEquals(ids("block/pipe_s"), atlas.sprites());
    }

    private static RecordingOutput atlasOf(String... paths) {
        RecordingOutput output = new RecordingOutput();
        for (String path : paths) {
            output.add(ResourceLocation.withDefaultNamespace(path));
        }
        return output;
    }

    private static Set<ResourceLocation> ids(String... paths) {
        Set<ResourceLocation> ids = new HashSet<>();
        for (String path : paths) {
            ids.add(ResourceLocation.withDefaultNamespace(path));
        }
        return ids;
    }

    /** The sprite list an atlas hands its sources, recording which suppliers a source discards. */
    private static final class RecordingOutput implements SpriteSource.Output {
        private final Map<ResourceLocation, SpriteSource.SpriteSupplier> suppliers = new LinkedHashMap<>();
        final Set<ResourceLocation> discarded = new HashSet<>();

        void add(ResourceLocation id) {
            add(id, new SpriteSource.SpriteSupplier() {
                @Override
                public SpriteContents apply(SpriteResourceLoader loader) {
                    throw new AssertionError("the filter must not load a sprite");
                }

                @Override
                public void discard() {
                    discarded.add(id);
                }
            });
        }

        Set<ResourceLocation> sprites() {
            return suppliers.keySet();
        }

        @Override
        public void add(ResourceLocation id, SpriteSource.SpriteSupplier supplier) {
            suppliers.put(id, supplier);
        }

        @Override
        public void removeAll(Predicate<ResourceLocation> predicate) {
            Iterator<Map.Entry<ResourceLocation, SpriteSource.SpriteSupplier>> entries = suppliers.entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry<ResourceLocation, SpriteSource.SpriteSupplier> entry = entries.next();
                if (predicate.test(entry.getKey())) {
                    entry.getValue().discard();
                    entries.remove();
                }
            }
        }
    }
}
