/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.vertex.VertexFormatElement;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * Reads the layer-level facts the capture path needs from a {@link RenderType}.
 *
 * <p>A renderer requests its {@code VertexConsumer} by layer,
 * {@code provider.getBuffer(RenderLayer.getEntityCutout(textureId))}, so the
 * layer is where geometry and material meet and the one place material
 * identity is read. Its {@link RenderStateShard} state, reached through the access
 * widener, gives the texture the layer is submitted under, if any, the
 * {@link SurfaceMode} Remix shades with, and whether the geometry is streamed
 * afresh every frame; its draw mode gives the {@link Topology} and whether an
 * untextured layer carries geometry worth submitting at all.
 */
final class RenderLayerUtils {

    /** Material behavior encoded by Minecraft's actual render phases, and clear glass. */
    enum SurfaceMode {
        OPAQUE,
        CUTOUT,
        TRANSLUCENT,
        EMISSIVE,
        /**
         * Clear glass on the block atlas: blended, through the refracting material
         * terrain gives placed clear glass. No render phase encodes it, since vanilla
         * draws clear glass as a cutout; {@code RemixEntitySubmitter.atlasSurfaceMode}
         * assigns it to geometry from a clear-glass block item.
         */
        GLASS
    }

    /** How a layer's captured vertex stream turns into Remix triangles. */
    enum Topology {
        QUADS,
        TRIANGLE_STRIP
    }

    /**
     * Material identity for layers that draw without a texture. They have no
     * {@link ResourceLocation} of their own, but the submitter keys materials and mesh
     * pools by one, so they share this.
     */
    static final ResourceLocation UNTEXTURED_LAYER_ID =
            ResourceLocation.fromNamespaceAndPath("rtxremix", "untextured_entity_layer");

    private RenderLayerUtils() {}

