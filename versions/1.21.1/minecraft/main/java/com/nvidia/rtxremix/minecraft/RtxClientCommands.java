/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.nvidia.rtxremix.minecraft.camera.CameraController;
import com.nvidia.rtxremix.minecraft.camera.CameraPlaybackMode;
import com.nvidia.rtxremix.minecraft.camera.StillShot;
import com.nvidia.rtxremix.minecraft.light.HeldLightState;
import com.nvidia.rtxremix.minecraft.logic.ConfLayerDriver;
import com.nvidia.rtxremix.minecraft.logic.LogicChannels;
import com.nvidia.rtxremix.minecraft.logic.MarkerBlockScanner;
import com.nvidia.rtxremix.minecraft.logic.ZoneDefinition;
import com.nvidia.rtxremix.minecraft.logic.ZonePersistentState;
import com.nvidia.rtxremix.minecraft.logic.ZoneRegistry;
import com.nvidia.rtxremix.minecraft.sky.SkyLightRenderer;
import com.nvidia.rtxremix.minecraft.sky.SkyRenderer;
import com.nvidia.rtxremix.minecraft.terrain.ChunkManager;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Canonical loader-neutral implementation of the full client {@code /rtx} tree. */
public final class RtxClientCommands<S> {
    private static final Logger LOGGER = LoggerFactory.getLogger(RtxClientCommands.class);
    private static final String DOCS_SHOT_SET = "docs";

    /** Loader bridge for the two source operations used by command behavior. */
    public interface SourceAdapter<S> {
        void feedback(S source, Component message);
        LocalPlayer player(S source);
    }

    private final MinecraftClientRuntime runtime;
    private final SourceAdapter<S> sourceAdapter;

    public RtxClientCommands(MinecraftClientRuntime runtime, SourceAdapter<S> sourceAdapter) {
        this.runtime = runtime;
        this.sourceAdapter = sourceAdapter;
    }

