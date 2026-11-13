/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.profiling.ParticleTickProfiler;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.Map;
import java.util.Queue;

/**
 * Coarse particle ticking markers. These intentionally avoid per-particle
 * zones because the benchmark scene can tick thousands of particles per frame.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleManagerMixin {
    @Shadow @Final private Map<ParticleRenderType, Queue<Particle>> particles;

    private static final ThreadLocal<ProfileScope> rtxremix$tickScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$sheetScope = new ThreadLocal<>();

    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void rtxremix$tickHead(CallbackInfo ci) {
        ParticleTickProfiler.beginTick();
        int activeParticles = 0;
        int activeSheets = 0;
        for (Queue<Particle> queue : particles.values()) {
            int size = queue.size();
            activeParticles += size;
            if (size > 0) activeSheets++;
        }
        rtxremix$openScope(rtxremix$tickScope, ProfileScope.beginJfrOnly("ParticleManager.tick")
                .captureCount(activeParticles)
                .layerCount(activeSheets)
                .plotOnClose("Java.ParticleManager.tick.ms"));
    }

    @Inject(method = "tick", at = @At("RETURN"), require = 0)
    private void rtxremix$tickReturn(CallbackInfo ci) {
        ParticleTickProfiler.Stats stats = ParticleTickProfiler.current();
        ProfileScope tickScope = rtxremix$tickScope.get();
        if (tickScope != null) {
            tickScope.collisionChecks(stats.collisionChecks())
                    .collisionSkips(stats.skippedCollisionChecks())
                    .collisionNanos(stats.collisionNanos());
            ProfileScope.plot("Java.ParticleManager.collisionChecks", stats.collisionChecks());
            ProfileScope.plot("Java.ParticleManager.collisionSkips", stats.skippedCollisionChecks());
            ProfileScope.plot("Java.ParticleManager.collisionMs", stats.collisionNanos() / 1_000_000.0);
        }
        rtxremix$closeScope(rtxremix$sheetScope);
        rtxremix$closeScope(rtxremix$tickScope);
    }

    @Inject(method = "tickParticleList", at = @At("HEAD"), require = 0)
    private void rtxremix$tickParticlesHead(Collection<Particle> particles, CallbackInfo ci) {
        rtxremix$openScope(rtxremix$sheetScope, ProfileScope.beginJfrOnly("ParticleManager.tickParticles")
                .captureCount(particles.size())
                .plotOnClose("Java.ParticleManager.tickParticles.ms"));
    }

    @Inject(method = "tickParticleList", at = @At("RETURN"), require = 0)
    private void rtxremix$tickParticlesReturn(Collection<Particle> particles, CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$sheetScope);
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
