/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.particle.rapids.WaterRapidParticleSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Adds lightweight profiling scopes around client-world work that contributes
 * to Remix frame cost.
 */
@Mixin(ClientLevel.class)
public abstract class ClientWorldMixin {
    
    private static final ThreadLocal<ProfileScope> rtxremix$worldTickScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$entityTickScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$randomDisplayTickScope = new ThreadLocal<>();

    @Inject(method = "tick", at = @At("HEAD"))
    private void rtxremix$tickHead(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        rtxremix$openScope(rtxremix$worldTickScope, ProfileScope.beginJfrOnly("ClientWorld.tick")
                .plotOnClose("Java.ClientWorld.tick.ms"));
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void rtxremix$tickReturn(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$worldTickScope);
    }

    @Inject(method = "tickEntities", at = @At("HEAD"))
    private void rtxremix$tickEntitiesHead(CallbackInfo ci) {
        int entityCount = ((ClientLevel) (Object) this).getEntityCount();
        rtxremix$openScope(rtxremix$entityTickScope, ProfileScope.beginJfrOnly("ClientWorld.tickEntities")
                .entityCount(entityCount)
                .plotOnClose("Java.ClientWorld.tickEntities.ms"));
    }

    @Inject(method = "tickEntities", at = @At("RETURN"))
    private void rtxremix$tickEntitiesReturn(CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$entityTickScope);
    }

    @Inject(method = "animateTick", at = @At("HEAD"))
    private void rtxremix$randomBlockDisplayHead(int centerX, int centerY, int centerZ, CallbackInfo ci) {
        rtxremix$openScope(rtxremix$randomDisplayTickScope, ProfileScope.beginJfrOnly("ClientWorld.randomBlockDisplayTicks")
                .captureCount(1334)
                .plotOnClose("Java.ClientWorld.randomBlockDisplayTicks.ms"));
    }

    @Inject(method = "animateTick", at = @At("RETURN"))
    private void rtxremix$randomBlockDisplayReturn(int centerX, int centerY, int centerZ, CallbackInfo ci) {
        WaterRapidParticleSpawner.spawnRandomDisplayParticles((ClientLevel) (Object) this, centerX, centerY, centerZ);
        rtxremix$closeScope(rtxremix$randomDisplayTickScope);
    }

    private static void rtxremix$openScope(ThreadLocal<ProfileScope> holder, ProfileScope scope) {
        rtxremix$closeScope(holder);
        holder.set(scope);
    }

    private static void rtxremix$closeScope(ThreadLocal<ProfileScope> holder) {
        ProfileScope scope = holder.get();
        if (scope != null) {
            holder.remove();
            scope.close();
        }
    }
}
