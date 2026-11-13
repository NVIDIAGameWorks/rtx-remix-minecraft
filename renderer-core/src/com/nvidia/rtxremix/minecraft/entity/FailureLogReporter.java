/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import org.slf4j.Logger;

/** Connects canonical capture-failure throttling to the host logger. */
final class FailureLogReporter {
    private FailureLogReporter() {}

    static FailureLogThrottle.Reporter forLogger(final Logger logger) {
        return new FailureLogThrottle.Reporter() {
            @Override
            public void firstFailure(
                    String stage,
                    String what,
                    Object where,
                    int rearmFrames,
                    Exception failure) {
                logger.error(
                        "Error {} {} at {} (further failures are not logged until {} frames pass "
                                + "without one)",
                        stage,
                        what,
                        where,
                        rearmFrames,
                        failure);
            }

            @Override
            public void episodeStopped(String subject, long unlogged) {
                logger.error("{} failures have stopped; {} went unlogged", subject, unlogged);
            }
        };
    }
}
