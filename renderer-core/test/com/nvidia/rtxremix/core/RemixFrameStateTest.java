/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.core;

import com.nvidia.rtxremix.InteropInfo;
import com.nvidia.rtxremix.PresentInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemixFrameStateTest {
    @Test
    void ensureSubmittedAllocatesExactlyOneFrame() {
        FakeBackend backend = new FakeBackend();
        RemixFrameState state = new RemixFrameState(backend);

        state.ensureSubmitted();
        state.ensureSubmitted();

        assertEquals(1, backend.submits);
        assertEquals(0, backend.interopQueries);
        assertTrue(state.isSubmitted());
    }

    @Test
    void submitQueriesInteropOnlyWhenRequested() {
        FakeBackend backend = new FakeBackend();
        RemixFrameState state = new RemixFrameState(backend);

        assertSame(backend.interop, state.submitAndGetInteropInfo());

        assertEquals(1, backend.submits);
        assertEquals(1, backend.interopQueries);
    }

    @Test
    void aSecondExplicitSubmitIsRejected() {
        RemixFrameState state = new RemixFrameState(new FakeBackend());
        state.submit();

        assertThrows(IllegalStateException.class, state::submit);
    }

    @Test
    void presentCompletesThePendingFrame() {
        FakeBackend backend = new FakeBackend();
        RemixFrameState state = new RemixFrameState(backend);
        PresentInfo present = new PresentInfo();

        state.submit();
        state.present(present);

        assertSame(present, backend.presented);
        assertFalse(state.isSubmitted());
    }

    @Test
    void failedPresentKeepsTheNativeFramePending() {
        FakeBackend backend = new FakeBackend();
        backend.failPresent = true;
        RemixFrameState state = new RemixFrameState(backend);
        state.submit();

        assertThrows(IllegalStateException.class, () -> state.present(new PresentInfo()));
        assertTrue(state.isSubmitted());
        assertThrows(IllegalStateException.class, state::submit);
    }

    @Test
    void presentingWithoutASubmissionIsRejected() {
        RemixFrameState state = new RemixFrameState(new FakeBackend());

        assertThrows(IllegalStateException.class, () -> state.present(new PresentInfo()));
    }

    private static final class FakeBackend implements RemixFrameBackend {
        final InteropInfo interop = new InteropInfo();
        int submits;
        int interopQueries;
        PresentInfo presented;
        boolean failPresent;

        @Override
        public void submit() {
            submits++;
        }

        @Override
        public InteropInfo interopInfo() {
            interopQueries++;
            return interop;
        }

        @Override
        public void present(PresentInfo presentInfo) {
            presented = presentInfo;
            if (failPresent) throw new IllegalStateException("present failed");
        }
    }
}
