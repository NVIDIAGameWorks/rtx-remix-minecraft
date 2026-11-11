/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.logic;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.phys.Vec3;

/** Reusable Minecraft 1.21 view of gameplay-driven conf inputs. */
public final class MinecraftGameplayFrameContext
        implements ConfLayerDriver.FrameContext {
    private boolean underwater;
    private float viewDistanceStrength;
    private boolean overworld;
    private long timeOfDay;
    private String biomePath;
    private double cameraX;
    private double cameraY;
    private double cameraZ;
    private float rainGradient;
    private float thunderGradient;

    public MinecraftGameplayFrameContext update(
            ClientLevel world,
            Vec3 cameraPosition,
            boolean underwater,
            float viewDistanceStrength,
            float tickDelta) {
        this.underwater = underwater;
        this.viewDistanceStrength = viewDistanceStrength;
        overworld = Level.OVERWORLD.equals(world.dimension());
        timeOfDay = world.getDayTime();
        biomePath = world.getBiome(BlockPos.containing(cameraPosition)).unwrapKey()
                .filter(key -> "minecraft".equals(
                        key.location().getNamespace()))
                .map(key -> key.location().getPath())
                .orElse(null);
        cameraX = cameraPosition.x;
        cameraY = cameraPosition.y;
        cameraZ = cameraPosition.z;
        rainGradient = world.getRainLevel(tickDelta);
        thunderGradient = world.getThunderLevel(tickDelta);
        return this;
    }

    public static Set<String> vanillaBiomeNames() {
        Set<String> names = new HashSet<>();
        for (Field field : Biomes.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    || field.getType() != ResourceKey.class) {
                continue;
            }
            try {
                names.add(((ResourceKey<?>) field.get(null))
                        .location().getPath());
            } catch (IllegalAccessException e) {
                throw new AssertionError(
                        "BiomeKeys public field not readable: "
                                + field.getName(),
                        e);
            }
        }
        return Set.copyOf(names);
    }

    @Override public boolean underwater() { return underwater; }
    @Override public float viewDistanceStrength() { return viewDistanceStrength; }
    @Override public boolean overworld() { return overworld; }
    @Override public long timeOfDay() { return timeOfDay; }
    @Override public String biomePath() { return biomePath; }
    @Override public double cameraX() { return cameraX; }
    @Override public double cameraY() { return cameraY; }
    @Override public double cameraZ() { return cameraZ; }
    @Override public float rainGradient() { return rainGradient; }
    @Override public float thunderGradient() { return thunderGradient; }
}
