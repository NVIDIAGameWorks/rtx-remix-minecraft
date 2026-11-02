/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.nvidia.rtxremix.forge.RemixForgeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;

public final class ClientEvents {
    private final Minecraft mc = Minecraft.getMinecraft();
    private final ForgeRenderer renderer = new ForgeRenderer(mc);
    private final KeyBinding toggle = new KeyBinding("key.rtxremixforge.toggle", Keyboard.KEY_NONE,
            "key.categories.rtxremixforge");
    private boolean enabled, failed;

    public void register() {
        RendererLifecycle.attach(renderer);
        com.nvidia.rtxremix.forge.presentation.PresentationHooks.attach(renderer);
        ClientRegistry.registerKeyBinding(toggle);
        ClientCommandHandler.instance.registerCommand(new RemixCommand(this));
        ((IReloadableResourceManager) mc.getResourceManager()).registerReloadListener(
                resources -> mc.addScheduledTask((Runnable) renderer::releaseWorld));
    }

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            while (toggle.isPressed()) setEnabled(!enabled);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void prepareWorld(RenderWorldLastEvent event) {
        if (!eligible()) return;
        try { renderer.prepareWorld(); }
        catch (RuntimeException | LinkageError failure) { fail(failure); }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void renderWorld(RenderWorldLastEvent event) {
        if (!eligible()) return;
        try { renderer.render(event.getPartialTicks()); }
        catch (RuntimeException | LinkageError failure) { fail(failure); }
    }

    private boolean eligible() {
        return enabled && mc.world != null && Display.isVisible()
                && mc.displayWidth > 0 && mc.displayHeight > 0
                && !mc.gameSettings.anaglyph && !mc.entityRenderer.isShaderActive();
    }

    @SubscribeEvent
    public void worldUnload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote) {
            mc.addScheduledTask(() -> renderer.releaseWorld(event.getWorld()));
        }
    }

    void setEnabled(boolean value) {
        if (value && failed) { message("Restart Minecraft after the rendering failure."); return; }
        if (value && (mc.gameSettings.anaglyph || mc.entityRenderer.isShaderActive())) {
            message("Disable anaglyph and post-processing shaders before enabling Remix.");
            return;
        }
        enabled = value;
        if (!value) renderer.releaseWorld();
        message(value ? "Remix enabled." : "Vanilla rendering enabled.");
    }

    private void fail(Throwable failure) {
        failed = true;
        enabled = false;
        RemixForgeMod.LOG.error("Remix rendering stopped", failure);
        message("Remix stopped; see logs/latest.log. Restart Minecraft after a native failure.");
    }

    void message(String text) {
        if (mc.player != null) mc.player.sendMessage(new TextComponentString("[Remix] " + text));
        RemixForgeMod.LOG.info(text);
    }

    String status() { return "enabled=" + enabled + "; " + renderer.status(); }
}