    public void register(CommandDispatcher<S> dispatcher) {
        dispatcher.register(literal("rtx")
            .executes(context -> {
                toggleRtx();
                feedback(context.getSource(), Component.literal("RTX Rendering: " + (MinecraftRuntime.isRtxEnabled() ? "ON" : "OFF")));
                return 1;
            })
            .then(literal("on")
                .executes(context -> {
                    MinecraftRuntime.setRtxEnabled(true);
                    feedback(context.getSource(), Component.literal("RTX Rendering: ON"));
                    return 1;
                }))
            .then(literal("off")
                .executes(context -> {
                    MinecraftRuntime.setRtxEnabled(false);
                    feedback(context.getSource(), Component.literal("RTX Rendering: OFF"));
                    return 1;
                }))
            .then(literal("status")
                .executes(context -> {
                    feedback(context.getSource(), Component.literal("RTX Status:"));
                    feedback(context.getSource(), Component.literal("  Enabled (master): " + MinecraftRuntime.isRtxEnabled()));
                    feedback(context.getSource(), Component.literal(
                            "  World: " + MinecraftRuntime.isWorldEnabled() + " (active " + isWorldActive() + ")"
                            + ", Entities: " + MinecraftRuntime.isEntitiesEnabled() + " (active " + isEntitiesActive() + ")"
                            + ", BlockEntities: " + MinecraftRuntime.isBlockEntitiesEnabled() + " (active " + isBlockEntitiesActive() + ")"
                            + ", Particles: " + MinecraftRuntime.isParticlesEnabled() + " (active " + isParticlesActive() + ")"
                            + ", Sky: " + MinecraftRuntime.isSkyEnabled() + " (active " + isSkyActive() + ")"));
                    feedback(context.getSource(), Component.literal("  Sky Dome: " + SkyRenderer.isEnabled() + ", Sky Ambient Rect: " + SkyRenderer.isSkyAmbientRectEnabled()));
                    feedback(context.getSource(), Component.literal(
                            "  Held Lights: " + (HeldLightState.isLocalHeldLightsOff() ? "OFF" : "ON")
                            + ", Held Flames Under Water: "
                            + (HeldLightState.areFlamesAllowedUnderwater() ? "ALLOWED" : "EXTINGUISHED")));
                    feedback(context.getSource(), Component.literal(
                            "  Renderer Initialized: " + (renderer() != null && renderer().isInitialized())));
                    feedback(context.getSource(), Component.literal("  SDK Available: " + runtime.isRemixAvailable()));
                    if (renderer() != null && renderer().getChunkManager() != null) {
                        ChunkManager cm = renderer().getChunkManager();
                        feedback(context.getSource(), Component.literal("  Loaded Chunks: " + cm.getLoadedChunkCount()));
                        feedback(context.getSource(), Component.literal("  Retained Pages: " + cm.getTerrainDiagnostics().retainedPages()));
                    }
                    if (renderer() != null && renderer().getEntityPass() != null) {
                        feedback(context.getSource(), Component.literal("  Entity Cull Distance: "
                                + renderer().getEntityPass().getEntityCullDistance() + " chunks"));
                    }
                    if (renderer() != null && renderer().getChunkManager() != null) {
                        feedback(context.getSource(), Component.literal("  Light Cull Distance: "
                                + renderer().getChunkManager().getLightCullDistance() + " chunks"
                                + (renderer().getChunkManager().getLightCullDistance() == 0 ? " (view distance)" : "")));
                        feedback(context.getSource(), Component.literal("  Light Reach: "
                                + renderer().getChunkManager().getLightReach() + " blocks"));
                    }
                    if (renderer() != null && renderer().getLightManager() != null) {
                        feedback(context.getSource(), Component.literal("  Active Lights: " + renderer().getLightManager().getLightCount()));
                    }
                    if (renderer() != null) {
                        feedback(context.getSource(), Component.literal("  View Distance Fade: " + renderer().getViewDistanceFade().describe()));
                    }
                    return 1;
                }))
            .then(literal("entities")
                .executes(context -> {
                    setEntitiesEnabled(!MinecraftRuntime.isEntitiesEnabled());
                    feedback(context.getSource(), Component.literal("Entity Rendering: " + (MinecraftRuntime.isEntitiesEnabled() ? "ON" : "OFF")));
                    return 1;
                }))
            // /rtx blockentities - toggle block entity rendering (chests, furnaces, signs, etc.)
            .then(literal("blockentities")
                .executes(context -> {
                    setBlockEntitiesEnabled(!MinecraftRuntime.isBlockEntitiesEnabled());
                    feedback(context.getSource(), Component.literal("Block Entity Rendering: " + (MinecraftRuntime.isBlockEntitiesEnabled() ? "ON" : "OFF")));
                    return 1;
                }))
            .then(literal("particles")
                .executes(context -> {
                    setParticlesEnabled(!MinecraftRuntime.isParticlesEnabled());
                    feedback(context.getSource(), Component.literal("Particle Rendering: " + (MinecraftRuntime.isParticlesEnabled() ? "ON" : "OFF")));
                    return 1;
                }))
            // /rtx heldlights - put the local player's held lights out or relight
            // them, as the Toggle Held Lights key does; /rtx heldlights underwater
            // lets open flames keep burning under water.
            .then(literal("heldlights")
                .executes(context -> {
                    boolean off = HeldLightState.toggleLocalHeldLights();
                    feedback(context.getSource(), Component.literal("Held lights: " + (off ? "OFF" : "ON")));
                    return 1;
                })
                .then(literal("underwater")
                    .executes(context -> {
                        boolean allowed = !HeldLightState.areFlamesAllowedUnderwater();
                        HeldLightState.setFlamesAllowedUnderwater(allowed);
                        feedback(context.getSource(), Component.literal(
                                "Held flames under water: " + (allowed ? "ALLOWED" : "EXTINGUISHED")));
                        return 1;
                    })))
            // /rtx world - toggle world rendering
            .then(literal("world")
                .executes(context -> {
                    setWorldEnabled(!MinecraftRuntime.isWorldEnabled());
                    feedback(context.getSource(), Component.literal("World Rendering: " + (MinecraftRuntime.isWorldEnabled() ? "ON" : "OFF")));
                    return 1;
                }))
            .then(literal("skyDomeMult")
                .executes(context -> {
                    feedback(context.getSource(), Component.literal(
                            "Sky dome multiplier: " + SkyRenderer.getSkyDomeMultiplier()));
                    return 1;
                })
                .then(argument("value",
                        FloatArgumentType.floatArg(0.0f))
                    .executes(context -> {
                        float v = FloatArgumentType.getFloat(context, "value");
                        SkyRenderer.setSkyDomeMultiplier(v);
                        feedback(context.getSource(), Component.literal("Sky dome multiplier: " + v));
                        return 1;
                    })))
            .then(literal("sky")
                .executes(context -> {
                    MinecraftRuntime.setSkyEnabled(!MinecraftRuntime.isSkyEnabled());
                    feedback(context.getSource(), Component.literal("Sky Rendering: " + (MinecraftRuntime.isSkyEnabled() ? "ON" : "OFF")));
                    return 1;
                })
                .then(literal("ambient")
                    .executes(context -> {
                        SkyRenderer.setSkyAmbientRectEnabled(!SkyRenderer.isSkyAmbientRectEnabled());
                        boolean on = SkyRenderer.isSkyAmbientRectEnabled();
                        feedback(context.getSource(), Component.literal("Sky Ambient Rect: " + (on ? "ON" : "OFF")));
                        return 1;
                    })
                    .then(literal("night")
                        .executes(context -> {
                            feedback(context.getSource(), Component.literal("Night sky ambient boost: " + SkyRenderer.getNightAmbientBoost()));
                            return 1;
                        })
                        .then(argument("value",
                                FloatArgumentType.floatArg(0.0f, 10.0f))
                            .executes(context -> {
                                float v = FloatArgumentType.getFloat(context, "value");
                                SkyRenderer.setNightAmbientBoost(v);
                                if (renderer() != null && renderer().getSkyRenderer() != null)
                                    renderer().getSkyRenderer().forceRefresh();
                                feedback(context.getSource(), Component.literal("Night sky ambient boost: " + v));
                                return 1;
                            })))
                    .then(literal("day")
                        .executes(context -> {
                            feedback(context.getSource(), Component.literal("Day sky ambient boost: " + SkyRenderer.getDayAmbientBoost()));
                            return 1;
                        })
                        .then(argument("value",
                                FloatArgumentType.floatArg(0.0f, 5.0f))
                            .executes(context -> {
                                float v = FloatArgumentType.getFloat(context, "value");
                                SkyRenderer.setDayAmbientBoost(v);
                                if (renderer() != null && renderer().getSkyRenderer() != null)
                                    renderer().getSkyRenderer().forceRefresh();
                                feedback(context.getSource(), Component.literal("Day sky ambient boost: " + v));
                                return 1;
                            }))))
                .then(literal("minimumopacity")
                    .executes(context -> {
                        feedback(context.getSource(), Component.literal("Minimum celestial weather visibility: " + SkyRenderer.getMinimumCelestialWeatherVisibility()));
                        return 1;
                    })
                    .then(argument("value",
                            FloatArgumentType.floatArg(0.0f, 1.0f))
                        .executes(context -> {
                            float v = FloatArgumentType.getFloat(context, "value");
                            SkyRenderer.setMinimumCelestialWeatherVisibility(v);
                            if (renderer() != null && renderer().getSkyRenderer() != null)
                                renderer().getSkyRenderer().forceRefresh();
                            runtime.saveSettings();
                            feedback(context.getSource(),
                                    Component.literal("Minimum celestial weather visibility: " + v));
                            return 1;
                        })))
                .then(literal("sun")
                    .executes(context -> {
                        feedback(context.getSource(), Component.literal("Sun intensity: " + SkyLightRenderer.getSunIntensity()));
                        return 1;
                    })
                    .then(argument("value",
                            FloatArgumentType.floatArg(0.0f, 100.0f))
                        .executes(context -> {
                            float v = FloatArgumentType.getFloat(context, "value");
                            SkyLightRenderer.setSunIntensity(v);
                            if (renderer() != null && renderer().getSkyRenderer() != null)
                                renderer().getSkyRenderer().forceRefresh();
                            feedback(context.getSource(), Component.literal("Sun intensity: " + v));
                            return 1;
                        })))
                .then(literal("moon")
                    .executes(context -> {
                        feedback(context.getSource(), Component.literal("Moon intensity: " + SkyLightRenderer.getMoonIntensity()));
                        return 1;
                    })
                    .then(argument("value",
                            FloatArgumentType.floatArg(0.0f, 10.0f))
                        .executes(context -> {
                            float v = FloatArgumentType.getFloat(context, "value");
                            SkyLightRenderer.setMoonIntensity(v);
                            if (renderer() != null && renderer().getSkyRenderer() != null)
                                renderer().getSkyRenderer().forceRefresh();
                            feedback(context.getSource(), Component.literal("Moon intensity: " + v));
                            return 1;
                        }))))
            .then(literal("culldistance")
                .executes(context -> {
                    if (renderer() != null && renderer().getChunkManager() != null) {
                        int light = renderer().getChunkManager().getLightCullDistance();
                        feedback(context.getSource(), Component.literal("Light cull distance: " + light + " chunks" + (light == 0 ? " (view distance)" : "")));
                        if (renderer().getEntityPass() != null) {
                            int entity = renderer().getEntityPass().getEntityCullDistance();
                            feedback(context.getSource(), Component.literal("Entity cull distance: " + entity + " chunks" + (entity == 0 ? " (view distance)" : "")));
                        }
                        feedback(context.getSource(), Component.literal("Light reach: "
                                + renderer().getChunkManager().getLightReach() + " blocks (torch cutoff)"));
                    } else {
                        feedback(context.getSource(), Component.literal("Chunk manager not available"));
                    }
                    return 1;
                })
                .then(literal("light")
                    .then(argument("chunks",
                            IntegerArgumentType.integer(0, 32))
                        .executes(context -> {
                            int n = IntegerArgumentType.getInteger(context, "chunks");
                            if (renderer() != null && renderer().getChunkManager() != null) {
                                renderer().getChunkManager().setLightCullDistance(n);
                                feedback(context.getSource(), Component.literal("Light cull distance set to " + n + " chunks" + (n == 0 ? " (view distance)" : "")));
                            } else {
                                feedback(context.getSource(), Component.literal("Chunk manager not available"));
                            }
                            return 1;
                        })))
                .then(literal("entity")
                    .then(argument("chunks",
                            IntegerArgumentType.integer(0, 32))
                        .executes(context -> {
                            int n = IntegerArgumentType.getInteger(context, "chunks");
                            if (renderer() != null && renderer().getEntityPass() != null) {
                                renderer().getEntityPass().setEntityCullDistance(n);
                                feedback(context.getSource(), Component.literal("Entity cull distance set to " + n + " chunks" + (n == 0 ? " (view distance)" : "")));
                            } else {
                                feedback(context.getSource(), Component.literal("Entity renderer() not available"));
                            }
                            return 1;
                        })))
                .then(literal("reach")
                    .then(argument("blocks",
                            IntegerArgumentType.integer(
                                    ChunkManager.MIN_LIGHT_REACH_BLOCKS, ChunkManager.MAX_LIGHT_REACH_BLOCKS))
                        .executes(context -> {
                            int n = IntegerArgumentType.getInteger(context, "blocks");
                            if (renderer() != null && renderer().getChunkManager() != null) {
                                renderer().getChunkManager().setLightReach(n);
                                feedback(context.getSource(), Component.literal("Light reach set to " + n + " blocks"));
                            } else {
                                feedback(context.getSource(), Component.literal("Chunk manager not available"));
                            }
                            return 1;
                        }))))
            .then(literal("lights")
                .executes(context -> {
                    if (renderer() == null || renderer().getChunkManager() == null) {
                        feedback(context.getSource(), Component.literal("Chunk manager not available"));
                        return 1;
                    }
                    List<ChunkManager.LightRuleStats> stats =
                            renderer().getChunkManager().lightStatsByRule();
                    int total = 0, drawn = 0;
                    for (ChunkManager.LightRuleStats s : stats) {
                        total += s.total();
                        drawn += s.drawn();
                    }
                    feedback(context.getSource(), Component.literal(
                            "Terrain lights: " + drawn + " drawn of " + total));
                    for (ChunkManager.LightRuleStats s : stats) {
                        String ruleId = s.ruleId();
                        int separator = ruleId.indexOf(':');
                        String rulePath = separator >= 0
                                ? ruleId.substring(separator + 1)
                                : ruleId;
                        feedback(context.getSource(), Component.literal(
                                "  " + rulePath + ": "
                                        + s.drawn() + " / " + s.total()));
                    }
                    return 1;
                }))
            .then(literal("preset")
                .executes(context -> {
                    feedback(context.getSource(), Component.literal(
                            "Presets: " + String.join(", ", QualityPreset.ids())
                                    + ". A preset sets Remix's graphics tier for this session."));
                    return 1;
                })
                .then(argument("name",
                        StringArgumentType.word())
                    .suggests((context, builder) -> {
                        for (String name : QualityPreset.ids()) {
                            builder.suggest(name);
                        }
                        return builder.buildFuture();
                    })
                    .executes(context -> {
                        String name = StringArgumentType
                                .getString(context, "name");
                        feedback(context.getSource(), applyQualityPreset(name));
                        return 1;
                    })))
            .then(literal("viewdistance")
                .executes(context -> {
                    if (renderer() != null) {
                        feedback(context.getSource(), Component.literal("View distance fade: " + renderer().getViewDistanceFade().describe()));
                    } else {
                        feedback(context.getSource(), Component.literal("Renderer not available"));
                    }
                    return 1;
                })
                .then(literal("auto")
                    .executes(context -> {
                        if (renderer() != null) {
                            boolean on = !renderer().getViewDistanceFade().isAuto();
                            renderer().setViewDistanceFadeAuto(on);
                            feedback(context.getSource(), Component.literal("View distance fade: " + renderer().getViewDistanceFade().describe()));
                        }
                        return 1;
                    })
                    .then(literal("on")
                        .executes(context -> {
                            if (renderer() != null) {
                                renderer().setViewDistanceFadeAuto(true);
                                feedback(context.getSource(), Component.literal("View distance fade: " + renderer().getViewDistanceFade().describe()));
                            }
                            return 1;
                        }))
                    .then(literal("off")
                        .executes(context -> {
                            if (renderer() != null) {
                                renderer().setViewDistanceFadeAuto(false);
                                feedback(context.getSource(), Component.literal("View distance fade: " + renderer().getViewDistanceFade().describe()));
                            }
                            return 1;
                        }))))
            .then(literal("reload")
                .executes(context -> {
                    if (MinecraftRuntime.rebuildLoadedTerrain()) {
                        feedback(context.getSource(), Component.literal("Terrain cleared - recapturing the sections that were built"));
                    } else {
                        feedback(context.getSource(), Component.literal("Remix world rendering inactive - nothing to reload"));
                    }
                    return 1;
                }))
            .then(sssCommand())
            .then(zoneCommand())
            .then(literal("conf")
                .then(literal("reload")
                    .executes(context -> {
                        // Layers parse their conf files at acquire time, so a
                        // release + re-acquire re-reads every conf from disk and
                        // re-scans confs/zones/ for new or removed zone confs:
                        // a no-restart iteration loop for conf authors.
                        ConfLayerDriver driver = renderer() != null ? renderer().getConfLayerDriver() : null;
                        if (driver == null) {
                            feedback(context.getSource(), Component.literal(
                                    "Conf layers unavailable: the Remix renderer() is not initialized"));
                            return 1;
                        }
                        if (driver.reload()) {
                            feedback(context.getSource(), Component.literal(
                                    "Conf layers re-acquired (zones rescanned) - edited conf values are live"));
                        } else if (driver.isActive()) {
                            // reload() also reports failure when only the
                            // selected quality preset could not be
                            // re-acquired; the catalog is driving.
                            feedback(context.getSource(), Component.literal(
                                    "Conf layers re-acquired, but the selected quality preset conf could not be "
                                            + "re-acquired (see log)"));
                        } else {
                            feedback(context.getSource(), Component.literal(
                                    "Conf layer reload failed - conf-layer driving disabled (see log)"));
                        }
                        return 1;
                    }))
                .then(literal("status")
                    // Default: only interesting rows (nonzero strengths and
                    // unacquired layers) + a summary; "all" dumps every layer.
                    .executes(context -> sendConfStatus(context.getSource(), false))
                    .then(literal("all")
                        .executes(context -> sendConfStatus(context.getSource(), true)))))
            .then(literal("water")
                .executes(context -> {
                    BlockTextureManager textureManager = getTextureManager();
                    if (textureManager == null) {
                        feedback(context.getSource(), Component.literal("Water material not available"));
                        return 0;
                    }
                    sendWaterStatus(context.getSource(), textureManager);
                    return 1;
                })
                .then(literal("ior")
                    .executes(context -> {
                        BlockTextureManager textureManager = getTextureManager();
                        if (textureManager == null) {
                            feedback(context.getSource(), Component.literal("Water material not available"));
                            return 0;
                        }
                        feedback(context.getSource(), Component.literal("Water IOR: " + textureManager.getWaterIor()));
                        return 1;
                    })
                    .then(argument("value",
                        FloatArgumentType.floatArg(1.0f, 5.0f))
                        .executes(context -> {
                            float value = FloatArgumentType.getFloat(context, "value");
                            return updateWaterSetting(context.getSource(),
                                    manager -> manager.updateWaterIor(value),
                                    "Water IOR set to " + value);
                        })))
                .then(literal("distance")
                    .executes(context -> {
                        BlockTextureManager textureManager = getTextureManager();
                        if (textureManager == null) {
                            feedback(context.getSource(), Component.literal("Water material not available"));
                            return 0;
                        }
                        feedback(context.getSource(), Component.literal(
                            "Water transmittance distance: " + textureManager.getWaterTransmittanceDistance()));
                        return 1;
                    })
                    .then(argument("value",
                        FloatArgumentType.floatArg(0.001f, 1000.0f))
                        .executes(context -> {
                            float value = FloatArgumentType.getFloat(context, "value");
                            return updateWaterSetting(context.getSource(),
                                    manager -> manager.updateWaterTransmittanceDistance(value),
                                    "Water transmittance distance set to " + value);
                        })))
                .then(literal("color")
                    .executes(context -> {
                        BlockTextureManager textureManager = getTextureManager();
                        if (textureManager == null) {
                            feedback(context.getSource(), Component.literal("Water material not available"));
                            return 0;
                        }
                        feedback(context.getSource(), Component.literal("Water transmittance color: "
                            + textureManager.getWaterTransmittanceR() + ", "
                            + textureManager.getWaterTransmittanceG() + ", "
                            + textureManager.getWaterTransmittanceB()));
                        return 1;
                    })
                    .then(argument("r",
                        FloatArgumentType.floatArg(0.0f, 10.0f))
                        .then(argument("g",
                            FloatArgumentType.floatArg(0.0f, 10.0f))
                            .then(argument("b",
                                FloatArgumentType.floatArg(0.0f, 10.0f))
                                .executes(context -> {
                                    float r = FloatArgumentType.getFloat(context, "r");
                                    float g = FloatArgumentType.getFloat(context, "g");
                                    float b = FloatArgumentType.getFloat(context, "b");
                                    return updateWaterSetting(context.getSource(),
                                            manager -> manager.updateWaterTransmittanceColor(r, g, b),
                                            "Water transmittance color set to " + r + ", " + g + ", " + b);
                                }))))))
        );

        dispatcher.register(literal("rtx")
            .then(benchmarkCommand())
            .then(cameraCommand())
            .then(shotCommand())
        );
    }


