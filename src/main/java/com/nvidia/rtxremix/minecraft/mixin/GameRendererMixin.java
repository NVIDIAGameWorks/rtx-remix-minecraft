/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Moves the first-person arm/item draw into Remix while preserving Minecraft's
 * own HeldItemRenderer animation and selection logic, brackets the GUI tail
 * of its render method with a profiling scope, and has input capture follow
 * the Remix menus at the start of each frame.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Redirect(
            method = "renderItemInHand",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V"
            ))
    private void rtxremix$captureFirstPersonModel(
            ItemInHandRenderer heldItemRenderer,
            float tickDelta,
            PoseStack matrices,
            MultiBufferSource.BufferSource vertexConsumers,
            LocalPlayer player,
            int light) {
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (!RtxRemixMod.shouldCaptureFirstPersonModel()) {
            heldItemRenderer.renderHandsWithItems(tickDelta, matrices, vertexConsumers, player, light);
            return;
        }

        renderer.captureFirstPersonModel(heldItemRenderer, tickDelta, matrices, player, light);
    }

    @Inject(
            method = "renderItemInHand",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ScreenEffectRenderer;renderScreenEffect(Lnet/minecraft/client/Minecraft;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
                    shift = At.Shift.BEFORE
            ))
    private void rtxremix$submitBeforeScreenOverlays(
            Camera camera,
            float tickDelta,
            Matrix4f positionMatrix,
            CallbackInfo ci) {
        RtxRemixMod.submitRemixFrameIfReady();
    }

    /**
     * Covers third person, sleeping, hidden-hand, panorama, and other paths
     * where HeldItemRenderer or InGameOverlayRenderer is skipped.
     */
    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void rtxremix$submitAtRenderWorldTail(
            DeltaTracker tickCounter,
            CallbackInfo ci) {
        RtxRemixMod.submitRemixFrameIfReady();
    }

    /**
     * The GUI tail of GameRenderer.render, from vanilla's {@code swap("gui")} to
     * the method's return: HUD, in-game screen or overlay, toasts, and the final
     * DrawContext flush. Vanilla only reaches that swap while a world is loaded,
     * so menu-only frames have no GUI zone. WorldRender.END accounts for the
     * world half of the frame.
     */
    private ProfileScope rtxremix$guiScope;

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V"
            ))
    private void rtxremix$beginGuiScope(
            DeltaTracker tickCounter,
            boolean tick,
            CallbackInfo ci) {
        rtxremix$closeGuiScope();
        if (ProfileScope.isJavaScopeEnabled()) {
            rtxremix$guiScope = ProfileScope.begin("GameRenderer.render.gui")
                    .plotOnClose("Java.GameRenderer.render.gui.ms");
        }
    }

    /** Before any screen draws, so the frame's hover state already matches input capture. */
    @Inject(method = "render", at = @At("HEAD"))
    private void rtxremix$followRemixMenuVisibility(
            DeltaTracker tickCounter,
            boolean tick,
            CallbackInfo ci) {
        RtxRemixMod.followRemixMenuVisibility();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void rtxremix$endGuiScope(
            DeltaTracker tickCounter,
            boolean tick,
            CallbackInfo ci) {
        rtxremix$closeGuiScope();
    }

    private void rtxremix$closeGuiScope() {
        ProfileScope scope = rtxremix$guiScope;
        if (scope != null) {
            rtxremix$guiScope = null;
            scope.close();
        }
    }
}
