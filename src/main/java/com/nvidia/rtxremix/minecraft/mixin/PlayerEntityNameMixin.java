/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.PlayerNameText;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

/** Caches the player's name text, rebuilt only when the profile name changes; see {@link PlayerNameText}. */
@Mixin(Player.class)
public abstract class PlayerEntityNameMixin implements PlayerNameText {
    private String rtxremix$cachedName;
    private Component rtxremix$cachedText;

    @Override
    public Component rtxremix$nameText() {
        Player self = (Player) (Object) this;
        String name = self.getGameProfile().getName();
        if (rtxremix$cachedText == null || !name.equals(rtxremix$cachedName)) {
            rtxremix$cachedName = name;
            rtxremix$cachedText = self.getName();
        }
        return rtxremix$cachedText;
    }
}