    private RemixRenderer renderer() {
        return runtime.renderer();
    }

    private void feedback(S source, Component message) {
        sourceAdapter.feedback(source, message);
    }

    private LiteralArgumentBuilder<S> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private <T> RequiredArgumentBuilder<S, T> argument(String name, ArgumentType<T> type) {
        return RequiredArgumentBuilder.argument(name, type);
    }

    private void toggleRtx() {
        MinecraftRuntime.setRtxEnabled(!MinecraftRuntime.isRtxEnabled());
    }

    private void setEntitiesEnabled(boolean value) {
        publishFeatures(
                value,
                MinecraftRuntime.isWorldEnabled(),
                MinecraftRuntime.isBlockEntitiesEnabled(),
                MinecraftRuntime.isParticlesEnabled());
    }

    private void setWorldEnabled(boolean value) {
        publishFeatures(
                MinecraftRuntime.isEntitiesEnabled(),
                value,
                MinecraftRuntime.isBlockEntitiesEnabled(),
                MinecraftRuntime.isParticlesEnabled());
    }

    private void setBlockEntitiesEnabled(boolean value) {
        publishFeatures(
                MinecraftRuntime.isEntitiesEnabled(),
                MinecraftRuntime.isWorldEnabled(),
                value,
                MinecraftRuntime.isParticlesEnabled());
    }

