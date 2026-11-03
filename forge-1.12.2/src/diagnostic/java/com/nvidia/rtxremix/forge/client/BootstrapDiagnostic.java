/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.InstanceInfo;
import com.nvidia.rtxremix.LightInfo;
import com.nvidia.rtxremix.LightInfoDistantEXT;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.MeshInfo;
import com.nvidia.rtxremix.MeshInfoSurfaceTriangles;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.forge.RemixForgeMod;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Optional test mod. Its classes are excluded from the distributable renderer JAR. */
@Mod(modid = "rtxremixdiagnostic", name = "Remix bootstrap diagnostic", version = "0.1.0",
        dependencies = "required-after:rtxremixforge", clientSideOnly = true, acceptableRemoteVersions = "*")
public final class BootstrapDiagnostic {
    private final Minecraft mc = Minecraft.getMinecraft();
    private boolean launched, enabled, reported;
    private RemixAPI api;
    private WorldClient world;
    private long material, mesh, light;
    private final InstanceInfo instance = new InstanceInfo();

    @Mod.EventHandler
    public void initialize(FMLInitializationEvent event) {
        if (Boolean.getBoolean("rtxremix.forge.diagnostic")) MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!launched && mc.currentScreen instanceof GuiMainMenu) {
            launched = true;
            WorldSettings settings = new WorldSettings(0, GameType.CREATIVE, false, false, WorldType.FLAT);
            mc.launchIntegratedServer("remix-bootstrap-" + System.currentTimeMillis(), "Remix bootstrap", settings);
        }
        if (mc.world != null && mc.player != null && !enabled) {
            enabled = true;
            ClientCommandHandler.instance.executeCommand(mc.player, "/remix on");
        }
        ForgeRenderer renderer = RendererLifecycle.renderer();
        if (api != null && (renderer == null || renderer.world() != world)) close();
        if (!reported && renderer != null && renderer.frames() >= 120) {
            reported = true;
            RemixForgeMod.LOG.info("BOOTSTRAP_DIAGNOSTIC: 120 native frames completed; inspect the green panel.");
            if (Boolean.getBoolean("rtxremix.forge.diagnostic.exit")) mc.shutdown();
        }
    }

    @SubscribeEvent
    public void world(RenderWorldLastEvent event) {
        ForgeRenderer renderer = RendererLifecycle.renderer();
        if (renderer == null || renderer.api() == null || renderer.world() != mc.world) return;
        if (api == null) {
            api = renderer.api();
            world = mc.world;
            create();
        }
        api.drawInstance(instance);
        api.drawLightInstance(light);
    }

    @SubscribeEvent
    public void unload(WorldEvent.Unload event) {
        if (world != event.getWorld()) return;
        if (mc.isCallingFromMinecraftThread()) close();
        else mc.addScheduledTask(() -> { if (world == event.getWorld()) close(); });
    }

    private void create() {
        material = api.createMaterial(MaterialInfo.builder().hash(0x464f5247454d4154L)
                .opaqueExt(MaterialInfoOpaqueEXT.builder().albedoConstant(0.1f, 0.8f, 0.2f)
                        .roughnessConstant(0.8f).build()).build());
        Vec3d eye = mc.player.getPositionEyes(1);
        Vec3d forward = mc.player.getLook(1);
        Vec3d right = new Vec3d(-forward.z, 0, forward.x).normalize();
        Vec3d center = eye.add(forward.scale(3));
        ByteBuffer vertices = ByteBuffer.allocateDirect(4 * HardcodedVertex.STRIDE).order(ByteOrder.nativeOrder());
        for (int corner = 0; corner < 4; corner++) {
            double x = corner == 0 || corner == 3 ? -1 : 1;
            double y = corner < 2 ? -1 : 1;
            Vec3d point = center.add(right.scale(x)).add(0, y, 0);
            HardcodedVertex.create((float) point.x, (float) point.y, (float) point.z,
                    (float) -forward.x, (float) -forward.y, (float) -forward.z,
                    0, 0, 0xffffffff).writeTo(vertices);
        }
        vertices.flip();
        ByteBuffer indices = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder());
        for (int index : new int[] {0, 1, 2, 0, 2, 3}) indices.putInt(index);
        indices.flip();
        MeshInfoSurfaceTriangles surface = new MeshInfoSurfaceTriangles();
        surface.vertexBuffer = vertices;
        surface.vertexCount = 4;
        surface.indexBuffer = indices;
        surface.indexCount = 6;
        surface.material = material;
        mesh = api.createMesh(MeshInfo.builder().hash(0x464f5247454d4553L).surfaces(surface).build());
        instance.mesh = mesh;
        instance.doubleSided = 1;
        Vec3d lightDirection = forward.add(0, -0.5, 0).normalize();
        light = api.createLight(LightInfo.builder().hash(0x464f5247454c4954L).radiance(8, 8, 8)
                .distantExt(LightInfoDistantEXT.builder().direction((float) lightDirection.x, (float) lightDirection.y, (float) lightDirection.z).build())
                .build());
    }

    private void close() {
        api.destroyMesh(mesh);
        api.destroyMaterial(material);
        api.destroyLight(light);
        api = null;
        world = null;
    }
}