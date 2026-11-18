/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import java.util.NoSuchElementException;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

/**
 * Reusable iterator for a compact render-thread BuiltChunk buffer.
 */
public final class BuiltChunkArrayIterator implements ObjectListIterator<SectionRenderDispatcher.RenderSection> {
    private SectionRenderDispatcher.RenderSection[] chunks;
    private int size;
    private int index;

    public BuiltChunkArrayIterator reset(SectionRenderDispatcher.RenderSection[] chunks, int size) {
        this.chunks = chunks;
        this.size = size;
        this.index = 0;
        return this;
    }

    @Override
    public boolean hasNext() {
        return index < size;
    }

    @Override
    public SectionRenderDispatcher.RenderSection next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        return chunks[index++];
    }

    @Override
    public boolean hasPrevious() {
        return index > 0;
    }

    @Override
    public SectionRenderDispatcher.RenderSection previous() {
        if (!hasPrevious()) {
            throw new NoSuchElementException();
        }
        return chunks[--index];
    }

    @Override
    public int nextIndex() {
        return index;
    }

    @Override
    public int previousIndex() {
        return index - 1;
    }
}
