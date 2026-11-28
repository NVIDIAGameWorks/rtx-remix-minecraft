/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.client.renderer.SectionOcclusionGraph.Node;

/**
 * The reached-section set of a visibility walk, as flat arrays reused across walks.
 *
 * <p>Vanilla builds a new {@code LinkedHashSet} sized to the whole storage for every full
 * walk and allocates an entry per reached section; at render distance 32 that is a
 * hundred-thousand-bucket table plus thousands of entries several times a second, on the
 * visibility thread. This set keeps insertion order in one array and membership in a
 * per-index stamp, so a {@link #clear} costs nothing and an {@link #add} allocates nothing.
 *
 * <p>Membership follows vanilla's {@code ChunkInfo.equals}: two infos are the same element
 * when their sections have the same origin. A section re-originned mid-walk therefore counts
 * as a new element, as it does in vanilla, which keeps the walk's output identical. The type
 * is the one vanilla's fields declare so its readers need no change; the only operations they
 * use are {@code add}, iteration and {@code size}, and everything else fails loudly rather
 * than consulting the empty table underneath.
 *
 * <p>Single writer at a time, as vanilla's set: the visibility thread fills it, then the
 * render thread owns it after publication.
 */
public final class ReachedChunkSet extends LinkedHashSet<Node> {
    private Node[] order;
    private int count;
    private int[] stamp;
    private long[] originAtIndex;
    private int walk = 1;

    public ReachedChunkSet(int chunkCount) {
        super(0);
        order = new Node[Math.max(64, chunkCount / 8)];
        stamp = new int[Math.max(1, chunkCount)];
        originAtIndex = new long[Math.max(1, chunkCount)];
    }

    /** Forgets every element; the next walk reuses the arrays. */
    @Override
    public void clear() {
        Arrays.fill(order, 0, count, null);
        count = 0;
        if (++walk == 0) {
            Arrays.fill(stamp, 0);
            walk = 1;
        }
    }

    @Override
    public boolean add(Node info) {
        int index = info.section.index;
        if (index < 0 || index >= stamp.length) {
            throw new IllegalArgumentException("Section index " + index + " outside storage of " + stamp.length);
        }
        long origin = info.section.getOrigin().asLong();
        if (stamp[index] == walk && originAtIndex[index] == origin) {
            return false;
        }
        stamp[index] = walk;
        originAtIndex[index] = origin;
        if (count == order.length) {
            order = Arrays.copyOf(order, order.length * 2);
        }
        order[count++] = info;
        return true;
    }

    @Override
    public boolean contains(Object o) {
        if (!(o instanceof Node info)) {
            return false;
        }
        int index = info.section.index;
        return index >= 0 && index < stamp.length && stamp[index] == walk
                && originAtIndex[index] == info.section.getOrigin().asLong();
    }

    @Override
    public int size() {
        return count;
    }

    @Override
    public boolean isEmpty() {
        return count == 0;
    }

    @Override
    public Iterator<Node> iterator() {
        return new Iterator<>() {
            private int next;

            @Override
            public boolean hasNext() {
                return next < count;
            }

            @Override
            public Node next() {
                if (next >= count) {
                    throw new NoSuchElementException();
                }
                return order[next++];
            }
        };
    }

    @Override
    public void forEach(Consumer<? super Node> action) {
        for (int i = 0; i < count; i++) {
            action.accept(order[i]);
        }
    }

    @Override
    public Spliterator<Node> spliterator() {
        return Spliterators.spliterator(order, 0, count, Spliterator.ORDERED | Spliterator.DISTINCT);
    }

    @Override
    public Object[] toArray() {
        return Arrays.copyOf(order, count, Object[].class);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T[] toArray(T[] a) {
        if (a.length < count) {
            return (T[]) Arrays.copyOf(order, count, a.getClass());
        }
        System.arraycopy(order, 0, a, 0, count);
        if (a.length > count) {
            a[count] = null;
        }
        return a;
    }

    @Override
    public Node getFirst() {
        if (count == 0) {
            throw new NoSuchElementException();
        }
        return order[0];
    }

    @Override
    public Node getLast() {
        if (count == 0) {
            throw new NoSuchElementException();
        }
        return order[count - 1];
    }

    @Override
    public boolean remove(Object o) {
        throw unsupported();
    }

    @Override
    public boolean removeIf(Predicate<? super Node> filter) {
        throw unsupported();
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        throw unsupported();
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        throw unsupported();
    }

    @Override
    public void addFirst(Node info) {
        throw unsupported();
    }

    @Override
    public void addLast(Node info) {
        throw unsupported();
    }

    @Override
    public Node removeFirst() {
        throw unsupported();
    }

    @Override
    public Node removeLast() {
        throw unsupported();
    }

    @Override
    public LinkedHashSet<Node> reversed() {
        throw unsupported();
    }

    @Override
    public Object clone() {
        throw unsupported();
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("reached-section set supports add, contains, size and iteration only");
    }
}
