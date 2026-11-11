/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixException;
import com.nvidia.rtxremix.minecraft.light.BlockLightIntensityControls;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.terrain.ChunkManager;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.MinecraftRuntime;
import com.nvidia.rtxremix.minecraft.sky.SkyLightRenderer;
import com.nvidia.rtxremix.minecraft.sky.SkyRenderer;
import com.nvidia.rtxremix.minecraft.UnfocusedFramerateLimit;
import com.nvidia.rtxremix.minecraft.entity.RemixEntityPass;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Constructs the curated RtxSettings bound to a live, initialized RemixRenderer. */
public final class RtxSettingsFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(RtxSettingsFactory.class);

    /**
     * Minecraft's maximum render distance, and therefore the point past which a
     * cull can no longer remove anything.
     */
    private static final int MAX_DISTANCE_CHUNKS = 32;

    private RtxSettingsFactory() {}

    /**
     * Builds the curated registry.
     *
     * <p>A new setting is one entry here, bound to an authoritative live getter
     * and setter, plus the {@code rtxremix.setting.} label and {@code .tip}
     * keys for its id in the language file. It then appears in
     * the menu, persists, and resets without further wiring. A setting needs
     * both a live model and a defined authored baseline. A boolean Remix
     * option gets both from {@link #remixBool}.
     *
     * <p><b>A declared range must contain the live default.</b> Defaults are
     * captured from the live getters as this method runs. A default outside its
     * own {@code [min, max]} is rejected by the store's range guard when the
     * saved file is read back, so the setting silently loses its persisted
     * value. Sun and moon intensity are normalized to {@code 1.0}; the other
     * artist-authored controls expose their native values, so check the runtime
     * model as well as the range when adding one.
     *
     * <p><b>The label must fit its widget.</b> Option rows are 150 px vanilla
     * widgets that draw {@code Label: Value} with a 2 px margin, and wider text
     * scrolls as a marquee that clips the label. {@code SettingsRowWidthTest}
     * measures every label at its registered widest value with the real
     * default-font advances, so a new setting also needs an entry there.
     */
    public static RtxSettings build(RemixRenderer renderer) {
        List<RtxSetting<?>> list = new ArrayList<>();

        ChunkManager chunks = renderer.getChunkManager();
        RemixEntityPass entities = renderer.getEntityPass();
        PostProcessingSettings postProcessing = renderer.getPostProcessingSettings();

        // --- Render distances (live-bound to managers) ---
        list.add(RtxSetting.ofBool("dist.autoFade", SettingGroup.RENDER_DISTANCE,
                "rtxremix.setting.dist.autoFade", "rtxremix.setting.dist.autoFade.tip",
                renderer.isViewDistanceFadeAuto(), renderer::isViewDistanceFadeAuto,
                renderer::setViewDistanceFadeAuto));

        // Both ranges are 0 to 32 chunks and both zeros mean Auto, Minecraft's
        // render distance. 32 is Minecraft's maximum render distance and
        // therefore the point past which a cull stops culling anything, so one
        // ceiling fits every distance and they share a single sentinel position.
        list.add(RtxSetting.ofInt("dist.light", SettingGroup.RENDER_DISTANCE,
                "rtxremix.setting.dist.light", "rtxremix.setting.dist.light.tip",
                0, MAX_DISTANCE_CHUNKS, chunks.getLightCullDistance(),
                chunks::getLightCullDistance, chunks::setLightCullDistance));

        list.add(RtxSetting.ofInt("dist.entity", SettingGroup.RENDER_DISTANCE,
                "rtxremix.setting.dist.entity", "rtxremix.setting.dist.entity.tip",
                0, MAX_DISTANCE_CHUNKS, entities.getEntityCullDistance(),
                entities::getEntityCullDistance, entities::setEntityCullDistance));

        list.add(RtxSetting.ofInt("light.reach", SettingGroup.RENDER_DISTANCE,
                "rtxremix.setting.light.reach", "rtxremix.setting.light.reach.tip",
                ChunkManager.MIN_LIGHT_REACH_BLOCKS, ChunkManager.MAX_LIGHT_REACH_BLOCKS,
                chunks.getLightReach(), chunks::getLightReach, chunks::setLightReach));

        // --- Framerate ---
        list.add(unfocusedFramerate());

        // --- Sky & time of day ---
        list.add(RtxSetting.ofBool("sky.enabled", SettingGroup.SKY,
                "rtxremix.setting.sky.enabled", "rtxremix.setting.sky.enabled.tip",
                MinecraftRuntime.isSkyEnabled(), MinecraftRuntime::isSkyEnabled,
                v -> {
                    MinecraftRuntime.setSkyEnabled(v);
                    if (renderer.getSkyRenderer() != null) {
                        renderer.getSkyRenderer().forceRefresh();
                    }
                }));

        list.add(RtxSetting.ofBool("sky.ambient", SettingGroup.SKY,
                "rtxremix.setting.sky.ambient", "rtxremix.setting.sky.ambient.tip",
                SkyRenderer.isSkyAmbientRectEnabled(),
                SkyRenderer::isSkyAmbientRectEnabled,
                v -> {
                    SkyRenderer.setSkyAmbientRectEnabled(v);
                    if (renderer.getSkyRenderer() != null) {
                        renderer.getSkyRenderer().forceRefresh();
                    }
                }));
        list.add(skyFloat(
                renderer,
                "sky.mieScatteringAnisotropy",
                "rtxremix.setting.sky.haloFocus",
                0.0,
                SkyRenderer.MAX_MIE_SCATTERING_ANISOTROPY,
                SkyRenderer::getMieScatteringAnisotropy,
                SkyRenderer::setMieScatteringAnisotropy));
        list.add(skyFloat(
                renderer,
                "sky.minimumCelestialWeatherVisibility",
                "rtxremix.setting.sky.minimumCelestialWeatherVisibility",
                0.0,
                1.0,
                SkyRenderer::getMinimumCelestialWeatherVisibility,
                SkyRenderer::setMinimumCelestialWeatherVisibility));

        list.add(RtxSetting.ofBool("clouds.procedural", SettingGroup.CLOUDS,
                "rtxremix.setting.clouds.procedural",
                "rtxremix.setting.clouds.procedural.tip",
                false,
                renderer.getCloudRenderer()::isProceduralClouds,
                renderer.getCloudRenderer()::setProceduralClouds));
        list.add(floatSetting(
                "clouds.vanillaEdgeSmoothness",
                SettingGroup.CLOUDS,
                "rtxremix.setting.clouds.vanillaEdgeSmoothness",
                0.0,
                1.99,
                renderer.getCloudRenderer()::getVanillaEdgeSmoothness,
                renderer.getCloudRenderer()::setVanillaEdgeSmoothness)
                .deferSliderApply());
        list.add(floatSetting(
                "clouds.size",
                SettingGroup.CLOUDS,
                "rtxremix.setting.clouds.size",
                0.1,
                2.0,
                renderer.getCloudRenderer()::getCloudSize,
                renderer.getCloudRenderer()::setCloudSize)
                .deferSliderApply());
        list.add(floatSetting(
                "clouds.height",
                SettingGroup.CLOUDS,
                "rtxremix.setting.clouds.height",
                -128.0,
                128.0,
                renderer.getCloudRenderer()::getCloudsHeight,
                renderer.getCloudRenderer()::setCloudsHeight));

        list.add(skyFloat(renderer, "sky.sunIntensity", "rtxremix.setting.sky.sunIntensity",
                0.0, 100.0,
                SkyLightRenderer::getSunIntensity, SkyLightRenderer::setSunIntensity));
        list.add(skyFloat(renderer, "sky.moonIntensity", "rtxremix.setting.sky.moonIntensity",
                0.0, 10.0,
                SkyLightRenderer::getMoonIntensity, SkyLightRenderer::setMoonIntensity));
        list.add(skyFloat(renderer, "sky.dayAmbient", "rtxremix.setting.sky.dayAmbient",
                0.0, 5.0,
                SkyRenderer::getDayAmbientBoost, SkyRenderer::setDayAmbientBoost));
        list.add(skyFloat(renderer, "sky.nightAmbient", "rtxremix.setting.sky.nightAmbient",
                0.0, 10.0,
                SkyRenderer::getNightAmbientBoost, SkyRenderer::setNightAmbientBoost));

        for (BlockLightIntensityControls.Control control
                : BlockLightIntensityControls.controls()) {
            list.add(RtxSetting.ofFloat(
                    control.settingId(),
                    SettingGroup.BLOCK_LIGHTS,
                    control.labelKey(),
                    "rtxremix.setting.blockLights.multiplier.tip",
                    0.0,
                    BlockLightIntensityControls.MAX_MULTIPLIER,
                    BlockLightIntensityControls.DEFAULT_MULTIPLIER,
                    control::get,
                    control::set));
        }

        // --- Materials ---
        BlockTextureManager textures = renderer.getTextureManager();
        list.add(RtxSetting.ofFloat(
                RtxOptionFactory.TERRAIN_EMISSION_MULTIPLIER_ID,
                SettingGroup.MATERIALS,
                "rtxremix.setting.materials.terrainEmission",
                "rtxremix.setting.materials.terrainEmission.tip",
                0.0,
                BlockTextureManager.MAX_TERRAIN_EMISSION_MULTIPLIER,
                BlockTextureManager.DEFAULT_TERRAIN_EMISSION_MULTIPLIER,
                () -> (double) textures.getTerrainEmissionMultiplier(),
                value -> textures.setTerrainEmissionMultiplier(value.floatValue())));
        RemixAPI remix = renderer.getRemixApi();
        remixBool("materials.smoothReliefMaps", SettingGroup.MATERIALS,
                "rtxremix.setting.materials.smoothReliefMaps",
                "rtx.materials.smoothReliefMapFiltering",
                remix::getConfigVariableResolved, remix::setConfigVariable)
                .ifPresent(list::add);

        // --- Tonemapping ---
        list.add(floatSetting("post.exposure", SettingGroup.TONEMAPPING,
                "rtxremix.setting.post.exposure", 0.0, 2.0,
                postProcessing::getExposure, postProcessing::setExposure));
        list.add(floatSetting("post.shadows", SettingGroup.TONEMAPPING,
                "rtxremix.setting.post.shadows", -5.0, 5.0,
                postProcessing::getShadowLevel, postProcessing::setShadowLevel));
        list.add(floatSetting("post.highlights", SettingGroup.TONEMAPPING,
                "rtxremix.setting.post.highlights", -5.0, 10.0,
                postProcessing::getHighlightsLevel, postProcessing::setHighlightsLevel));

        // --- Eye adaptation ---
        list.add(floatSetting("post.lowLightAdaptation", SettingGroup.EYE_ADAPTATION,
                "rtxremix.setting.post.lowLightAdaptation", -5.0, 5.0,
                postProcessing::getLowLightAdaptation, postProcessing::setLowLightAdaptation));
        list.add(floatSetting("post.highLightAdaptation", SettingGroup.EYE_ADAPTATION,
                "rtxremix.setting.post.highLightAdaptation", -5.0, 10.0,
                postProcessing::getHighLightAdaptation, postProcessing::setHighLightAdaptation));

        return new RtxSettings(list);
    }

    static RtxSetting<Integer> unfocusedFramerate() {
        return RtxSetting.ofInt(RtxOptionFactory.UNFOCUSED_FRAMERATE_ID, SettingGroup.FRAMERATE,
                "rtxremix.setting.framerate.unfocused",
                "rtxremix.setting.framerate.unfocused.tip",
                UnfocusedFramerateLimit.MIN, UnfocusedFramerateLimit.UNLIMITED,
                UnfocusedFramerateLimit.DEFAULT,
                UnfocusedFramerateLimit::get, UnfocusedFramerateLimit::set)
                .withStep(UnfocusedFramerateLimit.STEP);
    }

    /**
     * Binds a boolean Remix option, with the value Remix resolved at startup as
     * its default. The setting then reports its own writes rather than reading
     * the option again, because Remix resolves a write at a later option
     * update and a read before that still returns the previous value.
     *
     * <p>Returns empty when the loaded runtime cannot resolve the option, for
     * example a runtime built before the option existed, so the menu leaves
     * the setting out instead of failing client startup.
     */
    static Optional<RtxSetting<Boolean>> remixBool(String id, SettingGroup group, String labelKey,
            String option, Function<String, String> configReader,
            BiConsumer<String, String> configWriter) {
        boolean[] value = new boolean[1];
        try {
            value[0] = Boolean.parseBoolean(configReader.apply(option));
        } catch (RemixException e) {
            LOGGER.warn("Remix option {} is unavailable in this runtime, so the {} setting is hidden",
                    option, id, e);
            return Optional.empty();
        }
        return Optional.of(RtxSetting.ofBool(id, group, labelKey, labelKey + ".tip", value[0],
                () -> value[0],
                v -> {
                    value[0] = v;
                    configWriter.accept(option, Boolean.toString(v));
                }));
    }

    private static RtxSetting<Double> skyFloat(RemixRenderer renderer, String id, String labelKey,
            double min, double max,
            Supplier<Float> getter, Consumer<Float> setter) {
        return RtxSetting.ofFloat(id, SettingGroup.SKY, labelKey, labelKey + ".tip",
                min, max, getter.get(),
                () -> (double) getter.get(),
                v -> {
                    setter.accept(v.floatValue());
                    if (renderer.getSkyRenderer() != null) {
                        renderer.getSkyRenderer().forceRefresh();
                    }
                });
    }

    private static RtxSetting<Double> floatSetting(String id, SettingGroup group, String labelKey,
            double min, double max,
            Supplier<Float> getter, Consumer<Float> setter) {
        return RtxSetting.ofFloat(id, group, labelKey, labelKey + ".tip", min, max, getter.get(),
                () -> (double) getter.get(), v -> setter.accept(v.floatValue()));
    }

}