    private void setParticlesEnabled(boolean value) {
        publishFeatures(
                MinecraftRuntime.isEntitiesEnabled(),
                MinecraftRuntime.isWorldEnabled(),
                MinecraftRuntime.isBlockEntitiesEnabled(),
                value);
    }

    private void publishFeatures(boolean entities, boolean world, boolean blockEntities, boolean particles) {
        MinecraftRuntime.publishFeatureState(
                MinecraftRuntime.isRtxEnabled(), entities, world, MinecraftRuntime.isSkyEnabled(),
                blockEntities, particles, MinecraftRuntime.isFirstPersonSmokeEnabled());
    }

    private boolean isWorldActive() { return MinecraftRuntime.isRtxEnabled() && MinecraftRuntime.isWorldEnabled(); }
    private boolean isEntitiesActive() { return MinecraftRuntime.isRtxEnabled() && MinecraftRuntime.isEntitiesEnabled(); }
    private boolean isBlockEntitiesActive() { return MinecraftRuntime.isRtxEnabled() && MinecraftRuntime.isBlockEntitiesEnabled(); }
    private boolean isParticlesActive() { return MinecraftRuntime.isRtxEnabled() && MinecraftRuntime.isParticlesEnabled(); }
    private boolean isSkyActive() { return MinecraftRuntime.isRtxEnabled() && MinecraftRuntime.isSkyEnabled(); }

    private Component applyQualityPreset(String name) {
        Optional<QualityPreset> preset = QualityPreset.byId(name);
        if (preset.isEmpty()) {
            return Component.literal("No preset '" + name + "'. Available: " + String.join(", ", QualityPreset.ids()));
        }
        RemixRenderer renderer = renderer();
        if (renderer == null || renderer.getRemixApi() == null) {
            return Component.literal("Preset '" + name + "' did NOT apply: the Remix runtime is unavailable");
        }
        preset.get().writeTo(renderer.getRemixApi()::setConfigVariable);
        return Component.literal("Remix graphics preset '" + name + "' applied for this session");
    }

