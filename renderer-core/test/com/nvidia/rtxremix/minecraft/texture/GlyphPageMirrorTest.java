/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how an uploaded glyph lands in its page mirror. The mirror must hold what the GL
 * texture holds as the text shaders read it: a colour page keeps the uploaded channels
 * as {@code glTexSubImage2D} converts them into RGBA, an intensity page spreads red
 * across all four channels because {@code rendertype_text_intensity} samples
 * {@code .rrrr}.
 */
class GlyphPageMirrorTest {

    private static ByteBuffer bytes(int... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length);
        for (int value : values) {
            buffer.put((byte) value);
        }
        return buffer.flip();
    }

    private static int[] pixel(GlyphPageMirror page, int x, int y) {
        byte[] data = page.data();
        int index = (y * GlyphPageMirror.PAGE_SIZE + x) * 4;
        return new int[] {
                data[index] & 0xFF, data[index + 1] & 0xFF, data[index + 2] & 0xFF, data[index + 3] & 0xFF };
    }

    @Test
    void rgbaGlyphLandsInItsSlotAndSkipsTheSourceRowPadding() {
        GlyphPageMirror page = new GlyphPageMirror(false);
        // A 2x2 glyph cut from a 3-pixel-wide image: the third pixel of each row is
        // outside the glyph and must not be read.
        ByteBuffer src = bytes(
                1, 2, 3, 4, 5, 6, 7, 8, 99, 99, 99, 99,
                9, 10, 11, 12, 13, 14, 15, 16, 99, 99, 99, 99);

        page.blit(10, 20, 2, 2, 4, src, 12);

        assertArrayEquals(new int[] {1, 2, 3, 4}, pixel(page, 10, 20));
        assertArrayEquals(new int[] {5, 6, 7, 8}, pixel(page, 11, 20));
        assertArrayEquals(new int[] {9, 10, 11, 12}, pixel(page, 10, 21));
        assertArrayEquals(new int[] {13, 14, 15, 16}, pixel(page, 11, 21));
        assertArrayEquals(new int[] {0, 0, 0, 0}, pixel(page, 12, 20));
        assertArrayEquals(new int[] {0, 0, 0, 0}, pixel(page, 9, 20));
        assertArrayEquals(new int[] {0, 0, 0, 0}, pixel(page, 10, 22));
    }

    @Test
    void blitReadsFromTheSourcePosition() {
        GlyphPageMirror page = new GlyphPageMirror(false);
        ByteBuffer src = bytes(99, 99, 99, 99, 1, 2, 3, 4);
        src.position(4);

        page.blit(0, 0, 1, 1, 4, src, 4);

        assertArrayEquals(new int[] {1, 2, 3, 4}, pixel(page, 0, 0));
    }

    @Test
    void colourPageFillsMissingChannelsAsGlDoes() {
        GlyphPageMirror page = new GlyphPageMirror(false);

        page.blit(0, 0, 1, 1, 3, bytes(10, 20, 30), 3);
        page.blit(1, 0, 1, 1, 1, bytes(40), 1);
        page.blit(2, 0, 1, 1, 2, bytes(50, 60), 2);

        assertArrayEquals(new int[] {10, 20, 30, 255}, pixel(page, 0, 0), "RGB is opaque");
        assertArrayEquals(new int[] {40, 0, 0, 255}, pixel(page, 1, 0), "RED fills green and blue with 0");
        assertArrayEquals(new int[] {50, 60, 0, 255}, pixel(page, 2, 0), "RG fills blue with 0");
    }

    @Test
    void intensityPageSpreadsRedAcrossEveryChannel() {
        GlyphPageMirror page = new GlyphPageMirror(true);

        page.blit(0, 0, 1, 1, 1, bytes(200), 1);
        page.blit(1, 0, 1, 1, 4, bytes(70, 1, 2, 3), 4);

        assertArrayEquals(new int[] {200, 200, 200, 200}, pixel(page, 0, 0), "TrueType coverage");
        assertArrayEquals(new int[] {70, 70, 70, 70}, pixel(page, 1, 0),
                "an R8 page keeps only red of an RGBA upload");
    }

    @Test
    void versionsAdvanceWithEveryGlyphAndAcrossPages() {
        GlyphPageMirror first = new GlyphPageMirror(false);
        GlyphPageMirror second = new GlyphPageMirror(false);
        assertEquals(0L, first.version(), "a page with no glyphs has nothing to upload");

        first.blit(0, 0, 1, 1, 1, bytes(1), 1);
        long firstGlyph = first.version();
        second.blit(0, 0, 1, 1, 1, bytes(1), 1);
        first.blit(0, 0, 1, 1, 1, bytes(2), 1);

        assertTrue(firstGlyph > 0L);
        assertTrue(second.version() > firstGlyph,
                "a page written later is newer than one it may replace under the same id");
        assertTrue(first.version() > second.version());
    }

    @Test
    void aGlyphOutsideThePageIsRejected() {
        GlyphPageMirror page = new GlyphPageMirror(false);
        ByteBuffer twoPixels = bytes(0, 0, 0, 0, 0, 0, 0, 0);

        assertThrows(IllegalArgumentException.class,
                () -> page.blit(GlyphPageMirror.PAGE_SIZE - 1, 0, 2, 1, 4, twoPixels, 8));
        assertThrows(IllegalArgumentException.class,
                () -> page.blit(0, GlyphPageMirror.PAGE_SIZE, 1, 1, 4, twoPixels, 4));
        assertThrows(IllegalArgumentException.class,
                () -> page.blit(-1, 0, 1, 1, 4, twoPixels, 4));
        assertEquals(0L, page.version(), "a rejected upload changes nothing");
    }
}
