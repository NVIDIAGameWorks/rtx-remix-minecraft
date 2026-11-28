/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * A quality preset: one of Remix's graphics tiers, selected for a single session.
 *
 * <p>Selecting one writes {@code rtx.graphicsPreset} and nothing else, so Minecraft's options and
 * the mod's settings stay the player's own. The write lands in Remix's User Settings layer without
 * saving it, so the tier lasts until the game exits unless the player saves from Remix's own menu.
 */
public enum QualityPreset {
    LOW(3),
    MEDIUM(2),
    HIGH(1),
    ULTRA(0);

    private static final String REMIX_OPTION = "rtx.graphicsPreset";

    /** The runtime's {@code GraphicsPreset} enumerator for this tier. */
    private final int remixValue;

    QualityPreset(int remixValue) {
        this.remixValue = remixValue;
    }

    /** The name the launchers, the benchmark harness and {@code /rtx preset} use. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Selects this tier.
     *
     * <p>Remix resolves an Auto tier from the GPU at device init and writes the result to the same
     * layer, so this must run after the renderer has initialized or the runtime replaces it.
     *
     * @param setConfigVariable Remix's {@code SetConfigVariable}
     */
    public void writeTo(BiConsumer<String, String> setConfigVariable) {
        setConfigVariable.accept(REMIX_OPTION, Integer.toString(remixValue));
    }

    public static Optional<QualityPreset> byId(String id) {
        return Arrays.stream(values()).filter(preset -> preset.id().equals(id)).findFirst();
    }

    /** Every id, cheapest tier first. */
    public static List<String> ids() {
        return Collections.unmodifiableList(
                Arrays.stream(values()).map(QualityPreset::id).collect(Collectors.toList()));
    }
}