    /**
     * /rtx conf status: with a large authored catalog a flat dump can flood
     * chat, so the default view is the bounded summary from
     * ConfLayerDriver.formatStatusSummary; "all" prints every layer.
     */
    private int sendConfStatus(S source,
                               boolean all) {
        ConfLayerDriver driver = renderer() != null ? renderer().getConfLayerDriver() : null;
        if (driver == null) {
            feedback(source, Component.literal(
                    "Conf layers unavailable: the Remix renderer() is not initialized"));
            return 1;
        }
        if (!driver.isActive()) {
            feedback(source, Component.literal(
                    "Conf layers inactive (reload failed); run /rtx conf reload to retry"));
            return 1;
        }
        List<ConfLayerDriver.LayerStatus> layers = driver.snapshot();
        if (all) {
            int active = 0;
            int idle = 0;
            int unacquired = 0;
            for (ConfLayerDriver.LayerStatus layer : layers) {
                if (!layer.acquired()) {
                    unacquired++;
                } else if (layer.strength() > 0.0f) {
                    active++;
                } else {
                    idle++;
                }
                feedback(source, Component.literal("  " + layer.name() + " = "
                        + (layer.acquired() ? String.valueOf(layer.strength()) : "unacquired (see log)")));
            }
            feedback(source, Component.literal(String.format("Conf layers: %d active, %d idle, %d unacquired",
                    active, idle, unacquired)));
        } else {
            // Bounded default view (worst case 12 lines; healthy quiet = 1):
            // summary header, capped active rows, one line for unacquired.
            for (String line : ConfLayerDriver.formatStatusSummary(layers)) {
                feedback(source, Component.literal(line));
            }
        }
        return 1;
    }


