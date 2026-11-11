/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

/**
 * Whether the lit element of a hand-held item is burning: the beam of a beacon,
 * the flame, smoke and analytic light of a torch, the glow of any other luminous
 * block item.
 *
 * <p>Two switches. The local player's held lights can be put out and relit with
 * the "Toggle Held Lights" key. That is client state with no packet behind it, so
 * it speaks only for the player at this keyboard; lights in anyone else's hands
 * stay lit. An open flame also goes out while its holder is under water, and
 * that applies to every holder, since submersion is a synced entity state. Both
 * apply to a single decision here so the light, the flame and the smoke can never
 * disagree about whether a torch is burning.
 *
 * <p>Flames under water can be allowed, so the rule can be lifted for a player
 * who wants it or tested without diving out of the water.
 */
public final class HeldLightPolicy {

    private static boolean localHeldLightsOff = false;
    private static boolean flamesAllowedUnderwater = false;

    private HeldLightPolicy() {}

    /**
     * The local player's switch puts out everything in their hands; water puts
     * out open flames in anyone's hands unless flames are allowed under water.
     */
    public static boolean isLit(boolean localPlayer, boolean heldLightsOff,
                                boolean openFlame, boolean submerged,
                                boolean flamesAllowedUnderwater) {
        if (localPlayer && heldLightsOff) return false;
        return !(openFlame && submerged && !flamesAllowedUnderwater);
    }

    public static boolean isLocalHeldLightsOff() {
        return localHeldLightsOff;
    }

    /** Puts the local player's held lights out, or relights them; returns the new off state. */
    public static boolean toggleLocalHeldLights() {
        localHeldLightsOff = !localHeldLightsOff;
        return localHeldLightsOff;
    }

    public static boolean areFlamesAllowedUnderwater() {
        return flamesAllowedUnderwater;
    }

    public static void setFlamesAllowedUnderwater(boolean allowed) {
        flamesAllowedUnderwater = allowed;
    }
}