    /**
     * Cache of RenderLayer -> capturable texture. Most RenderLayer instances are reused,
     * but weak keys avoid retaining dynamic layers created by mods or skin features.
     */
    private static final Map<RenderType, Optional<ResourceLocation>> capturableTextureCache =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Cache of RenderLayer -> streamed-per-frame verdict, weak-keyed like {@link #capturableTextureCache}. */
    private static final Map<RenderType, Boolean> streamedPerFrameCache =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Returns the texture a captured layer is submitted under, or empty when Remix has
     * nothing to draw for the layer. Results are cached by layer identity.
     *
     * <p>Empty covers two cases. Untextured layers (shadow, debug, lines) carry no
     * material identity at all. Layers that pass the depth test unconditionally, the
     * see-through text of a name tag, are drawn over the whole scene in vanilla; no
     * placement in a ray-traced scene reproduces that, and vanilla draws the
     * depth-tested copy of the same text beside them, so their quads would only double
     * it.
     *
     * @param layer the render layer (typically from an entity renderer)
     * @return the texture identifier to capture under, or empty to skip the layer
     */
    static Optional<ResourceLocation> capturableTexture(RenderType layer) {
        return capturableTextureCache.computeIfAbsent(layer, RenderLayerUtils::capturableTextureUncached);
    }

    private static Optional<ResourceLocation> capturableTextureUncached(RenderType layer) {
        if (layer instanceof RenderType.CompositeRenderType multiPhase
                && multiPhase.state().depthTestState == RenderStateShard.NO_DEPTH_TEST) {
            return Optional.empty();
        }
        return extractTexture(layer);
    }

    private static Optional<ResourceLocation> extractTexture(RenderType layer) {
        if (layer instanceof RenderType.CompositeRenderType multiPhase) {
            RenderType.CompositeState phases = multiPhase.state();
            RenderStateShard.EmptyTextureStateShard textureBase = phases.textureState;

            if (textureBase instanceof RenderStateShard.TextureStateShard texture) {
                return texture.cutoutTexture();
            }
        }
        return Optional.empty();
    }

    /**
     * How to read the layer's vertices, or empty when it draws primitives that
     * do not become triangles, such as lines.
     */
    static Optional<Topology> topology(RenderType layer) {
        return switch (layer.mode()) {
            case QUADS -> Optional.of(Topology.QUADS);
            case TRIANGLE_STRIP -> Optional.of(Topology.TRIANGLE_STRIP);
            default -> Optional.empty();
        };
    }

    /**
     * Whether a layer that draws without a texture still carries geometry worth
     * submitting to Remix. In 1.21.1 the lead between a mob and its holder is the
     * only such layer.
     *
     * <p>Two conditions, both about what the layer can actually shade with.
     * Vertex colour is the only surface detail such a layer has, so a layer
     * without it carries nothing to submit; that excludes the water mask, which
     * exists to write depth. And of the untextured layers that do have colour,
     * the triangle strips are world geometry while the quads are screen
     * furniture such as the nameplate background, which should not become
     * scene geometry that lights and casts shadows.
     */
    static boolean isCapturableUntexturedLayer(RenderType layer) {
        // Only layers with no texture at all qualify: a textured layer that
        // capturableTexture declined must not re-enter capture through here.
        return extractTexture(layer).isEmpty()
                && topology(layer).filter(t -> t == Topology.TRIANGLE_STRIP).isPresent()
                && layer.format().getElements().contains(VertexFormatElement.COLOR);
    }

    /**
     * Whether the layer's vertex content differs every frame, so no mesh built from
     * it can be shared across frames and none should be pooled.
     *
     * <p>Vanilla streams two kinds of geometry through an entity provider whose
     * vertices never repeat: triangle strips, which it computes from live positions
     * (the lead between a mob and its holder), and the beacon beam, whose texture
     * coordinate scrolls with world time. Everything else it draws is rigid: a
     * model part or baked quad whose pose changes while its local vertices do not.
     */
    static boolean isStreamedPerFrame(RenderType layer) {
        return streamedPerFrameCache.computeIfAbsent(layer, RenderLayerUtils::isStreamedPerFrameUncached);
    }

    private static boolean isStreamedPerFrameUncached(RenderType layer) {
        boolean beaconBeamProgram = layer instanceof RenderType.CompositeRenderType multiPhase
                && multiPhase.state().shaderState == RenderStateShard.RENDERTYPE_BEACON_BEAM_SHADER;
        return classifyStreamedPerFrame(topology(layer).orElse(Topology.QUADS), beaconBeamProgram);
    }

    static boolean classifyStreamedPerFrame(Topology topology, boolean beaconBeamProgram) {
        return topology == Topology.TRIANGLE_STRIP || beaconBeamProgram;
    }

    /**
     * Converts Minecraft's render-phase state into the material behavior that
     * Remix needs. This deliberately inspects phases rather than render-layer
     * names so mod-created layers using the standard phases behave correctly.
     *
     * <p>The block-breaking crack multiplies the surface under it, a blend Remix
     * has no material for. Its texels are either fully opaque grey or fully
     * transparent, so alpha blending them over the surface is the closest surface
     * behaviour and it is classified as translucent.
     */
    static SurfaceMode surfaceMode(RenderType layer) {
        if (!(layer instanceof RenderType.CompositeRenderType multiPhase)) {
            return SurfaceMode.CUTOUT;
        }

        RenderType.CompositeState phases = multiPhase.state();
        return classifySurfaceMode(
                phases.shaderState == RenderStateShard.RENDERTYPE_EYES_SHADER
                        || phases.shaderState == RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_EMISSIVE_SHADER,
                phases.transparencyState == RenderStateShard.ADDITIVE_TRANSPARENCY,
                phases.transparencyState == RenderStateShard.TRANSLUCENT_TRANSPARENCY
                        || phases.transparencyState == RenderStateShard.CRUMBLING_TRANSPARENCY,
                phases.shaderState == RenderStateShard.RENDERTYPE_ENTITY_SOLID_SHADER,
                phases.shaderState == RenderStateShard.RENDERTYPE_TEXT_SHADER
                        || phases.shaderState == RenderStateShard.RENDERTYPE_TEXT_INTENSITY_SHADER);
    }

    /**
     * The text programs blend in vanilla only so glyph edges antialias; what they draw,
     * glyphs, map pixels and map icons, is coverage whose texels are either there or
     * not. Cutout keeps those quads out of the blended path, so text and maps are opaque
     * hits that cast shadows and appear in reflections instead of ghosted decals.
     */
    static SurfaceMode classifySurfaceMode(boolean emissiveProgram, boolean additiveTransparency,
                                           boolean translucentTransparency, boolean solidProgram,
                                           boolean textProgram) {
        if (emissiveProgram || additiveTransparency) {
            return SurfaceMode.EMISSIVE;
        }
        if (textProgram) {
            return SurfaceMode.CUTOUT;
        }
        if (translucentTransparency) {
            return SurfaceMode.TRANSLUCENT;
        }
        if (solidProgram) {
            return SurfaceMode.OPAQUE;
        }
        return SurfaceMode.CUTOUT;
    }
}