    /** Queues an integrated-server state update; false means remote multiplayer. */
    private boolean saveZoneToCurrentWorld(ZoneDefinition zone,
                                                   Consumer<Boolean> completion) {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return false;
        }
        try {
            server.execute(() -> {
                boolean succeeded = true;
                try {
                    // Storing a definition is the one path that should create
                    // the save data if this world has none yet.
                    ZonePersistentState.getOrCreate(server).put(zone);
                } catch (RuntimeException e) {
                    succeeded = false;
                    LOGGER.error("Failed to save RTX Remix zone '{}'", zone.name(), e);
                }
                boolean result = succeeded;
                client.execute(() -> {
                    if (client.getSingleplayerServer() == server) {
                        completion.accept(result);
                    }
                });
            });
        } catch (RuntimeException e) {
            LOGGER.error("Failed to queue save for RTX Remix zone '{}'", zone.name(), e);
            completion.accept(false);
        }
        return true;
    }

    /** Queues deletion from the integrated-server state; false means remote multiplayer. */
    private boolean removeZoneFromCurrentWorld(String name,
                                                       Consumer<Boolean> completion) {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return false;
        }
        try {
            server.execute(() -> {
                boolean succeeded = true;
                try {
                    // Nothing saved means nothing to remove; do not create the
                    // save data just to delete from it.
                    ZonePersistentState state = ZonePersistentState.getIfPresent(server);
                    if (state != null) {
                        state.remove(name);
                    }
                } catch (RuntimeException e) {
                    succeeded = false;
                    LOGGER.error("Failed to remove saved RTX Remix zone '{}'", name, e);
                }
                boolean result = succeeded;
                client.execute(() -> {
                    if (client.getSingleplayerServer() == server) {
                        completion.accept(result);
                    }
                });
            });
        } catch (RuntimeException e) {
            LOGGER.error("Failed to queue removal of saved RTX Remix zone '{}'", name, e);
            completion.accept(false);
        }
        return true;
    }


    private LiteralArgumentBuilder<
            S> zoneCommand() {
        return literal("zone")
            // /rtx zone define <name> [--inner r] [--outer r] [--center x y z] ... - define or replace a
            // logic zone; radii are optional (bare define = inner 10 / outer 30, box --fade defaults to 20)
            .then(literal("define")
                .then(argument("spec",
                        StringArgumentType.greedyString())
                    .executes(context -> {
                        if (renderer() == null) {
                            feedback(context.getSource(), Component.literal("Remix renderer() not available"));
                            return 1;
                        }
                        ConfLayerDriver defineDriver = renderer().getConfLayerDriver();
                        if (defineDriver == null || !defineDriver.isActive()) {
                            feedback(context.getSource(), Component.literal(
                                "Conf-layer blending is inactive (layer acquire failure); "
                                + "check /rtx conf status and the log, or retry with /rtx conf reload"));
                            return 1;
                        }
                        LocalPlayer player = sourceAdapter.player(context.getSource());
                        if (player == null) {
                            feedback(context.getSource(), Component.literal("Player not available"));
                            return 1;
                        }
                        String spec = "rtx zone define "
                                + StringArgumentType.getString(context, "spec");
                        try {
                            ZoneDefinition zone = ZoneDefinition.parse(spec, player.getX(), player.getY(), player.getZ());
                            ZoneRegistry activeRegistry = renderer().getZoneRegistry();
                            activeRegistry.put(zone);
                            boolean savedToWorld = saveZoneToCurrentWorld(zone, succeeded -> {
                                // A join-time snapshot may have been queued just
                                // before this command. Reapply the newer mutation
                                // after its server-state task so stale hydration
                                // cannot restore the previous geometry.
                                if (renderer() != null && renderer().getZoneRegistry() == activeRegistry) {
                                    activeRegistry.put(zone);
                                }
                                feedback(context.getSource(), Component.literal(succeeded
                                    ? "Saved zone '" + zone.name() + "' -> " + zone.channel()
                                      + " to this single-player world"
                                    : "Zone '" + zone.name() + "' is active, but could not be saved; see the log"));
                            });
                            if (!savedToWorld) {
                                feedback(context.getSource(), Component.literal(
                                    "Zone '" + zone.name() + "' -> " + zone.channel()
                                    + " is active locally for this multiplayer session only; "
                                    + "it is not saved or synchronized"));
                            }
                            if (spec.contains("--center") || spec.contains("--box")) {
                                feedback(context.getSource(), Component.literal(
                                    "The same definition text also works as a command-block marker"));
                            } else {
                                feedback(context.getSource(), Component.literal(String.format(
                                    "Centered at your position (%.1f %.1f %.1f). "
                                    + "Add --center to the text before pasting into a command block, "
                                    + "or the block's position becomes the center.",
                                    player.getX(), player.getY(), player.getZ())));
                            }
                            if (zone.usedDefaultRadii()) {
                                feedback(context.getSource(), Component.literal(String.format(
                                    "Radii: inner %.1f, outer %.1f (defaults; pass --inner/--outer to override)",
                                    zone.innerRadius(), zone.outerRadius())));
                            } else if (zone.usedDefaultFade()) {
                                feedback(context.getSource(), Component.literal(String.format(
                                    "Fade: %.1f (default; pass --fade to override)", zone.fade())));
                            }
                            if (!defineDriver.hasAcquiredLayer(zone.channel())) {
                                feedback(context.getSource(), Component.literal(noConfHint(zone.channel())));
                            }
                        } catch (IllegalArgumentException e) {
                            feedback(context.getSource(), Component.literal("Invalid zone: " + e.getMessage()));
                        }
                        return 1;
                    })))
            .then(literal("list")
                .executes(context -> {
                    if (renderer() == null) {
                        feedback(context.getSource(), Component.literal("Remix renderer() not available"));
                        return 1;
                    }
                    Collection<ZoneDefinition> zones = renderer().getZoneRegistry().zones();
                    feedback(context.getSource(), Component.literal("Zones: " + zones.size()));
                    for (ZoneDefinition zone : zones) {
                        feedback(context.getSource(), Component.literal("  " + zone.name() + " -> " + zone.channel()));
                    }
                    return 1;
                }))
            .then(literal("remove")
                .then(argument("name",
                        StringArgumentType.word())
                    .executes(context -> {
                        if (renderer() == null) {
                            feedback(context.getSource(), Component.literal("Remix renderer() not available"));
                            return 1;
                        }
                        String name = StringArgumentType.getString(context, "name");
                        ZoneRegistry activeRegistry = renderer().getZoneRegistry();
                        boolean removed = activeRegistry.remove(name);
                        boolean removedFromWorld = removeZoneFromCurrentWorld(name, succeeded -> {
                            // See define above: a stale join snapshot must not
                            // resurrect a definition after this newer removal.
                            if (renderer() != null && renderer().getZoneRegistry() == activeRegistry) {
                                activeRegistry.remove(name);
                            }
                            String message;
                            if (!succeeded) {
                                message = "Zone '" + name
                                        + "' was removed locally, but the world save could not be updated; see the log";
                            } else if (removed) {
                                message = "Removed zone '" + name + "' from this single-player world";
                            } else {
                                message = "No active zone named '" + name
                                        + "'; any saved definition has been removed";
                            }
                            feedback(context.getSource(), Component.literal(message));
                        });
                        if (!removedFromWorld) {
                            feedback(context.getSource(), Component.literal(removed
                                    ? "Removed local session zone '" + name + "'"
                                    : "No local session zone named '" + name + "'"));
                        }
                        return 1;
                    })))
            .then(literal("rescan")
                .executes(context -> {
                    if (renderer() == null) {
                        feedback(context.getSource(), Component.literal("Remix renderer() not available"));
                        return 1;
                    }
                    MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
                    if (server == null) {
                        feedback(context.getSource(), Component.literal("Rescan needs the integrated server (single-player)"));
                        return 1;
                    }
                    feedback(context.getSource(), Component.literal("Rescanning loaded chunks for zone command blocks... (results in log)"));
                    MarkerBlockScanner.rescan(server, renderer().getZoneRegistry());
                    return 1;
                }))
            .then(literal("status")
                .executes(context -> {
                    if (renderer() == null) {
                        feedback(context.getSource(), Component.literal("Remix renderer() not available"));
                        return 1;
                    }
                    Collection<ZoneDefinition> zones = renderer().getZoneRegistry().zones();
                    feedback(context.getSource(), Component.literal("Zones: " + zones.size()));
                    LocalPlayer player = sourceAdapter.player(context.getSource());
                    ConfLayerDriver statusDriver = renderer().getConfLayerDriver();
                    for (ZoneDefinition zone : zones) {
                        List<String> notes = new ArrayList<>();
                        if (player != null) {
                            notes.add(String.format("weight %.3f here",
                                zone.weight(player.getX(), player.getY(), player.getZ())));
                        }
                        // Zone layers are discovered from conf file names; flag
                        // zones whose channel has no conf backing it yet.
                        if (statusDriver != null && statusDriver.isActive()
                                && !statusDriver.hasAcquiredLayer(zone.channel())) {
                            notes.add(noConfHint(zone.channel()));
                        }
                        String line = "  " + zone.name() + " -> " + zone.channel()
                            + (notes.isEmpty() ? "" : " (" + String.join("; ", notes) + ")");
                        feedback(context.getSource(), Component.literal(line));
                    }
                    feedback(context.getSource(), Component.literal("Conf layer strengths: /rtx conf status"));
                    return 1;
                }));
    }


    /** One-line pointer from a zone channel with no acquired layer to the conf that would create it. */
    private String noConfHint(String channel) {
        if (channel.startsWith(LogicChannels.ZONE_PREFIX)) {
            String stem = channel.substring(LogicChannels.ZONE_PREFIX.length());
            return "no conf - create zones/" + stem + ".conf under the mod's confs/ (shared archetype) "
                    + "or <world>/rtx-remix/confs/ (ships with this map), then /rtx conf reload";
        }
        return "no conf layer for channel " + channel;
    }

    private LiteralArgumentBuilder<
            S> sssCommand() {
        return literal("sss")
            .executes(context -> {
                BlockTextureManager textureManager = getTextureManager();
                if (!isCutoutSssAvailable(context.getSource(), textureManager)) return 0;
                sendCutoutSssStatus(context.getSource(), textureManager);
                return 1;
            })
            .then(literal("cutout")
                .executes(context -> {
                    BlockTextureManager textureManager = getTextureManager();
                    if (!isCutoutSssAvailable(context.getSource(), textureManager)) return 0;
                    sendCutoutSssStatus(context.getSource(), textureManager);
                    return 1;
                })
                .then(literal("transmissionDistance")
                    .executes(context -> {
                        BlockTextureManager textureManager = getTextureManager();
                        if (!isCutoutSssAvailable(context.getSource(), textureManager)) return 0;
                        feedback(context.getSource(), Component.literal("Cutout SSS transmission distance: "
                                + textureManager.getCutoutSssTransmissionDistance()));
                        return 1;
                    })
                    .then(argument("value",
                            FloatArgumentType.floatArg(0.0001f, 1000.0f))
                        .executes(context -> {
                            float value = FloatArgumentType.getFloat(context, "value");
                            return updateCutoutSssSetting(context.getSource(),
                                    textureManager -> textureManager.updateCutoutSssTransmissionDistance(value),
                                    "Cutout SSS transmission distance set to " + value);
                        })))
                .then(literal("anisotropy")
                    .executes(context -> {
                        BlockTextureManager textureManager = getTextureManager();
                        if (!isCutoutSssAvailable(context.getSource(), textureManager)) return 0;
                        feedback(context.getSource(), Component.literal("Cutout SSS anisotropy: "
                                + textureManager.getCutoutSssAnisotropy()));
                        return 1;
                    })
                    .then(argument("value",
                            FloatArgumentType.floatArg(-1.0f, 1.0f))
                        .executes(context -> {
                            float value = FloatArgumentType.getFloat(context, "value");
                            return updateCutoutSssSetting(context.getSource(),
                                    textureManager -> textureManager.updateCutoutSssAnisotropy(value),
                                    "Cutout SSS anisotropy set to " + value);
                        })))
                .then(literal("albedo")
                    .executes(context -> {
                        BlockTextureManager textureManager = getTextureManager();
                        if (!isCutoutSssAvailable(context.getSource(), textureManager)) return 0;
                        feedback(context.getSource(), Component.literal("Cutout SSS fallback albedo: "
                                + textureManager.getCutoutSssAlbedoR() + ", "
                                + textureManager.getCutoutSssAlbedoG() + ", "
                                + textureManager.getCutoutSssAlbedoB()));
                        return 1;
                    })
                    .then(argument("r",
                            FloatArgumentType.floatArg(0.0f, 1.0f))
                        .then(argument("g",
                                FloatArgumentType.floatArg(0.0f, 1.0f))
                            .then(argument("b",
                                    FloatArgumentType.floatArg(0.0f, 1.0f))
                                .executes(context -> {
                                    float r = FloatArgumentType.getFloat(context, "r");
                                    float g = FloatArgumentType.getFloat(context, "g");
                                    float b = FloatArgumentType.getFloat(context, "b");
                                    return updateCutoutSssSetting(context.getSource(),
                                            textureManager -> textureManager.updateCutoutSssAlbedo(r, g, b),
                                            "Cutout SSS fallback albedo set to " + r + ", " + g + ", " + b);
                                })))))
                .then(literal("albedoTexture")
                    .executes(context -> {
                        BlockTextureManager textureManager = getTextureManager();
                        if (!isCutoutSssAvailable(context.getSource(), textureManager)) return 0;
                        feedback(context.getSource(), Component.literal("Cutout SSS albedo texture: "
                                + textureManager.isCutoutSssAlbedoTextureEnabled()));
                        return 1;
                    })
                    .then(literal("on")
                        .executes(context -> updateCutoutSssSetting(context.getSource(),
                                textureManager -> textureManager.updateCutoutSssAlbedoTextureEnabled(true),
                                "Cutout SSS albedo texture enabled")))
                    .then(literal("off")
                        .executes(context -> updateCutoutSssSetting(context.getSource(),
                                textureManager -> textureManager.updateCutoutSssAlbedoTextureEnabled(false),
                                "Cutout SSS albedo texture disabled")))
                    .then(argument("enabled",
                            BoolArgumentType.bool())
                        .executes(context -> {
                            boolean enabled = BoolArgumentType.getBool(
                                    context, "enabled");
                            return updateCutoutSssSetting(context.getSource(),
                                    textureManager -> textureManager.updateCutoutSssAlbedoTextureEnabled(enabled),
                                    "Cutout SSS albedo texture " + (enabled ? "enabled" : "disabled"));
                        })))
                .then(literal("reset")
                    .executes(context -> updateCutoutSssSetting(context.getSource(),
                            BlockTextureManager::resetCutoutSss,
                            "Cutout SSS settings reset"))));
    }

    private boolean isCutoutSssAvailable(
            S source,
            BlockTextureManager textureManager) {
        if (textureManager == null || !textureManager.isInitialized()) {
            feedback(source, Component.literal("Cutout SSS materials not available"));
            return false;
        }
        return true;
    }

    private int updateCutoutSssSetting(
            S source,
            Function<BlockTextureManager, Boolean> updater,
            String successMessage) {
        BlockTextureManager textureManager = getTextureManager();
        if (!isCutoutSssAvailable(source, textureManager)) return 0;

        boolean hadActiveAtlasMaterial = textureManager.hasCutoutSssAtlasMaterial();
        if (hadActiveAtlasMaterial) {
            clearTerrainForMaterialRebuild();
        }

        boolean rebuilt = Boolean.TRUE.equals(updater.apply(textureManager));
        if (hadActiveAtlasMaterial && !rebuilt) {
            feedback(source, Component.literal("Failed to rebuild active cutout SSS material"));
            return 0;
        }
        if (rebuilt) {
            requestTerrainRebuildForMaterialChange();
        }

        feedback(source, Component.literal(successMessage + (rebuilt
                ? " (terrain rebuild requested)"
                : " (no active cutout SSS atlas material)")));
        return 1;
    }

    private int updateWaterSetting(
            S source,
            Function<BlockTextureManager, Boolean> updater,
            String successMessage) {
        BlockTextureManager textureManager = getTextureManager();
        if (textureManager == null) {
            feedback(source, Component.literal("Water material not available"));
            return 0;
        }

        clearTerrainForMaterialRebuild();

        boolean rebuilt = Boolean.TRUE.equals(updater.apply(textureManager));
        if (!rebuilt) {
            feedback(source, Component.literal("Failed to rebuild water material"));
            return 0;
        }

        requestTerrainRebuildForMaterialChange();
        feedback(source, Component.literal(successMessage + " (terrain rebuild requested)"));
        return 1;
    }

    private void clearTerrainForMaterialRebuild() {
        if (renderer() != null && renderer().getChunkManager() != null) {
            renderer().getChunkManager().clear();
        }
    }


    private void requestTerrainRebuildForMaterialChange() {
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.levelRenderer != null) {
            LongSet built = MinecraftRuntime.builtSections(client);
            client.levelRenderer.allChanged();
            MinecraftRuntime.recaptureTerrainForRemix(client, built);
        }
    }


    private LiteralArgumentBuilder<
            S> benchmarkCommand() {
        return literal("benchmark")
            .executes(context -> {
                if (renderer() != null && renderer().getBenchmarkController() != null) {
                    feedback(context.getSource(),
                            Component.literal(renderer().getBenchmarkController().start()));
                }
                return 1;
            })
            .then(literal("stop")
                .executes(context -> {
                    if (renderer() != null && renderer().getBenchmarkController() != null) {
                        renderer().getBenchmarkController().stop(renderer().getPerfTimer());
                        feedback(context.getSource(), Component.literal("Benchmark stopped"));
                    }
                    return 1;
                }))
            .then(literal("status")
                .executes(context -> {
                    if (renderer() != null && renderer().getBenchmarkController() != null) {
                        feedback(context.getSource(),
                            Component.literal("Benchmark: " + renderer().getBenchmarkController().getStatus()
                                + " | target " + renderer().getBenchmarkController().getTargetStatus()));
                    }
                    return 1;
                }));
    }

    LiteralArgumentBuilder<
            S> cameraCommand() {
        return literal("camera")
            .executes(context -> cameraStatus(context.getSource()))
            .then(literal("record")
                .then(argument("name",
                        StringArgumentType.word())
                    .executes(context -> {
                        String name = StringArgumentType.getString(context, "name");
                        CameraController controller = getCameraControllerForCommand(context.getSource());
                        if (controller == null) return 0;
                        feedback(context.getSource(), Component.literal(controller.arm(name)));
                        return 1;
                    })))
            .then(literal("play")
                .then(argument("name",
                        StringArgumentType.word())
                    .executes(context -> playCamera(
                            context.getSource(),
                            StringArgumentType.getString(context, "name"),
                            CameraPlaybackMode.TIME
                    ))
                    .then(literal("time")
                        .executes(context -> playCamera(
                                context.getSource(),
                                StringArgumentType.getString(context, "name"),
                                CameraPlaybackMode.TIME
                        )))
                    .then(literal("fixed-60")
                        .executes(context -> playCamera(
                                context.getSource(),
                                StringArgumentType.getString(context, "name"),
                                CameraPlaybackMode.FIXED_60
                        )))))
            .then(literal("seek")
                .then(argument("seconds",
                        DoubleArgumentType.doubleArg(0.0))
                    .executes(context -> {
                        double seconds = DoubleArgumentType.getDouble(
                                context, "seconds");
                        CameraController controller = getCameraControllerForCommand(context.getSource());
                        if (controller == null) return 0;
                        feedback(context.getSource(), Component.literal(
                                controller.seek(Math.round(seconds * 1_000.0))));
                        return 1;
                    }))
                .then(argument("name",
                        StringArgumentType.word())
                    .then(argument("seconds",
                            DoubleArgumentType.doubleArg(0.0))
                        .executes(context -> {
                            String name = StringArgumentType.getString(
                                    context, "name");
                            double seconds = DoubleArgumentType.getDouble(
                                    context, "seconds");
                            CameraController controller = getCameraControllerForCommand(context.getSource());
                            if (controller == null) return 0;
                            feedback(context.getSource(), Component.literal(
                                    controller.seek(name, Math.round(seconds * 1_000.0))));
                            return 1;
                        }))))
            .then(literal("pause")
                .executes(context -> {
                    CameraController controller = getCameraControllerForCommand(context.getSource());
                    if (controller == null) return 0;
                    feedback(context.getSource(), Component.literal(controller.pause()));
                    return 1;
                }))
            .then(literal("resume")
                .executes(context -> {
                    CameraController controller = getCameraControllerForCommand(context.getSource());
                    if (controller == null) return 0;
                    feedback(context.getSource(), Component.literal(controller.resume()));
                    return 1;
                }))
            .then(literal("stop")
                .executes(context -> {
                    CameraController controller = getCameraControllerForCommand(context.getSource());
                    if (controller == null) return 0;
                    feedback(context.getSource(), Component.literal(controller.stop()));
                    return 1;
                }))
            .then(literal("status")
                .executes(context -> cameraStatus(context.getSource())))
            .then(literal("time")
                .then(literal("day")
                    .executes(context -> setCameraTime(context.getSource(), 1000)))
                .then(literal("noon")
                    .executes(context -> setCameraTime(context.getSource(), 6000)))
                .then(literal("sunset")
                    .executes(context -> setCameraTime(context.getSource(), 12000)))
                .then(literal("night")
                    .executes(context -> setCameraTime(context.getSource(), 13000)))
                .then(literal("midnight")
                    .executes(context -> setCameraTime(context.getSource(), 18000)))
                .then(argument("ticks",
                        LongArgumentType.longArg(0, 23999))
                    .executes(context -> {
                        long ticks = LongArgumentType.getLong(context, "ticks");
                        return setCameraTime(context.getSource(), ticks);
                    })));
    }

    /**
     * Still shots for documentation screenshots. A mark is a pose plus the
     * scene state the pose alone cannot carry, taken at the instant the command
     * runs; the harness captures the set headlessly later.
     */
    LiteralArgumentBuilder<
            S> shotCommand() {
        return literal("shot")
            .executes(context -> listShots(context.getSource()))
            .then(literal("mark")
                .then(argument("name",
                        StringArgumentType.word())
                    .executes(context -> markShot(
                            context.getSource(),
                            StringArgumentType.getString(context, "name")))))
            .then(literal("list")
                .executes(context -> listShots(context.getSource())))
            .then(literal("remove")
                .then(argument("name",
                        StringArgumentType.word())
                    .executes(context -> {
                        CameraController controller = getCameraControllerForCommand(context.getSource());
                        if (controller == null) return 0;
                        feedback(context.getSource(), Component.literal(controller.removeStillShot(
                                DOCS_SHOT_SET, StringArgumentType.getString(context, "name"))));
                        return 1;
                    })));
    }

    private int markShot(S source, String name) {
        CameraController controller = getCameraControllerForCommand(source);
        if (controller == null) return 0;
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            feedback(source, Component.literal("No world loaded."));
            return 0;
        }
        CameraController.StillShotScene scene = new CameraController.StillShotScene(
                StillShot.Weather.of(client.level.isRaining(), client.level.isThundering()),
                MinecraftRuntime.isRtxEnabled(),
                renderer().getCloudRenderer() != null && renderer().getCloudRenderer().isProceduralClouds());
        feedback(source, Component.literal(controller.markStillShot(DOCS_SHOT_SET, name, scene)));
        return 1;
    }

    private int listShots(S source) {
        CameraController controller = getCameraControllerForCommand(source);
        if (controller == null) return 0;
        for (String line : controller.describeStillShots(DOCS_SHOT_SET).split("\n")) {
            feedback(source, Component.literal(line));
        }
        return 1;
    }

    private int cameraStatus(
            S source) {
        CameraController controller = getCameraControllerForCommand(source);
        if (controller == null) return 0;
        feedback(source, Component.literal(controller.status()));
        return 1;
    }

    private int playCamera(
            S source,
            String name,
            CameraPlaybackMode mode) {
        CameraController controller = getCameraControllerForCommand(source);
        if (controller == null) return 0;
        feedback(source, Component.literal(controller.play(name, mode)));
        return 1;
    }

    private int setCameraTime(
            S source,
            long timeOfDay) {
        CameraController controller = getCameraControllerForCommand(source);
        if (controller == null) return 0;
        feedback(source, Component.literal(controller.setTimeOfDay(timeOfDay)));
        return 1;
    }

    private CameraController getCameraControllerForCommand(
            S source) {
        if (renderer() == null || !renderer().isInitialized() || renderer().getCameraController() == null) {
            feedback(source, Component.literal("Camera controller not available: RTX Remix renderer() is not initialized."));
            return null;
        }
        if (renderer().getBenchmarkController() != null && renderer().getBenchmarkController().isRunning()) {
            feedback(source, Component.literal("Camera controls are unavailable while a benchmark is running."));
            return null;
        }
        return renderer().getCameraController();
    }

    private BlockTextureManager getTextureManager() {
        if (renderer() == null || !renderer().isInitialized()) {
            return null;
        }
        return renderer().getTextureManager();
    }

    private void sendWaterStatus(S source,
                                 BlockTextureManager textureManager) {
        feedback(source, Component.literal("Water material:"));
        feedback(source, Component.literal("  IOR: " + textureManager.getWaterIor()));
        feedback(source, Component.literal("  Transmittance color: "
                + textureManager.getWaterTransmittanceR() + ", "
                + textureManager.getWaterTransmittanceG() + ", "
                + textureManager.getWaterTransmittanceB()));
        feedback(source, Component.literal(
                "  Transmittance distance: " + textureManager.getWaterTransmittanceDistance()));
    }

    private void sendCutoutSssStatus(
            S source,
            BlockTextureManager textureManager) {
        feedback(source, Component.literal("Cutout SSS material:"));
        feedback(source, Component.literal(
                "  Active atlas material: " + textureManager.hasCutoutSssAtlasMaterial()));
        feedback(source, Component.literal(
                "  Transmission distance: " + textureManager.getCutoutSssTransmissionDistance()));
        feedback(source, Component.literal("  Anisotropy: " + textureManager.getCutoutSssAnisotropy()));
        feedback(source, Component.literal("  Fallback albedo: "
                + textureManager.getCutoutSssAlbedoR() + ", "
                + textureManager.getCutoutSssAlbedoG() + ", "
                + textureManager.getCutoutSssAlbedoB()));
        feedback(source, Component.literal(
                "  Albedo texture: " + textureManager.isCutoutSssAlbedoTextureEnabled()));
    }
}
