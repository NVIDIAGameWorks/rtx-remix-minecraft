/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.RemixCaptureSource;
import com.nvidia.rtxremix.minecraft.entity.RemixCapturingConsumer;
import com.nvidia.rtxremix.minecraft.entity.RemixConsumerDispatch;
import net.minecraft.client.render.SpriteTexturedVertexConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Makes repeated cuboid/iterator queries a field read instead of a wrapper-chain walk. */
@Mixin(SpriteTexturedVertexConsumer.class)
public class SpriteTexturedVertexConsumerMixin implements RemixCaptureSource {
    @Unique private RemixCapturingConsumer rtxremix$capture;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void rtxremix$bindCapture(CallbackInfo ci) {
        rtxremix$capture = RemixConsumerDispatch.findCapture(((SpriteTexturedVertexConsumer) (Object) this).delegate);
    }

    @Override
    public RemixCapturingConsumer remixCapture() { return rtxremix$capture; }
}
