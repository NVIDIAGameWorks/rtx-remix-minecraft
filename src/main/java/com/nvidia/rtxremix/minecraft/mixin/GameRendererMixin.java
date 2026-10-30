/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Moves the first-person arm/item draw into Remix while preserving Minecraft's
 * own hands-and-items animation and selection logic, submits the Remix frame
 * before the screen effects, brackets the GUI draw with a profiling scope, and
 * has input capture follow the Remix menus at the start of each frame.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @WrapOperation(
            method = "renderItemInHand",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;submitHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;)V"
            ))
    private void rtxremix$captureFirstPersonModel(
            FirstPersonHandsAndItemsRenderer handsRenderer,
            float tickDelta,
            PoseStack matrices,
            SubmitNodeCollector collector,
            PlayerRenderState playerState,
            FirstPersonHandsAndItemsRenderState handsState,
            Operation<Void> original) {
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (!RtxRemixMod.shouldCaptureFirstPersonModel()) {
            original.call(handsRenderer, tickDelta, matrices, collector, playerState, handsState);
            return;
        }

        // Vanilla's own submit, into the capture: the hand storage stays empty, so
        // the hand pass vanilla runs next draws nothing.
        renderer.captureFirstPersonModel(
                captureCollector -> original.call(
                        handsRenderer, tickDelta, matrices, captureCollector, playerState, handsState),
                tickDelta,
                Minecraft.getInstance().player);
    }

    @Inject(
            method = "render3dHud",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ScreenEffectRenderer;submit(FLnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;Z)V",
                    shift = At.Shift.BEFORE
            ))
    private void rtxremix$submitBeforeScreenOverlays(CallbackInfo ci) {
        RtxRemixMod.submitRemixFrameIfReady();
    }

    /**
     * Covers third person, sleeping, hidden-hand, panorama, and other paths
     * where the hand pass or the screen effects are skipped.
     */
    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void rtxremix$submitAtRenderWorldTail(CallbackInfo ci) {
        RtxRemixMod.submitRemixFrameIfReady();
    }

    /**
     * The GUI draw of GameRenderer.render: HUD, in-game screen or overlay and
     * toasts. LevelRenderEvents.END_MAIN accounts for the world half of the frame.
     */
    private ProfileScope rtxremix$guiScope;

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"
            ))
    private void rtxremix$beginGuiScope(CallbackInfo ci) {
        rtxremix$closeGuiScope();
        if (ProfileScope.isJavaScopeEnabled()) {
            rtxremix$guiScope = ProfileScope.begin("GameRenderer.render.gui")
                    .plotOnClose("Java.GameRenderer.render.gui.ms");
        }
    }

    /** Before any screen draws, so the frame's hover state already matches input capture. */
    @Inject(method = "render", at = @At("HEAD"))
    private void rtxremix$followRemixMenuVisibility(CallbackInfo ci) {
        RtxRemixMod.followRemixMenuVisibility();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void rtxremix$endGuiScope(CallbackInfo ci) {
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
