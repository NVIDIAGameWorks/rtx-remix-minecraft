/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import com.nvidia.rtxremix.minecraft.entity.HeldItemFlame;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** Supplies Minecraft-held-item facts to the canonical held-light policy. */
public final class HeldLightState {
    private HeldLightState() {}

    public static boolean isLit(LivingEntity holder, ItemStack stack) {
        Minecraft client = Minecraft.getInstance();
        return HeldLightPolicy.isLit(
                client != null && holder == client.player,
                HeldLightPolicy.isLocalHeldLightsOff(),
                HeldItemFlame.particleFor(stack) != null,
                holder.isUnderWater(),
                HeldLightPolicy.areFlamesAllowedUnderwater());
    }

    static boolean isLit(boolean localPlayer, boolean heldLightsOff,
                         boolean openFlame, boolean submerged,
                         boolean flamesAllowedUnderwater) {
        return HeldLightPolicy.isLit(
                localPlayer, heldLightsOff, openFlame, submerged, flamesAllowedUnderwater);
    }

    public static boolean isLocalHeldLightsOff() {
        return HeldLightPolicy.isLocalHeldLightsOff();
    }

    public static boolean toggleLocalHeldLights() {
        return HeldLightPolicy.toggleLocalHeldLights();
    }

    public static boolean areFlamesAllowedUnderwater() {
        return HeldLightPolicy.areFlamesAllowedUnderwater();
    }

    public static void setFlamesAllowedUnderwater(boolean allowed) {
        HeldLightPolicy.setFlamesAllowedUnderwater(allowed);
    }
}
