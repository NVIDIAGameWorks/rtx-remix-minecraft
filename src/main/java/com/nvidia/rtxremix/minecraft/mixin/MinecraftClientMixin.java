/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.thread.ThreadExecutor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks into MinecraftClient lifecycle events.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    private static final ThreadLocal<ProfileScope> rtxremix$renderScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$renderPhaseScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$tickScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$tickPhaseScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$preGamePrepScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$beginRenderTickScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$scheduledTasksScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$soundScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$displayPrepScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$mouseScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$blitScope = new ThreadLocal<>();
    private static final ThreadLocal<Long> rtxremix$renderStartNanos = new ThreadLocal<>();

    @Inject(method = "render", at = @At("HEAD"))
    private void rtxremix$renderHead(boolean tick, CallbackInfo ci) {
        ProfileScope.beginFrame();
        rtxremix$renderStartNanos.set(System.nanoTime());
        rtxremix$openRenderScope();
        rtxremix$transitionRenderPhase("MinecraftClient.render.preGameRenderer");
        rtxremix$openScope(rtxremix$preGamePrepScope, "MinecraftClient.render.preGamePrep");
    }

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/RenderTickCounter$Dynamic;beginRenderTick(JZ)I"
        ),
        require = 0
    )
    private void rtxremix$beforeBeginRenderTick(boolean tick, CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$preGamePrepScope);
        rtxremix$openScope(rtxremix$beginRenderTickScope, "MinecraftClient.render.beginRenderTick");
    }

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/RenderTickCounter$Dynamic;beginRenderTick(JZ)I",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterBeginRenderTick(boolean tick, CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$beginRenderTickScope);
    }

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/MinecraftClient;runTasks()V"
        ),
        require = 0
    )
    private void rtxremix$beforeRunTasks(boolean tick, CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$preGamePrepScope);
        rtxremix$closeScope(rtxremix$beginRenderTickScope);
        ProfileScope.beginClientTaskDrain("MinecraftClient", rtxremix$getTaskCount());
        rtxremix$openScope(rtxremix$scheduledTasksScope, "MinecraftClient.render.runTasks");
    }

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/MinecraftClient;runTasks()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterRunTasks(boolean tick, CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$scheduledTasksScope);
        rtxremix$closeClientTaskDrain();
    }

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/sound/SoundManager;updateListenerPosition(Lnet/minecraft/client/render/Camera;)V"
        ),
        require = 0
    )
    private void rtxremix$beforeSoundListener(boolean tick, CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$preGamePrepScope);
        rtxremix$closeScope(rtxremix$beginRenderTickScope);
        rtxremix$closeScope(rtxremix$scheduledTasksScope);
        rtxremix$closeClientTaskDrain();
        rtxremix$openScope(rtxremix$soundScope, "MinecraftClient.render.soundListener");
    }

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/sound/SoundManager;updateListenerPosition(Lnet/minecraft/client/render/Camera;)V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterSoundListener(boolean tick, CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$soundScope);
    }

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(I)V"
        ),
        require = 0
    )
    private void rtxremix$beforeDisplayPrep(boolean tick, CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$preGamePrepScope);
        rtxremix$closeScope(rtxremix$beginRenderTickScope);
        rtxremix$closeScope(rtxremix$scheduledTasksScope);
        rtxremix$closeClientTaskDrain();
        rtxremix$closeScope(rtxremix$soundScope);
        rtxremix$openScope(rtxremix$displayPrepScope, "MinecraftClient.render.displayPrep");
    }

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Mouse;tick()V"
        ),
        require = 0
    )
    private void rtxremix$beforeMouseTick(boolean tick, CallbackInfo ci) {
        rtxremix$openScope(rtxremix$mouseScope, "MinecraftClient.render.mouseTick");
    }

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Mouse;tick()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterMouseTick(boolean tick, CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$mouseScope);
    }

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/GameRenderer;render(Lnet/minecraft/client/render/RenderTickCounter;Z)V"
        )
    )
    private void rtxremix$beforeGameRenderer(boolean tick, CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$preGamePrepScope);
        rtxremix$closeScope(rtxremix$beginRenderTickScope);
        rtxremix$closeScope(rtxremix$scheduledTasksScope);
        rtxremix$closeClientTaskDrain();
        rtxremix$closeScope(rtxremix$soundScope);
        rtxremix$closeScope(rtxremix$mouseScope);
        rtxremix$closeScope(rtxremix$displayPrepScope);
        rtxremix$transitionRenderPhase("MinecraftClient.render.gameRenderer");

        // Apply interactive or benchmark camera poses before GameRenderer.renderWorld
        // constructs Minecraft's interpolated Camera for this frame.
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (renderer != null) {
            renderer.onMinecraftWorldRenderFrameStart((MinecraftClient) (Object) this, tick);
        }
    }

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/GameRenderer;render(Lnet/minecraft/client/render/RenderTickCounter;Z)V",
            shift = At.Shift.AFTER
        )
    )
    private void rtxremix$afterGameRenderer(boolean tick, CallbackInfo ci) {
        rtxremix$transitionRenderPhase("MinecraftClient.render.postGameRenderer");
    }

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gl/Framebuffer;endWrite()V"
        ),
        require = 0
    )
    private void rtxremix$beforeBlit(boolean tick, CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$preGamePrepScope);
        rtxremix$closeScope(rtxremix$beginRenderTickScope);
        rtxremix$closeScope(rtxremix$scheduledTasksScope);
        rtxremix$closeClientTaskDrain();
        rtxremix$closeScope(rtxremix$soundScope);
        rtxremix$closeScope(rtxremix$displayPrepScope);
        rtxremix$closeScope(rtxremix$mouseScope);
        rtxremix$openScope(rtxremix$blitScope, "MinecraftClient.render.blitFramebuffer");
    }

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gl/Framebuffer;draw(II)V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterBlit(boolean tick, CallbackInfo ci) {
        rtxremix$closeScope(rtxremix$blitScope);
    }

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/util/Window;swapBuffers(Lnet/minecraft/client/util/tracy/TracyFrameCapturer;)V"
        )
    )
    private void rtxremix$beforeSwapBuffers(boolean tick, CallbackInfo ci) {
        rtxremix$transitionRenderPhase("MinecraftClient.render.swapBuffers");
    }

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/util/Window;swapBuffers(Lnet/minecraft/client/util/tracy/TracyFrameCapturer;)V",
            shift = At.Shift.AFTER
        )
    )
    private void rtxremix$afterSwapBuffers(boolean tick, CallbackInfo ci) {
        rtxremix$transitionRenderPhase("MinecraftClient.render.frameLimit");
    }

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;limitDisplayFPS(I)V"
        ),
        require = 0
    )
    private void rtxremix$beforeLimitDisplayFps(boolean tick, CallbackInfo ci) {
        rtxremix$transitionRenderPhase("MinecraftClient.render.limitDisplayFPS");
    }

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;limitDisplayFPS(I)V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterLimitDisplayFps(boolean tick, CallbackInfo ci) {
        rtxremix$transitionRenderPhase("MinecraftClient.render.afterFrameLimit");
    }

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Ljava/lang/Thread;yield()V"
        )
    )
    private void rtxremix$beforeYield(boolean tick, CallbackInfo ci) {
        rtxremix$transitionRenderPhase("MinecraftClient.render.yield");
    }

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Ljava/lang/Thread;yield()V",
            shift = At.Shift.AFTER
        )
    )
    private void rtxremix$afterYield(boolean tick, CallbackInfo ci) {
        rtxremix$transitionRenderPhase("MinecraftClient.render.postFrame");
    }

    // Tracy frames come from the runtime's FrameMark inside Present; a second
    // mark here would split every frame at the present boundary.
    @Inject(method = "render", at = @At("RETURN"))
    private void rtxremix$renderReturn(boolean tick, CallbackInfo ci) {
        rtxremix$closeRenderChildScopes();
        rtxremix$closeRenderPhase();
        rtxremix$closeRenderScope();
        Long startedAt = rtxremix$renderStartNanos.get();
        if (startedAt != null) {
            rtxremix$renderStartNanos.remove();
            long fullFrameNanos = System.nanoTime() - startedAt;
            ProfileScope.emitFrameLedger(fullFrameNanos);
            RemixRenderer renderer = RtxRemixMod.renderer;
            if (renderer != null) {
                renderer.onMinecraftRenderFrameEnd(fullFrameNanos);
            }
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void rtxremix$tickHead(CallbackInfo ci) {
        if (ProfileScope.isDeepProfilingEnabled()) {
            rtxremix$tickScope.set(ProfileScope.begin("MinecraftClient.tick")
                    .plotOnClose("Java.MinecraftClient.tick.ms"));
        }
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/hud/InGameHud;tick(Z)V"
        ),
        require = 0
    )
    private void rtxremix$beforeHudTick(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.hud");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/hud/InGameHud;tick(Z)V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterHudTick(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/GameRenderer;updateCrosshairTarget(F)V"
        ),
        require = 0
    )
    private void rtxremix$beforeCrosshairTarget(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.crosshairTarget");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/GameRenderer;updateCrosshairTarget(F)V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterCrosshairTarget(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayerInteractionManager;tick()V"
        ),
        require = 0
    )
    private void rtxremix$beforeInteractionTick(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.interactionManager");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayerInteractionManager;tick()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterInteractionTick(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/texture/TextureManager;tick()V"
        ),
        require = 0
    )
    private void rtxremix$beforeTextureTick(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.textures");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/texture/TextureManager;tick()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterTextureTick(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/MinecraftClient;handleInputEvents()V"
        ),
        require = 0
    )
    private void rtxremix$beforeInputEvents(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.inputEvents");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/MinecraftClient;handleInputEvents()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterInputEvents(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/GameRenderer;tick()V"
        ),
        require = 0
    )
    private void rtxremix$beforeGameRendererTick(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.gameRenderer");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/GameRenderer;tick()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterGameRendererTick(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/WorldRenderer;tick()V"
        ),
        require = 0
    )
    private void rtxremix$beforeWorldRendererTick(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.worldRenderer");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/WorldRenderer;tick()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterWorldRendererTick(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/world/ClientWorld;tickEntities()V"
        ),
        require = 0
    )
    private void rtxremix$beforeWorldEntitiesTick(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.worldEntities");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/world/ClientWorld;tickEntities()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterWorldEntitiesTick(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/world/ClientWorld;tick(Ljava/util/function/BooleanSupplier;)V"
        ),
        require = 0
    )
    private void rtxremix$beforeWorldTick(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.world");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/world/ClientWorld;tick(Ljava/util/function/BooleanSupplier;)V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterWorldTick(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/world/ClientWorld;doRandomBlockDisplayTicks(III)V"
        ),
        require = 0
    )
    private void rtxremix$beforeRandomBlockDisplayTicks(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.randomBlockDisplay");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/world/ClientWorld;doRandomBlockDisplayTicks(III)V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterRandomBlockDisplayTicks(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/particle/ParticleManager;tick()V"
        ),
        require = 0
    )
    private void rtxremix$beforeParticleManagerTick(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.particles");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/particle/ParticleManager;tick()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterParticleManagerTick(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/sound/SoundManager;tick(Z)V"
        ),
        require = 0
    )
    private void rtxremix$beforeSoundTick(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.sound");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/sound/SoundManager;tick(Z)V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterSoundTick(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Keyboard;pollDebugCrash()V"
        ),
        require = 0
    )
    private void rtxremix$beforeKeyboardTick(CallbackInfo ci) {
        rtxremix$openTickPhase("MinecraftClient.tick.keyboard");
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Keyboard;pollDebugCrash()V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void rtxremix$afterKeyboardTick(CallbackInfo ci) {
        rtxremix$closeTickPhase();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void rtxremix$tickReturn(CallbackInfo ci) {
        rtxremix$closeTickPhase();
        ProfileScope scope = rtxremix$tickScope.get();
        if (scope != null) {
            rtxremix$tickScope.remove();
            scope.close();
        }
    }

    @Inject(method = "close", at = @At("TAIL"))
    private void rtxremix$closeTail(CallbackInfo ci) {
        RtxRemixMod.shutdownAfterMinecraftClose();
    }
    
    private static void rtxremix$transitionRenderPhase(String phaseName) {
        rtxremix$closeRenderPhase();
        // The phases partition MinecraftClient.render into consecutive spans that
        // give the frame a top-level Tracy skeleton. Injected child scopes close
        // before their phase transitions; when a nested render (world join or
        // disconnect from inside tick) closes a phase under an open child,
        // ProfileScope defers the Tracy end so the zones stay well-formed.
        if (ProfileScope.isJavaScopeEnabled()) {
            rtxremix$renderPhaseScope.set(ProfileScope.begin(phaseName)
                    .plotOnClose("Java." + phaseName + ".ms"));
        }
    }

    private static void rtxremix$openRenderScope() {
        rtxremix$closeRenderScope();
        if (ProfileScope.isDeepProfilingEnabled()) {
            rtxremix$renderScope.set(ProfileScope.beginJfrOnly("MinecraftClient.render")
                    .plotOnClose("Java.MinecraftClient.render.ms"));
        }
    }

    private static void rtxremix$closeRenderScope() {
        ProfileScope scope = rtxremix$renderScope.get();
        if (scope != null) {
            rtxremix$renderScope.remove();
            scope.close();
        }
    }

    private static void rtxremix$closeRenderPhase() {
        ProfileScope scope = rtxremix$renderPhaseScope.get();
        if (scope != null) {
            rtxremix$renderPhaseScope.remove();
            scope.close();
        }
    }

    private static void rtxremix$openTickPhase(String phaseName) {
        rtxremix$closeTickPhase();
        if (ProfileScope.isDeepProfilingEnabled()) {
            rtxremix$tickPhaseScope.set(ProfileScope.begin(phaseName)
                    .plotOnClose("Java." + phaseName + ".ms"));
        }
    }

    private static void rtxremix$closeTickPhase() {
        ProfileScope scope = rtxremix$tickPhaseScope.get();
        if (scope != null) {
            rtxremix$tickPhaseScope.remove();
            scope.close();
        }
    }

    private static void rtxremix$openScope(ThreadLocal<ProfileScope> holder, String phaseName) {
        rtxremix$closeScope(holder);
        // Guard the begin() call and the plot-name concatenation: these run
        // every frame for each render sub-phase, so skipping them when no Java
        // scope is enabled avoids per-frame string allocation on the hot path.
        if (ProfileScope.isJavaScopeEnabled()) {
            holder.set(ProfileScope.begin(phaseName)
                    .plotOnClose("Java." + phaseName + ".ms"));
        }
    }

    private static void rtxremix$closeScope(ThreadLocal<ProfileScope> holder) {
        ProfileScope scope = holder.get();
        if (scope != null) {
            holder.remove();
            scope.close();
        }
    }

    private static void rtxremix$closeRenderChildScopes() {
        rtxremix$closeScope(rtxremix$preGamePrepScope);
        rtxremix$closeScope(rtxremix$beginRenderTickScope);
        rtxremix$closeScope(rtxremix$scheduledTasksScope);
        rtxremix$closeClientTaskDrain();
        rtxremix$closeScope(rtxremix$soundScope);
        rtxremix$closeScope(rtxremix$displayPrepScope);
        rtxremix$closeScope(rtxremix$mouseScope);
        rtxremix$closeScope(rtxremix$blitScope);
    }

    private int rtxremix$getTaskCount() {
        // O(n) in the queued task backlog; skip it when nothing records the value.
        if (!ProfileScope.isClientTaskProfilingEnabled()) {
            return 0;
        }
        return ((ThreadExecutor<?>)(Object)this).getTaskCount();
    }

    private static void rtxremix$closeClientTaskDrain() {
        MinecraftClient client = MinecraftClient.getInstance();
        int taskCount = 0;
        if (client != null && ProfileScope.isClientTaskProfilingEnabled()) {
            taskCount = ((ThreadExecutor<?>)client).getTaskCount();
        }
        ProfileScope.endClientTaskDrain(taskCount);
    }
}
