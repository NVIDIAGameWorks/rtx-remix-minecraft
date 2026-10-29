/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.nvidia.rtxremix.forge.RemixForgeMod;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Future;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.client.ClientCommandHandler;

/** Test-only publication checks in the bootstrap diagnostic's disposable integrated world. */
final class TerrainDiagnostic {
    private final Minecraft mc = Minecraft.getMinecraft();
    private final String testWorld;
    private int phase;
    private long deadline = System.nanoTime() + 120_000_000_000L;
    private long readyFrame = -1, placedRevision;
    private Object previousCapture;
    private Future<?> mutation;

    TerrainDiagnostic(String testWorld) { this.testWorld = testWorld; }

    boolean tick(ForgeRenderer renderer) {
        if (mc.getIntegratedServer() == null || !testWorld.equals(mc.getIntegratedServer().getFolderName()))
            throw new IllegalStateException("Terrain diagnostic requires its disposable integrated world");
        if (System.nanoTime() > deadline) throw new IllegalStateException("Terrain diagnostic timed out in phase " + phase);
        if (renderer == null || renderer.world() != mc.world) return false;
        Object capture = field(renderer, "terrain");
        if (capture == null) return false;
        if (mutation != null) {
            if (!mutation.isDone()) return false;
            try { mutation.get(); }
            catch (Exception failure) { throw new IllegalStateException("Server fixture mutation failed", failure); }
            mutation = null;
        }
        switch (phase) {
            case 0:
                mutation = mc.getIntegratedServer().addScheduledTask(() -> {
                    WorldServer world = mc.getIntegratedServer().getWorld(0);
                    world.setWorldTime(6000);
                    world.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
                    wall(world, true);
                    player().connection.setPlayerLocation(8, 5, -4, 0, 0);
                });
                next();
                break;
            case 1:
                if (!stable(renderer, mc.world.getBlockState(new BlockPos(8, 5, 4)).getBlock() == Blocks.STONE
                        && ready(capture, 0, 0) && (boolean) field(capture, "warnedCapacity"))) return false;
                RemixForgeMod.LOG.info("TERRAIN_DIAGNOSTIC: mesh budget reached before editing terrain.");
                placedRevision = revision(capture, 0, 0);
                screenshot("placed");
                mutation = mc.getIntegratedServer().addScheduledTask(() -> wall(mc.getIntegratedServer().getWorld(0), false));
                next();
                break;
            case 2:
                if (!stable(renderer, mc.world.isAirBlock(new BlockPos(8, 5, 4)) && ready(capture, 0, 0)
                        && revision(capture, 0, 0) > placedRevision)) return false;
                screenshot("removed");
                mutation = mc.getIntegratedServer().addScheduledTask(() -> player().connection.setPlayerLocation(1608, 5, 1608, 0, 0));
                next();
                break;
            case 3:
                if (!stable(renderer, column(capture, 0, 0) == null && ready(capture, 100, 100))) return false;
                screenshot("streamed");
                previousCapture = capture;
                mc.refreshResources();
                next();
                break;
            case 4:
                if (!stable(renderer, capture != previousCapture && ready(capture, 100, 100))) return false;
                released(previousCapture);
                screenshot("reloaded");
                ClientCommandHandler.instance.executeCommand(mc.player, "/remix off");
                released(capture);
                if (field(renderer, "terrain") != null) throw new IllegalStateException("Disabled renderer retained terrain");
                RemixForgeMod.LOG.info("TERRAIN_DIAGNOSTIC: PASS placement, removal, streaming, resource reload, release.");
                return true;
            default:
                throw new IllegalStateException("Unexpected terrain diagnostic phase");
        }
        return false;
    }

    private EntityPlayerMP player() {
        return mc.getIntegratedServer().getPlayerList().getPlayerByUUID(mc.player.getUniqueID());
    }

    private static void wall(WorldServer world, boolean present) {
        for (int x = 4; x <= 12; x++) for (int y = 4; y <= 8; y++)
            world.setBlockState(new BlockPos(x, y, 4), (present ? Blocks.STONE : Blocks.AIR).getDefaultState(), 3);
    }

    private void next() {
        phase++;
        readyFrame = -1;
        deadline = System.nanoTime() + 120_000_000_000L;
    }

    private boolean stable(ForgeRenderer renderer, boolean ready) {
        if (!ready) { readyFrame = -1; return false; }
        if (readyFrame < 0) readyFrame = renderer.frames();
        return renderer.frames() - readyFrame >= 10;
    }

    private void screenshot(String phase) {
        ScreenShotHelper.saveScreenshot(mc.gameDir, "remix-terrain-" + phase + ".png",
                mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        RemixForgeMod.LOG.info("TERRAIN_DIAGNOSTIC: {} publication verified and screenshot saved.", phase);
    }

    private static boolean ready(Object capture, int x, int z) {
        Object column = column(capture, x, z);
        if (column == null) return false;
        Object section = ((Object[]) field(column, "sections"))[0];
        return (long) field(section, "publication") != 0
                && field(section, "publishedRevision").equals(field(section, "revision"))
                && field(section, "asset") != null;
    }

    private static long revision(Object capture, int x, int z) {
        return (long) field(((Object[]) field(column(capture, x, z), "sections"))[0], "publication");
    }

    private static Object column(Object capture, int x, int z) {
        Object schedule = field(capture, "schedule");
        try {
            Method method = schedule.getClass().getDeclaredMethod("column", int.class, int.class);
            method.setAccessible(true);
            return method.invoke(schedule, x, z);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    private static void released(Object capture) {
        if (!(boolean) field(capture, "closed") || (int) field(capture, "pageResources") != 0
                || (long) field(capture, "atlas") != 0 || (long) field(capture, "daylight") != 0)
            throw new IllegalStateException("Terrain release left live resources");
        for (long material : (long[]) field(capture, "materials"))
            if (material != 0) throw new IllegalStateException("Terrain release left a material");
    }

    private static Object field(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
}
