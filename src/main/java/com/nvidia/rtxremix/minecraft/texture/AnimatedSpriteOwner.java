/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Implemented by a sprite's animation state, so the state knows where in the atlas
 * its frames land: the contents an animation belongs to carry no atlas position.
 */
public interface AnimatedSpriteOwner {
    void rtxremix$setSprite(TextureAtlasSprite sprite);
}
