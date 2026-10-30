/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockLightIntensityControlsTest {

    @Test
    void catalogRejectsMissingBuiltInRules() {
        Set<Identifier> controlledRules = BlockLightIntensityControls.controls().stream()
                .map(BlockLightIntensityControls.Control::ruleId)
                .collect(Collectors.toSet());

        assertThrows(
                IllegalStateException.class,
                () -> BlockLightIntensityControls.validateBuiltInRules(
                        controlledRules.stream().skip(1).collect(Collectors.toSet())));
    }

    @Test
    void configuredMultiplierScalesControlledRulesOnly() {
        BlockLightIntensityControls.Control control =
                BlockLightIntensityControls.controls().getFirst();
        Identifier externalRule =
                Identifier.fromNamespaceAndPath("example", "rtx_remix/block_lights/custom.json");
        try {
            assertEquals(4.0f, BlockLightIntensityControls.apply(control.ruleId(), 4.0f));

            control.set(12.5);

            assertEquals(50.0f, BlockLightIntensityControls.apply(control.ruleId(), 4.0f));
            assertEquals(4.0f, BlockLightIntensityControls.apply(externalRule, 4.0f));
        } finally {
            control.set(BlockLightIntensityControls.DEFAULT_MULTIPLIER);
        }
    }
}
