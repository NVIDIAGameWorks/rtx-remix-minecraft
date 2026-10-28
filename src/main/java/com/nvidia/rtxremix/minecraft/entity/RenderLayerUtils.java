/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexFormatElement;
import net.minecraft.util.Identifier;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * Reads the layer-level facts the capture path needs from a {@link RenderLayer}.
 *
 * <p>A renderer requests its {@code VertexConsumer} by layer,
 * {@code provider.getBuffer(RenderLayer.getEntityCutout(textureId))}, so the
 * layer is where geometry and material meet and the one place material
 * identity is read. Its {@link RenderPhase} state, reached through the access
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
     * {@link Identifier} of their own, but the submitter keys materials and mesh
     * pools by one, so they share this.
     */
    static final Identifier UNTEXTURED_LAYER_ID =
            Identifier.of("rtxremix", "untextured_entity_layer");

    private RenderLayerUtils() {}

    /**
     * Cache of RenderLayer -> capturable texture. Most RenderLayer instances are reused,
     * but weak keys avoid retaining dynamic layers created by mods or skin features.
     */
    private static final Map<RenderLayer, Optional<Identifier>> capturableTextureCache =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Cache of RenderLayer -> streamed-per-frame verdict, weak-keyed like {@link #capturableTextureCache}. */
    private static final Map<RenderLayer, Boolean> streamedPerFrameCache =
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
    static Optional<Identifier> capturableTexture(RenderLayer layer) {
        return capturableTextureCache.computeIfAbsent(layer, RenderLayerUtils::capturableTextureUncached);
    }

    private static Optional<Identifier> capturableTextureUncached(RenderLayer layer) {
        if (layer instanceof RenderLayer.MultiPhase multiPhase
                && multiPhase.getPhases().depthTest == RenderPhase.ALWAYS_DEPTH_TEST) {
            return Optional.empty();
        }
        return extractTexture(layer);
    }

    private static Optional<Identifier> extractTexture(RenderLayer layer) {
        if (layer instanceof RenderLayer.MultiPhase multiPhase) {
            RenderLayer.MultiPhaseParameters phases = multiPhase.getPhases();
            // The end portal and gateway programs combine two textures in screen space
            // and their vertices carry position only; they draw with a baked texture.
            if (phases.program == RenderPhase.END_PORTAL_PROGRAM) {
                return EndPortalSurface.PORTAL_TEXTURE;
            }
            if (phases.program == RenderPhase.END_GATEWAY_PROGRAM) {
                return EndPortalSurface.GATEWAY_TEXTURE;
            }
            RenderPhase.TextureBase textureBase = phases.texture;

            if (textureBase instanceof RenderPhase.Texture texture) {
                return texture.getId();
            }
        }
        return Optional.empty();
    }

    /**
     * How to read the layer's vertices, or empty when it draws primitives that
     * do not become triangles, such as lines.
     */
    static Optional<Topology> topology(RenderLayer layer) {
        return switch (layer.getDrawMode()) {
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
    static boolean isCapturableUntexturedLayer(RenderLayer layer) {
        // Only layers with no texture at all qualify: a textured layer that
        // capturableTexture declined must not re-enter capture through here.
        return extractTexture(layer).isEmpty()
                && topology(layer).filter(t -> t == Topology.TRIANGLE_STRIP).isPresent()
                && layer.getVertexFormat().getElements().contains(VertexFormatElement.COLOR);
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
    static boolean isStreamedPerFrame(RenderLayer layer) {
        return streamedPerFrameCache.computeIfAbsent(layer, RenderLayerUtils::isStreamedPerFrameUncached);
    }

    private static boolean isStreamedPerFrameUncached(RenderLayer layer) {
        boolean beaconBeamProgram = layer instanceof RenderLayer.MultiPhase multiPhase
                && multiPhase.getPhases().program == RenderPhase.BEACON_BEAM_PROGRAM;
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
    static SurfaceMode surfaceMode(RenderLayer layer) {
        if (!(layer instanceof RenderLayer.MultiPhase multiPhase)) {
            return SurfaceMode.CUTOUT;
        }

        RenderLayer.MultiPhaseParameters phases = multiPhase.getPhases();
        return classifySurfaceMode(
                phases.program == RenderPhase.EYES_PROGRAM
                        || phases.program == RenderPhase.ENTITY_TRANSLUCENT_EMISSIVE_PROGRAM,
                phases.transparency == RenderPhase.ADDITIVE_TRANSPARENCY,
                phases.transparency == RenderPhase.TRANSLUCENT_TRANSPARENCY
                        || phases.transparency == RenderPhase.CRUMBLING_TRANSPARENCY,
                phases.program == RenderPhase.ENTITY_SOLID_PROGRAM,
                phases.program == RenderPhase.TEXT_PROGRAM
                        || phases.program == RenderPhase.TEXT_INTENSITY_PROGRAM);
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
