/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.BiomeColorGeneration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Answers a biome's grass and foliage colours from a cached value instead of the
 * {@code Optional.orElseGet(this::getDefault...)} vanilla runs on every call, which
 * allocates a bound method reference and boxes a freshly computed value. The biome colour
 * cache asks for these for each blend-radius sample of each block of every newly loaded
 * chunk, on the section-build workers, and the boxed integers were the largest single
 * class of garbage left on those threads.
 *
 * <p>Both values are per-biome constants for a given pair of colormaps, so vanilla's first
 * answer is kept, stamped with the {@link BiomeColorGeneration} it was computed under; a
 * resource reload that replaces a colormap moves the generation on and the next lookup
 * recomputes. Grass is cached only for biomes whose colour modifier is {@code NONE}: swamps
 * and dark forests apply a position-dependent or lossy modifier after the constant, so their
 * returned value is not the constant, and they keep vanilla's path.
 *
 * <p>What is cached is the boxed {@link Integer} vanilla's path produced, and that same
 * object is handed back on every hit: {@code setReturnValue(int)} would box again per call,
 * which is what the first version of this mixin did (0.67 GB of Integer over a benchmark
 * run). Each cache is one volatile reference to an immutable holder, so a worker that sees
 * the holder sees a colour and the generation it belongs to together.
 */
@Mixin(Biome.class)
public abstract class BiomeMixin {

    @Shadow
    @Final
    private BiomeSpecialEffects specialEffects;

    private volatile BiomeColorGeneration.Cached rtxremix$grass;
    private volatile BiomeColorGeneration.Cached rtxremix$foliage;

    @Inject(method = "getGrassColor", at = @At("HEAD"), cancellable = true)
    private void rtxremix$grassColorAt(double x, double z, CallbackInfoReturnable<Integer> cir) {
        BiomeColorGeneration.Cached cached = rtxremix$grass;
        if (cached != null && cached.isCurrent()) {
            cir.setReturnValue(cached.color());
        }
    }

    @Inject(method = "getGrassColor", at = @At("RETURN"))
    private void rtxremix$rememberGrassColor(double x, double z, CallbackInfoReturnable<Integer> cir) {
        if (specialEffects.getGrassColorModifier() == BiomeSpecialEffects.GrassColorModifier.NONE) {
            rtxremix$grass = new BiomeColorGeneration.Cached(BiomeColorGeneration.current(), cir.getReturnValue());
        }
    }

    @Inject(method = "getFoliageColor", at = @At("HEAD"), cancellable = true)
    private void rtxremix$foliageColor(CallbackInfoReturnable<Integer> cir) {
        BiomeColorGeneration.Cached cached = rtxremix$foliage;
        if (cached != null && cached.isCurrent()) {
            cir.setReturnValue(cached.color());
        }
    }

    @Inject(method = "getFoliageColor", at = @At("RETURN"))
    private void rtxremix$rememberFoliageColor(CallbackInfoReturnable<Integer> cir) {
        rtxremix$foliage = new BiomeColorGeneration.Cached(BiomeColorGeneration.current(), cir.getReturnValue());
    }
}
