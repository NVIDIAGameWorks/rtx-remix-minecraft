/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.joml.Matrix4f;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

/**
 * A {@link VertexConsumer} that captures vertex data for RTX Remix.
 *
 * <p>Every consumer is fed inside a Remix capture pass, so the geometry it stores is
 * local to the pose that produced it and the pose itself is recorded as a bone
 * through {@link EntityRenderContext}. The matrix-aware paths
 * ({@code vertex(MatrixStack.Entry, ...)}, {@code vertex(Matrix4f, ...)} and
 * {@code quad(...)} for {@link BakedQuad} input) register the pose and keep the
 * untransformed coordinates, and {@code CuboidMixin} hands over whole cuboids by
 * identity through {@link #acceptCuboid}. Rigid instancing needs exactly that: a
 * mesh that never changes and a transform that does.
 *
 * <p>Uses pre-allocated flat arrays (struct-of-arrays) to avoid per-vertex object
 * allocations. Maximum capacity is {@link #MAX_VERTICES} per consumer.
 */
public class RemixCapturingConsumer implements VertexConsumer {

    /**
     * Maximum vertices per consumer. Minecraft entities rarely exceed ~1500 vertices; the
     * ceiling is set by glowing sign text, whose eight outline copies of four full lines
     * on both faces approach 4000.
     */
    static final int MAX_VERTICES = 4096;

    private ResourceLocation textureId;
    private RenderLayerUtils.Topology topology = RenderLayerUtils.Topology.QUADS;
    /**
     * Whether the layer streams its vertex content afresh every frame. Resolved with
     * the layer and carried to the CapturedLayer; the submitter bypasses the mesh pool
     * on it.
     */
    private boolean transientGeometry;

    // ---- Struct-of-arrays vertex storage (pre-allocated, zero GC) ----
    private int vertexCount = 0;
    private final float[] px = new float[MAX_VERTICES];
    private final float[] py = new float[MAX_VERTICES];
    private final float[] pz = new float[MAX_VERTICES];
    private final float[] nx = new float[MAX_VERTICES];
    private final float[] ny = new float[MAX_VERTICES];
    private final float[] nz = new float[MAX_VERTICES];
    private final float[] tu = new float[MAX_VERTICES];
    private final float[] tv = new float[MAX_VERTICES];
    /**
     * Packed ARGB (0xAARRGGBB). Remix reads {@code HardcodedVertex.color} as
     * {@code VK_FORMAT_B8G8R8A8_UNORM} from a little-endian buffer, so any other
     * channel order reaches the renderer with red and blue swapped.
     */
    private final int[] colors = new int[MAX_VERTICES];
    private final int[] boneIndices = new int[MAX_VERTICES];
    private final int[] texcoordBoundsMin = new int[MAX_VERTICES];
    private final int[] texcoordBoundsMax = new int[MAX_VERTICES];
    private final boolean[] generatedNormals = new boolean[MAX_VERTICES];

    // ---- Per-vertex accumulation state ----
    private float vx, vy, vz;
    private float vnx, vny, vnz;
    private float vu, vv;
    private int vColor = 0xFFFFFFFF;
    private int vOverlay = OverlayTexture.NO_OVERLAY;
    private boolean vertexOpen = false;
    private boolean explicitNormal = false;
    private int currentVertexBoneIndex = -1;

    private final Matrix4f lastPose = new Matrix4f();
    private boolean lastPoseValid = false;

    // ---- Rigid part records (recording consumers only) ----
    // A part is a run of consecutive vertices sharing one pivot: whole quads in a
    // quad layer, any run in a strip layer. Parts are recorded as the geometry
    // closes so the geometry hash folds while the vertices are still in L1.
    // MAX_PARTS covers every quad being its own part. A strip layer could exceed
    // it by changing pivot every few vertices; parts past the cap are dropped.
    private static final int MAX_PARTS = MAX_VERTICES / 4;
    private static final int VERTICES_PER_QUAD = 4;
    /** partStart for a cuboid part: its geometry lives in the Cuboid, not here. */
    static final int NO_VERTICES = -1;
    /** The two planes of ItemModelGenerator's card, in sixteenths: 7.5 and 8.5. */
    private static final float CARD_FRONT_Z = 7.5f / 16f;
    private static final float CARD_BACK_Z = 8.5f / 16f;
    /**
     * Rectangles per face beyond which a card face keeps vanilla's single quad: a
     * layer holds {@link #MAX_VERTICES} vertices, and a sprite this busy is dithered
     * art whose rectangles would crowd out the slivers and everything else in the hand.
     */
    static final int MAX_CARD_FACE_RECTS = 192;
    private static final int[] BUSY_CARD = new int[0];
    /**
     * The opaque-texel rectangles a full card face is rebuilt from, per sprite;
     * {@link #BUSY_CARD} marks a sprite past the cap. Captures run on the render
     * thread only, so the map is unsynchronised, and a re-stitch that discards a
     * sprite's contents drops its entry with it.
     */
    private static final Map<SpriteContents, int[]> CARD_FACE_RECTS = new WeakHashMap<>();
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    // Schema constants keep the static digest, the per-slot fold, and the part
    // hash from colliding with each other for degenerate inputs.
    private static final long STATIC_QUAD_SCHEMA = 0x6a09e667f3bcc909L;
    private static final long STATIC_VERTEX_0 = 0xbb67ae8584caa73bL;
    private static final long STATIC_VERTEX_1 = 0x3c6ef372fe94f82bL;
    private static final long STATIC_VERTEX_2 = 0xa54ff53a5f1d36f1L;
    private static final long STATIC_VERTEX_3 = 0x510e527fade682d1L;
    private static final long QUAD_SLOT_0 = 0x9b05688c2b3e6c1fL;
    private static final long QUAD_SLOT_1 = 0x1f83d9abfb41bd6bL;
    private static final long QUAD_SLOT_2 = 0x5be0cd19137e2179L;
    private static final long QUAD_SLOT_3 = 0xcbbb9d5dc1059ed8L;
    private static final long PART_HASH_SCHEMA = 0x629a292a367cd507L;

    // The BakedQuad currently streaming through quad(), so its bake-time digest
    // can be cached on the quad itself instead of recomputed every frame.
    private final SingletonCuboidIterator singletonCuboidIterator = new SingletonCuboidIterator();
    private int pendingBakedQuadStart = NO_VERTICES;
    private BakedQuadStaticGeometryCache pendingBakedQuadCache;
    private int[] pendingBakedQuadVertexData;

    private final int[] partStart = new int[MAX_PARTS];
    private final int[] partVertexCount = new int[MAX_PARTS];
    private final int[] partBone = new int[MAX_PARTS];
    private final long[] partHash = new long[MAX_PARTS];
    private int parts = 0;
    private boolean partOpen = false;
    // Four independent lanes, assigned by vertex-within-quad, so folding a quad
    // is four short chains rather than one 44-deep serial multiply.
    private long lane0, lane1, lane2, lane3;
    private int layerIndex = 0;
    // Tagged onto each part so submit can key by cuboid identity. Null for
    // parts that came from baked quads or direct matrix vertices.
    private final ModelPart.Polygon[][] partSides = new ModelPart.Polygon[MAX_PARTS][];
    /**
     * The atlas sprite each cuboid part was painted with; null for a cuboid drawn
     * without {@code SpriteTexturedVertexConsumer} and for every non-cuboid part.
     * Keyed alongside {@code partSides}: one cuboid is baked once and painted with
     * many sprites, and the rebuilt mesh bakes the sprite's UV rectangle.
     */
    private final TextureAtlasSprite[] partSprite = new TextureAtlasSprite[MAX_PARTS];
    private final int[] partTint = new int[MAX_PARTS];
    /**
     * The terrain layer of the block item each part came from, or
     * {@link EntityRenderContext#NO_BLOCK_ITEM}; see {@link EntityRenderContext#getBlockItemLayer}.
     * Per part rather than per layer because two items rendered into one layer,
     * a held pane of glass and a held torch, must not share the answer.
     */
    private final int[] partBlockItemLayer = new int[MAX_PARTS];
    /**
     * The first vertex of a full item-card face in each part, or {@link #NO_VERTICES}.
     * A full face survives only when its sprite is too busy to rebuild, see
     * {@link #MAX_CARD_FACE_RECTS}; its silhouette is then the texture's alpha rather
     * than the geometry, so the shell offset may thicken such a part but must not move
     * it in its plane; see {@link RemixEntitySubmitter#shellOffsets}.
     */
    private final int[] partCardFace = new int[MAX_PARTS];
    /**
     * Whether closing quads and strip runs are recorded as rigid parts. Every
     * provider-owned consumer records; the submitter's rebuild scratch only needs
     * the vertices it expands a cuboid into.
     */
    private boolean recordParts;

    public RemixCapturingConsumer(ResourceLocation textureId, boolean recordParts) {
        this(textureId, recordParts, RenderLayerUtils.Topology.QUADS, false);
    }

    public RemixCapturingConsumer(ResourceLocation textureId, boolean recordParts,
                                  RenderLayerUtils.Topology topology,
                                  boolean transientGeometry) {
        this.recordParts = recordParts;
        this.textureId = textureId;
        this.topology = topology;
        this.transientGeometry = transientGeometry;
    }

    /** Reinitialize a pooled consumer for a new layer. Must be called after clear(). */
    public void reinit(ResourceLocation textureId, boolean recordParts,
                       RenderLayerUtils.Topology topology,
                       boolean transientGeometry) {
        this.recordParts = recordParts;
        this.textureId = textureId;
        this.topology = topology;
        this.transientGeometry = transientGeometry;
    }

    // ---- Bone detection ----

    /**
     * Registers {@code currentPose} as a new bone when it differs from the last pose
     * this consumer saw, and returns the bone index the next vertex belongs to.
     */
    private int detectBoneChange(Matrix4f currentPose) {
        EntityRenderContext ctx = EntityRenderContext.get();
        if (!lastPoseValid || !lastPose.equals(currentPose)) {
            ctx.pushBone(currentPose);
            lastPose.set(currentPose);
            lastPoseValid = true;
        }
        return ctx.currentBoneIndex();
    }

    /**
     * Registers a cuboid's pivot and keeps this consumer's own bone tracking in step.
     *
     * <p>The pivot must be pushed through here rather than straight into
     * {@link EntityRenderContext}: {@link #detectBoneChange} skips its own push when a
     * later vertex's pose equals {@link #lastPose}, so a pivot this consumer never saw
     * would let unrelated quads inherit the cuboid's bone index and merge into its
     * rigid part. One bone index belongs to one source part.
     *
     * @return the bone index assigned to this cuboid
     */
    public int beginCuboid(Matrix4f pose) {
        int boneIndex = EntityRenderContext.get().pushBone(pose);
        lastPose.set(pose);
        lastPoseValid = true;
        return boneIndex;
    }

    // ---- VertexConsumer: position ----

    /**
     * Captures a posed vertex as local geometry plus a bone: the position is stored
     * untransformed and the entry's pose becomes the part's instance transform.
     */
    @Override
    public VertexConsumer addVertex(PoseStack.Pose matrix, float x, float y, float z) {
        beginVertex(detectBoneChange(matrix.pose()));
        vx = x; vy = y; vz = z;
        return this;
    }

    /**
     * Captures direct matrix-based quads, most notably held-map backgrounds,
     * map pixels, and map decorations, as local geometry plus a bone transform.
     *
     * <p>The vanilla default implementation transforms the position first and
     * then calls {@link #addVertex(float, float, float)}. That bakes animated hand
     * transforms into the mesh and prevents the geometry cache from remaining
     * stable between frames.
     */
    @Override
    public VertexConsumer addVertex(Matrix4f matrix, float x, float y, float z) {
        beginVertex(detectBoneChange(matrix));
        vx = x; vy = y; vz = z;
        return this;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        beginVertex(-1);
        vx = x; vy = y; vz = z;
        return this;
    }

    // ---- VertexConsumer: color ----

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        vColor = ((alpha & 0xFF) << 24) | ((red & 0xFF) << 16) |
                 ((green & 0xFF) << 8) | (blue & 0xFF);
        return this;
    }

    @Override
    public VertexConsumer setColor(float red, float green, float blue, float alpha) {
        return setColor((int)(red * 255), (int)(green * 255),
                     (int)(blue * 255), (int)(alpha * 255));
    }

    @Override
    public VertexConsumer setColor(int argb) {
        vColor = argb;
        return this;
    }

    // ---- VertexConsumer: texture ----

    @Override
    public VertexConsumer setUv(float u, float v) {
        vu = u; vv = v;
        return this;
    }

    // ---- VertexConsumer: overlay ----

    @Override
    public VertexConsumer setUv1(int u, int v) {
        vOverlay = ((v & 0xFFFF) << 16) | (u & 0xFFFF);
        return this;
    }

    @Override
    public VertexConsumer setOverlay(int uv) {
        vOverlay = uv;
        return this;
    }

    // ---- VertexConsumer: light ----

    @Override
    public VertexConsumer setUv2(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer setLight(int uv) {
        return this;
    }

    // ---- VertexConsumer: normal ----

    /** The normal stays local like the position; the instance transform poses both. */
    @Override
    public VertexConsumer setNormal(PoseStack.Pose matrix, float x, float y, float z) {
        return setNormal(x, y, z);
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        vnx = x; vny = y; vnz = z;
        explicitNormal = true;
        finalizeVertex();
        return this;
    }

    // ---- Vertex finalization ----

    private void beginVertex(int boneIndex) {
        flushOpenVertex();
        vertexOpen = true;
        explicitNormal = false;
        currentVertexBoneIndex = boneIndex;
        vnx = 0.0f; vny = 0.0f; vnz = 1.0f;
        vu = 0.0f; vv = 0.0f;
        vColor = 0xFFFFFFFF;
        vOverlay = OverlayTexture.NO_OVERLAY;
    }

    private void flushOpenVertex() {
        if (vertexOpen) {
            finalizeVertex();
        }
    }

    public void finishOpenVertex() {
        flushOpenVertex();
        closeOpenPart();
    }

    private void finalizeVertex() {
        if (!vertexOpen) return;
        if (vertexCount >= MAX_VERTICES) {
            resetOpenVertexState();
            return;
        }

        int i = vertexCount++;
        int boneIndex = currentVertexBoneIndex;

        px[i] = vx; py[i] = vy; pz[i] = vz;
        this.nx[i] = vnx; this.ny[i] = vny; this.nz[i] = vnz;
        this.tu[i] = vu; this.tv[i] = vv;
        colors[i] = applyEntityOverlay(vColor, vOverlay);
        boneIndices[i] = boneIndex;
        texcoordBoundsMin[i] = 0;
        texcoordBoundsMax[i] = 0;
        generatedNormals[i] = !explicitNormal;

        if (topology == RenderLayerUtils.Topology.TRIANGLE_STRIP) {
            closeStripIfReady(i);
            applyGeneratedStripNormalIfReady(i);
        } else {
            applyGeneratedQuadNormalIfReady(i);
            applyQuadTexcoordBoundsIfReady(i);
            closeQuadIfReady(i);
        }
        resetOpenVertexState();
    }

    private void resetOpenVertexState() {
        vertexOpen = false;
        explicitNormal = false;
        currentVertexBoneIndex = -1;
        vColor = 0xFFFFFFFF;
        vOverlay = OverlayTexture.NO_OVERLAY;
    }

    /** Overlay rows below this hold the red damage tint; the rest hold the white flash. */
    private static final int OVERLAY_HURT_ROWS = 8;
    /** Alpha of the damage row in Minecraft's overlay texture, out of 255. */
    private static final int OVERLAY_HURT_ALPHA = 178;
    /** Highest column index of the overlay texture, which is also its white-flash ramp. */
    private static final int OVERLAY_MAX_U = 15;
    /** Fraction of alpha the white flash gives up across that ramp. */
    private static final float OVERLAY_WHITE_RAMP = 0.75f;
    private static final int OPAQUE = 255;

    /**
     * Folds Minecraft's entity overlay into a captured ARGB colour.
     *
     * <p>Vanilla samples a 16x16 overlay texture per vertex and its entity shader
     * resolves it after the texture lookup, as
     * {@code mix(overlay.rgb, texel.rgb * colour.rgb, overlay.a)}. Remix has no
     * per-vertex overlay, and the colour captured here is what the runtime multiplies
     * the texel by, so the blend is applied to the vertex colour before texture
     * modulation instead. That is an approximation: the flash lands on the tint
     * rather than on the shaded texel, so dark texels flash less than in vanilla and
     * a black one not at all. It is accepted because without it the red damage tint
     * and the white pre-explosion flash never reach the path tracer, and for cuboids
     * the blended colour rides the per-instance texture factor, so a flash is a
     * scene-instance update, never a mesh create.
     */
    public static int applyEntityOverlay(int argbColor, int packedOverlay) {
        if (packedOverlay == OverlayTexture.NO_OVERLAY) {
            return argbColor;
        }

        int u = Math.clamp(packedOverlay & 0xFFFF, 0, OVERLAY_MAX_U);
        int v = (packedOverlay >>> 16) & 0xFFFF;

        int overlayGreen;
        int overlayBlue;
        int overlayAlpha;
        if (v < OVERLAY_HURT_ROWS) {
            overlayGreen = 0;
            overlayBlue = 0;
            overlayAlpha = OVERLAY_HURT_ALPHA;
        } else {
            overlayGreen = OPAQUE;
            overlayBlue = OPAQUE;
            overlayAlpha = (int) ((1.0f - (float) u / OVERLAY_MAX_U * OVERLAY_WHITE_RAMP) * OPAQUE);
        }
        if (overlayAlpha >= OPAQUE) {
            return argbColor;
        }

        return (argbColor & 0xFF000000)
                | (mixOverlayChannel(OPAQUE, (argbColor >> 16) & 0xFF, overlayAlpha) << 16)
                | (mixOverlayChannel(overlayGreen, (argbColor >> 8) & 0xFF, overlayAlpha) << 8)
                | mixOverlayChannel(overlayBlue, argbColor & 0xFF, overlayAlpha);
    }

    /** GLSL {@code mix(overlay, base, alpha)} for one channel, with alpha out of 255. */
    private static int mixOverlayChannel(int overlay, int base, int alpha) {
        return (overlay * (OPAQUE - alpha) + base * alpha + OPAQUE / 2) / OPAQUE;
    }

    /**
     * Gives a strip vertex the normal of the triangle it closes.
     *
     * <p>A strip's triangles overlap, so unlike the quad pass there is no group
     * of four to wait for: every vertex from the third of its part onward closes
     * one triangle. The first two are seeded from that same triangle, since
     * nothing earlier describes them. Triangle parity counts from the start of
     * the open part, where {@code buildIndices} restarts the winding when the part
     * becomes a mesh; a part may begin at any vertex, since the pivot can change
     * mid-strip. Runs after {@link #closeStripIfReady} so that part is known.
     * Sources that supply their own normals keep them.
     */
    private void applyGeneratedStripNormalIfReady(int lastVertexIndex) {
        int stripStart = openStripStart();
        int inStrip = lastVertexIndex - stripStart;
        if (inStrip < 2 || !generatedNormals[lastVertexIndex]) return;

        int a = lastVertexIndex - 2;
        int b = lastVertexIndex - 1;
        // Odd triangles are wound the other way round, so swapping the first two
        // keeps every face of the strip pointing the same side out.
        if ((inStrip & 1) == 1) {
            int swap = a;
            a = b;
            b = swap;
        }

        float ex = px[b] - px[a], ey = py[b] - py[a], ez = pz[b] - pz[a];
        float fx = px[lastVertexIndex] - px[a];
        float fy = py[lastVertexIndex] - py[a];
        float fz = pz[lastVertexIndex] - pz[a];
        float fnx = ey * fz - ez * fy;
        float fny = ez * fx - ex * fz;
        float fnz = ex * fy - ey * fx;
        float len = (float) Math.sqrt(fnx * fnx + fny * fny + fnz * fnz);
        if (len <= 0.000001f) return;

        fnx /= len;
        fny /= len;
        fnz /= len;

        int first = inStrip == 2 ? stripStart : lastVertexIndex;
        for (int i = first; i <= lastVertexIndex; i++) {
            if (generatedNormals[i]) {
                nx[i] = fnx;
                ny[i] = fny;
                nz[i] = fnz;
            }
        }
    }

    /**
     * First vertex of the strip run the latest vertex extends. A consumer that
     * records no parts is one strip from vertex 0.
     */
    private int openStripStart() {
        return partOpen ? partStart[parts] : 0;
    }

    private void applyGeneratedQuadNormalIfReady(int lastVertexIndex) {
        if ((lastVertexIndex & 3) != 3) return;

        int start = lastVertexIndex - 3;
        if (!generatedNormals[start]
                && !generatedNormals[start + 1]
                && !generatedNormals[start + 2]
                && !generatedNormals[start + 3]) {
            return;
        }

        // Match the index winding used by RemixEntitySubmitter.buildSurface(): 0,2,1 and 0,3,2.
        float ax = px[start + 2] - px[start];
        float ay = py[start + 2] - py[start];
        float az = pz[start + 2] - pz[start];
        float bx = px[start + 1] - px[start];
        float by = py[start + 1] - py[start];
        float bz = pz[start + 1] - pz[start];

        float fnx = ay * bz - az * by;
        float fny = az * bx - ax * bz;
        float fnz = ax * by - ay * bx;
        float len = (float) Math.sqrt(fnx * fnx + fny * fny + fnz * fnz);
        if (len <= 0.000001f) return;

        fnx /= len;
        fny /= len;
        fnz /= len;

        for (int i = start; i <= lastVertexIndex; i++) {
            if (generatedNormals[i]) {
                nx[i] = fnx;
                ny[i] = fny;
                nz[i] = fnz;
            }
        }
    }

    /**
     * Supplies the per-quad UV rectangle required by Remix POM when the source
     * renderer did not provide atlas sprite bounds explicitly.
     *
     * <p>Entity textures are atlases of model faces. Using the full texture as
     * the wrapping domain lets a parallax ray cross a face boundary and sample
     * an unrelated part of the skin. The four UVs emitted for a Minecraft model
     * quad describe the correct local wrapping rectangle.
     */
    private void applyQuadTexcoordBoundsIfReady(int lastVertexIndex) {
        if ((lastVertexIndex & 3) != 3) return;

        int start = lastVertexIndex - 3;
        for (int i = start; i <= lastVertexIndex; i++) {
            if (texcoordBoundsMin[i] != 0 || texcoordBoundsMax[i] != 0) {
                // BakedQuad capture already supplied authoritative sprite bounds.
                return;
            }
        }

        float minU = Float.POSITIVE_INFINITY;
        float minV = Float.POSITIVE_INFINITY;
        float maxU = Float.NEGATIVE_INFINITY;
        float maxV = Float.NEGATIVE_INFINITY;
        for (int i = start; i <= lastVertexIndex; i++) {
            minU = Math.min(minU, tu[i]);
            minV = Math.min(minV, tv[i]);
            maxU = Math.max(maxU, tu[i]);
            maxV = Math.max(maxV, tv[i]);
        }

        if (!Float.isFinite(minU) || !Float.isFinite(minV)
                || !Float.isFinite(maxU) || !Float.isFinite(maxV)
                || maxU <= minU || maxV <= minV) {
            return;
        }

        int packedMin = HardcodedVertex.packHalf2x16(minU, minV);
        int packedMax = HardcodedVertex.packHalf2x16(maxU, maxV);
        for (int i = start; i <= lastVertexIndex; i++) {
            texcoordBoundsMin[i] = packedMin;
            texcoordBoundsMax[i] = packedMax;
        }
    }

    // ---- BakedQuad interception (items, BakedModel entities) ----

    // Scratch for the four-argument-per-vertex quad overload below. Safe to
    // pool: the ten-arg quad() consumes both arrays synchronously (this
    // override and any delegate's) and nothing retains them. Brightness is
    // always all-ones here.
    //
    // Per instance, not static: the ten-arg quad() hands these arrays to an
    // arbitrary downstream VertexConsumer, so a process-wide array would let a
    // single downstream writer corrupt brightness for every item quad after it.
    private final float[] quadUnitBrightness = {1.0f, 1.0f, 1.0f, 1.0f};
    private final int[] quadLightScratch = new int[4];
    private final int[] quadColors = new int[4];

    /**
     * Allocation-free replacement for the vanilla default, which allocates two
     * arrays per quad; values and downstream calls are identical.
     */
    @Override
    public void putBulkData(PoseStack.Pose matrixEntry, BakedQuad quad,
                     float red, float green, float blue, float alpha, int light, int overlay) {
        quadLightScratch[0] = light;
        quadLightScratch[1] = light;
        quadLightScratch[2] = light;
        quadLightScratch[3] = light;
        this.putBulkData(matrixEntry, quad, quadUnitBrightness, red, green, blue, alpha,
                quadLightScratch, overlay, false);
    }

    /**
     * Intercepts BakedModel quad rendering so item animation is captured as a bone transform
     * rather than baked into vertex positions.
     *
     * <p>Vanilla's default {@code quad()} implementation applies the matrix and calls
     * {@code vertex(float, float, float)}, bypassing bone detection. This override instead:
     * <ol>
     *   <li>Pushes the {@code MatrixStack.Entry} as a bone via {@link #detectBoneChange}</li>
     *   <li>Reads LOCAL (model-space) positions directly from {@link BakedQuad#getVertices()}</li>
     *   <li>Emits via {@link #acceptModelSpaceVertex}</li>
     * </ol>
     *
     * <p>Result: one pooled local-space mesh per item geometry, with the vanilla
     * pose applied as an ordinary Remix instance transform each frame.
     *
     * <p>A flat item is ItemModelGenerator's card: two full 16x16 faces a sixteenth
     * apart, and a sliver standing on every edge of every run of opaque texels. The
     * faces are rebuilt here as rectangles of those opaque texels, sharing their edges
     * with the slivers, so the card has no transparent texel left for a ray to hit.
     * The runtime resumes a ray past a transparent texel from an origin pushed along
     * the face normal by a few float ulps of the hit's world position, and a surface
     * inside that push, the held flame where it crosses the card, was never met and
     * showed as a hairline of background; a placed torch's block model, whose faces
     * cover only opaque texels, never had one.
     *
     * <p>A clear-glass item keeps its whole faces. Its transparent texels are not holes
     * but the glass itself, which its material refracts through as a placed pane's does;
     * rebuilt, the card would be a bare frame.
     */
    @Override
    public void putBulkData(PoseStack.Pose matrixEntry, BakedQuad quad, float[] brightnesses,
                     float red, float green, float blue, float alpha, int[] lights, int overlay,
                     boolean colorize) {
        // The pose is fixed for the whole quad, so resolve the pivot once here
        // rather than per vertex below.
        int quadBoneIndex = detectBoneChange(matrixEntry.pose());

        int[] vd = quad.getVertices();
        int boundsMin = 0;
        int boundsMax = 0;
        if (quad.getSprite() != null) {
            boundsMin = HardcodedVertex.packHalf2x16(quad.getSprite().getU0(), quad.getSprite().getV0());
            boundsMax = HardcodedVertex.packHalf2x16(quad.getSprite().getU1(), quad.getSprite().getV1());
        }

        // Per-face normal: same for all 4 vertices on a BakedQuad (offset 7 of first vertex).
        int pn = vd[7];
        float fnx = (byte)(pn & 0xFF) / 127f;
        float fny = (byte)((pn >> 8) & 0xFF) / 127f;
        float fnz = (byte)((pn >> 16) & 0xFF) / 127f;
        if (fnx * fnx + fny * fny + fnz * fnz < 0.01f) {
            // Packed normal is zero, so compute from edge cross-product
            float x0 = Float.intBitsToFloat(vd[0]),  y0 = Float.intBitsToFloat(vd[1]),  z0 = Float.intBitsToFloat(vd[2]);
            float x1 = Float.intBitsToFloat(vd[8]),  y1 = Float.intBitsToFloat(vd[9]),  z1 = Float.intBitsToFloat(vd[10]);
            float x2 = Float.intBitsToFloat(vd[16]), y2 = Float.intBitsToFloat(vd[17]), z2 = Float.intBitsToFloat(vd[18]);
            float ex = x1-x0, ey = y1-y0, ez = z1-z0;
            float fx = x2-x0, fy = y2-y0, fz = z2-z0;
            fnx = ey*fz - ez*fy; fny = ez*fx - ex*fz; fnz = ex*fy - ey*fx;
            float len = (float) Math.sqrt(fnx*fnx + fny*fny + fnz*fnz);
            if (len > 0f) { fnx /= len; fny /= len; fnz /= len; }
        }

        // Flush any open streamed vertex first, so vertexCount is exactly
        // where this quad's four vertices will land.
        flushOpenVertex();
        int quadStart = vertexCount;
        for (int v = 0; v < 4; v++) {
            quadColors[v] = bakedQuadVertexColor(vd, v, brightnesses, red, green, blue, alpha, overlay, colorize);
        }
        boolean cardFace = isFullCardFace(vd, quad.getDirection());
        int[] rects = cardFace
                && EntityRenderContext.get().getBlockItemLayer() != BlockTextureManager.L_GLASS
                ? cardFaceRects(quad.getSprite())
                : null;
        if (rects != null) {
            emitCardFaceRects(vd, rects, quad.getSprite().contents(), quadColors,
                    fnx, fny, fnz, boundsMin, boundsMax, quadBoneIndex);
            return;
        }
        if (quad instanceof BakedQuadStaticGeometryCache cache) {
            pendingBakedQuadStart = vertexCount;
            pendingBakedQuadCache = cache;
            pendingBakedQuadVertexData = vd;
        }
        try {
            for (int v = 0; v < 4; v++) {
                int off = v * 8;
                acceptModelSpaceVertex(
                        Float.intBitsToFloat(vd[off]), Float.intBitsToFloat(vd[off + 1]),
                        Float.intBitsToFloat(vd[off + 2]), quadColors[v],
                        Float.intBitsToFloat(vd[off + 4]), Float.intBitsToFloat(vd[off + 5]),
                        fnx, fny, fnz, boundsMin, boundsMax, quadBoneIndex);
            }
            if (cardFace) markCardFace(quadStart);
        } finally {
            pendingBakedQuadStart = NO_VERTICES;
            pendingBakedQuadCache = null;
            pendingBakedQuadVertexData = null;
        }
    }

    /**
     * The colour {@code VertexConsumer.quad()} gives vertex {@code v}: the supplied tint,
     * times the baked vertex colour when {@code colorize} is set, under the entity overlay.
     */
    private int bakedQuadVertexColor(int[] vd, int v, float[] brightnesses,
                                     float red, float green, float blue, float alpha,
                                     int overlay, boolean colorize) {
        float br = (brightnesses != null && v < brightnesses.length) ? brightnesses[v] : 1f;
        int vc = vd[v * 8 + 3];
        int fr, fg, fb;
        if (colorize) {
            // BakedQuad packs its color element as 0xAABBGGRR.
            fr = Math.min(255, (int) ((vc & 0xFF) * br * red));
            fg = Math.min(255, (int) (((vc >> 8) & 0xFF) * br * green));
            fb = Math.min(255, (int) (((vc >> 16) & 0xFF) * br * blue));
        } else {
            fr = Math.min(255, (int) (br * red * 255.0f));
            fg = Math.min(255, (int) (br * green * 255.0f));
            fb = Math.min(255, (int) (br * blue * 255.0f));
        }
        int fa = Math.min(255, (int) (alpha * 255.0f));
        return applyEntityOverlay((fa << 24) | (fr << 16) | (fg << 8) | fb, overlay);
    }

    /**
     * Whether a baked quad is a full face of an item card: one of the two 16x16 layer
     * faces ItemModelGenerator builds for a flat item, spanning the card exactly on one
     * of its two planes. Positions are compared exactly; the generator bakes them from
     * integer sixteenths, so they carry no rounding.
     */
    static boolean isFullCardFace(int[] vertexData, Direction face) {
        if (face != Direction.NORTH && face != Direction.SOUTH) return false;
        float plane = Float.intBitsToFloat(vertexData[2]);
        if (plane != CARD_FRONT_Z && plane != CARD_BACK_Z) return false;
        int corners = 0;
        for (int v = 0; v < 4; v++) {
            int off = v * 8;
            float x = Float.intBitsToFloat(vertexData[off]);
            float y = Float.intBitsToFloat(vertexData[off + 1]);
            float z = Float.intBitsToFloat(vertexData[off + 2]);
            if ((x != 0f && x != 1f) || (y != 0f && y != 1f) || z != plane) return false;
            corners |= 1 << ((x == 0f ? 0 : 1) + (y == 0f ? 0 : 2));
        }
        return corners == 0b1111;
    }

    /**
     * The opaque-texel rectangles of {@code sprite}, or null when it has none to give: no
     * sprite, or one past {@link #MAX_CARD_FACE_RECTS}.
     */
    private static int[] cardFaceRects(TextureAtlasSprite sprite) {
        if (sprite == null) return null;
        SpriteContents contents = sprite.contents();
        int[] rects = CARD_FACE_RECTS.get(contents);
        if (rects == null) {
            rects = opaqueRects(opaqueTexels(contents), contents.width(), contents.height(),
                    MAX_CARD_FACE_RECTS);
            CARD_FACE_RECTS.put(contents, rects == null ? BUSY_CARD : rects);
        }
        return rects == BUSY_CARD ? null : rects;
    }

    /**
     * Texels opaque in any frame of the sprite, row-major from the top-left: the mask
     * ItemModelGenerator stands its slivers on, frames united the same way.
     */
    private static boolean[] opaqueTexels(SpriteContents contents) {
        int w = contents.width();
        int h = contents.height();
        boolean[] opaque = new boolean[w * h];
        contents.getUniqueFrames().forEach(frame -> {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (!contents.isTransparent(frame, x, y)) opaque[y * w + x] = true;
                }
            }
        });
        return opaque;
    }

    /**
     * Rectangles covering the opaque texels of a {@code w} by {@code h} mask: each row's
     * runs, merged into the row above whenever the two rows have the same runs. Packed as
     * x0, y0, x1, y1 per rectangle with x1 and y1 exclusive, in row order; null when more
     * than {@code maxRects} would be needed.
     */
    static int[] opaqueRects(boolean[] opaque, int w, int h, int maxRects) {
        IntArrayList rects = new IntArrayList();
        int previousRow = -1;
        int previousRuns = -1;
        for (int y = 0; y < h; y++) {
            int rowStart = rects.size();
            int x = 0;
            while (x < w) {
                if (!opaque[y * w + x]) {
                    x++;
                    continue;
                }
                int runStart = x;
                while (x < w && opaque[y * w + x]) x++;
                rects.add(runStart);
                rects.add(y);
                rects.add(x);
                rects.add(y + 1);
            }
            int runs = (rects.size() - rowStart) / 4;
            if (runs == previousRuns && sameRuns(rects, previousRow, rowStart, runs)) {
                for (int run = 0; run < runs; run++) rects.set(previousRow + run * 4 + 3, y + 1);
                rects.size(rowStart);
            } else {
                previousRow = rowStart;
                previousRuns = runs;
            }
            if (rects.size() / 4 > maxRects) return null;
        }
        return rects.toIntArray();
    }

    private static boolean sameRuns(IntArrayList rects, int rowA, int rowB, int runs) {
        for (int run = 0; run < runs; run++) {
            int a = rowA + run * 4;
            int b = rowB + run * 4;
            if (rects.getInt(a) != rects.getInt(b) || rects.getInt(a + 2) != rects.getInt(b + 2)) return false;
        }
        return true;
    }

    /**
     * The card x of texel edge {@code edge} of a {@code size}-texel-wide sprite, computed as
     * ItemModelGenerator and BakedQuadFactory compute a sliver's, so the two share floats.
     */
    static float cardX(int edge, int size) {
        return (edge * (16.0f / size)) / 16.0f;
    }

    /** The card y of texel-row edge {@code edge}, counted from the top; row 0 is the top of the card. */
    static float cardY(int edge, int size) {
        return (16.0f - edge * (16.0f / size)) / 16.0f;
    }

    /**
     * Emits a full card face as one quad per opaque rectangle. Each rectangle's corners
     * take the place of the original quad's corners in its winding, and its UVs are read
     * off the original face's mapping, so a mirrored back face stays mirrored.
     */
    private void emitCardFaceRects(int[] vd, int[] rects, SpriteContents contents, int[] colors,
                                   float nx, float ny, float nz, int boundsMin, int boundsMax, int bone) {
        int w = contents.width();
        int h = contents.height();
        float z = Float.intBitsToFloat(vd[2]);
        int[] cornerX = new int[4];
        int[] cornerY = new int[4];
        float[] cornerU = new float[4];
        float[] cornerV = new float[4];
        for (int v = 0; v < 4; v++) {
            int off = v * 8;
            int cx = Float.intBitsToFloat(vd[off]) == 0f ? 0 : 1;
            int cy = Float.intBitsToFloat(vd[off + 1]) == 0f ? 0 : 1;
            cornerX[v] = cx;
            cornerY[v] = cy;
            cornerU[cy * 2 + cx] = Float.intBitsToFloat(vd[off + 4]);
            cornerV[cy * 2 + cx] = Float.intBitsToFloat(vd[off + 5]);
        }
        for (int r = 0; r < rects.length; r += 4) {
            float x0 = cardX(rects[r], w);
            float x1 = cardX(rects[r + 2], w);
            float top = cardY(rects[r + 1], h);
            float bottom = cardY(rects[r + 3], h);
            for (int v = 0; v < 4; v++) {
                float x = cornerX[v] == 0 ? x0 : x1;
                float y = cornerY[v] == 0 ? bottom : top;
                acceptModelSpaceVertex(x, y, z, colors[v], bilinear(cornerU, x, y), bilinear(cornerV, x, y),
                        nx, ny, nz, boundsMin, boundsMax, bone);
            }
        }
    }

    /** Interpolates a per-corner value, indexed {@code cy * 2 + cx}, at card position (x, y). */
    private static float bilinear(float[] corner, float x, float y) {
        return (1f - x) * (1f - y) * corner[0] + x * (1f - y) * corner[1]
                + (1f - x) * y * corner[2] + x * y * corner[3];
    }

    /** Records the card face that just closed into the open part, if it landed there whole. */
    private void markCardFace(int quadStart) {
        if (!partOpen || partCardFace[parts] != NO_VERTICES) return;
        if (vertexCount != quadStart + VERTICES_PER_QUAD
                || partStart[parts] + partVertexCount[parts] != vertexCount) return;
        partCardFace[parts] = quadStart;
    }

    // ---- Model-space vertex injection (from CuboidMixin) ----

    /**
     * Accepts a pre-transform (model-space) vertex directly from the cuboid mixin.
     * This bypasses vanilla's post-transform vertex path, giving us stable local
     * geometry for the rigid mesh pool. The pivot index is resolved once by the caller.
     */
    public void acceptModelSpaceVertex(float x, float y, float z, int color,
                                        float u, float v,
                                        float nx, float ny, float nz,
                                        int boneIndex) {
        acceptModelSpaceVertex(x, y, z, color, u, v, nx, ny, nz, 0, 0, boneIndex);
    }

    public void acceptModelSpaceVertex(float x, float y, float z, int color,
                                        float u, float v,
                                        float nx, float ny, float nz,
                                        int boundsMin, int boundsMax,
                                        int boneIndex) {
        flushOpenVertex();
        if (vertexCount >= MAX_VERTICES) return;

        int i = vertexCount++;
        // boneIndex is resolved once per cuboid or quad by the caller, not
        // re-read from the thread-local context per vertex.

        px[i] = x; py[i] = y; pz[i] = z;
        this.nx[i] = nx; this.ny[i] = ny; this.nz[i] = nz;
        tu[i] = u; tv[i] = v;
        colors[i] = color;
        boneIndices[i] = boneIndex;
        texcoordBoundsMin[i] = boundsMin;
        texcoordBoundsMax[i] = boundsMax;
        generatedNormals[i] = false;
        applyQuadTexcoordBoundsIfReady(i);
        closeQuadIfReady(i);
    }


    /**
     * Closes the quad ending at {@code lastVertexIndex} into a rigid part record.
     *
     * <p>Must run after both quad patch passes, which rewrite normals and texcoord
     * bounds once the fourth vertex lands; hashing earlier would fold values that
     * are about to change.
     *
     * <p>A quad whose four vertices disagree on their pivot cannot belong to any
     * rigid part, so it ends the open run and is dropped.
     */
    private void closeQuadIfReady(int lastVertexIndex) {
        if (!recordParts || (lastVertexIndex & 3) != 3) return;

        int start = lastVertexIndex - 3;
        int bone = boneIndices[start];
        if (boneIndices[start + 1] != bone
                || boneIndices[start + 2] != bone
                || boneIndices[start + 3] != bone) {
            closeOpenPart();
            return;
        }

        if (partOpen && partBone[parts] == bone
                && partStart[parts] + partVertexCount[parts] == start) {
            partVertexCount[parts] += 4;
        } else {
            closeOpenPart();
            if (parts >= MAX_PARTS) return;
            partStart[parts] = start;
            partVertexCount[parts] = 4;
            partBone[parts] = bone;
            partSides[parts] = null;
            partSprite[parts] = null;
            partBlockItemLayer[parts] = EntityRenderContext.get().getBlockItemLayer();
            partCardFace[parts] = NO_VERTICES;
            lane0 = FNV_OFFSET; lane1 = FNV_OFFSET; lane2 = FNV_OFFSET; lane3 = FNV_OFFSET;
            partOpen = true;
        }
        foldQuad(start);
    }

    /**
     * Extends or opens the rigid part a strip vertex belongs to.
     *
     * <p>A strip cannot be cut at an arbitrary boundary the way a run of quads
     * can, because its triangles share vertices with their neighbours. The only
     * safe split is where the pivot changes, so a run of vertices under one bone
     * becomes one part and the vertex count grows by one at a time.
     */
    private void closeStripIfReady(int lastVertexIndex) {
        if (!recordParts) return;

        int bone = boneIndices[lastVertexIndex];
        if (partOpen && partBone[parts] == bone
                && partStart[parts] + partVertexCount[parts] == lastVertexIndex) {
            partVertexCount[parts]++;
            return;
        }

        closeOpenPart();
        if (parts >= MAX_PARTS) return;
        partStart[parts] = lastVertexIndex;
        partVertexCount[parts] = 1;
        partBone[parts] = bone;
        partSides[parts] = null;
        partSprite[parts] = null;
        // Streamed strip vertices never come from a baked item quad, so the
        // constants are exact.
        partBlockItemLayer[parts] = EntityRenderContext.NO_BLOCK_ITEM;
        partCardFace[parts] = NO_VERTICES;
        lane0 = FNV_OFFSET; lane1 = FNV_OFFSET; lane2 = FNV_OFFSET; lane3 = FNV_OFFSET;
        partOpen = true;
    }

    /**
     * Folds one quad into the part lanes: a bake-time static digest plus the four
     * vertex colours. The digest covers positions, normals, UVs, and bounds, all
     * fixed at model bake, so it caches on the BakedQuad itself and is
     * content-verified on every hit; a fresh capture of the same quad costs a
     * 32-int compare instead of forty numeric folds. Colours stay outside the
     * digest because tint changes per render.
     */
    private void foldQuad(int start) {
        long staticHash;
        BakedQuadStaticGeometryCache cache = pendingBakedQuadCache;
        int[] bakedVertexData = pendingBakedQuadVertexData;
        int boundsMin = texcoordBoundsMin[start];
        int boundsMax = texcoordBoundsMax[start];
        if (start == pendingBakedQuadStart && cache != null
                && cache.rtxremix$matchesStaticGeometry(bakedVertexData,
                        boundsMin, boundsMax)) {
            staticHash = cache.rtxremix$getStaticGeometryHash();
        } else {
            staticHash = computeStaticQuadHash(start);
            if (start == pendingBakedQuadStart && cache != null) {
                cache.rtxremix$setStaticGeometryHash(staticHash, bakedVertexData,
                        boundsMin, boundsMax);
            }
        }

        lane0 = fold(fold(lane0, staticHash ^ QUAD_SLOT_0), colors[start]);
        lane1 = fold(fold(lane1, staticHash ^ QUAD_SLOT_1), colors[start + 1]);
        lane2 = fold(fold(lane2, staticHash ^ QUAD_SLOT_2), colors[start + 2]);
        lane3 = fold(fold(lane3, staticHash ^ QUAD_SLOT_3), colors[start + 3]);
    }

    private long computeStaticQuadHash(int start) {
        long vertex0 = foldStaticVertex(STATIC_VERTEX_0, start);
        long vertex1 = foldStaticVertex(STATIC_VERTEX_1, start + 1);
        long vertex2 = foldStaticVertex(STATIC_VERTEX_2, start + 2);
        long vertex3 = foldStaticVertex(STATIC_VERTEX_3, start + 3);
        long hash = fold(STATIC_QUAD_SCHEMA, vertex0);
        hash = fold(hash, vertex1);
        hash = fold(hash, vertex2);
        return fold(hash, vertex3);
    }

    private long foldStaticVertex(long hash, int i) {
        hash = fold(hash, Float.floatToIntBits(px[i]));
        hash = fold(hash, Float.floatToIntBits(py[i]));
        hash = fold(hash, Float.floatToIntBits(pz[i]));
        hash = fold(hash, Float.floatToIntBits(nx[i]));
        hash = fold(hash, Float.floatToIntBits(ny[i]));
        hash = fold(hash, Float.floatToIntBits(nz[i]));
        hash = fold(hash, Float.floatToIntBits(tu[i]));
        hash = fold(hash, Float.floatToIntBits(tv[i]));
        hash = fold(hash, texcoordBoundsMin[i]);
        return fold(hash, texcoordBoundsMax[i]);
    }

    private static long fold(long hash, long value) {
        hash ^= value;
        return hash * FNV_PRIME;
    }

    /** Finalizes the open part record, combining its lanes with its vertex count. */
    private void closeOpenPart() {
        if (!partOpen) return;
        if (topology == RenderLayerUtils.Topology.TRIANGLE_STRIP) {
            // A strip vertex only gets its normal once the triangle that follows
            // it arrives, so folding as they came in would hash stale normals for
            // the first two. Fold the finished run instead, colour included: for
            // an untextured strip the colour is the whole surface.
            lane0 = FNV_OFFSET;
            int end = partStart[parts] + partVertexCount[parts];
            for (int i = partStart[parts]; i < end; i++) {
                lane0 = fold(foldStaticVertex(lane0, i), colors[i]);
            }
        }
        long hash = fold(FNV_OFFSET, PART_HASH_SCHEMA);
        hash = fold(hash, partVertexCount[parts]);
        hash = fold(hash, lane0);
        hash = fold(hash, lane1);
        hash = fold(hash, lane2);
        hash = fold(hash, lane3);
        partHash[parts] = hash;
        parts++;
        partOpen = false;
    }

    // ---- Rigid part accessors ----

    /** Number of finalized rigid parts captured for this layer. */
    public int getPartCount() { return parts; }

    public int getPartStart(int part) { return partStart[part]; }

    public int getPartVertexCount(int part) { return partVertexCount[part]; }

    public int getPartBone(int part) { return partBone[part]; }

    public long getPartHash(int part) { return partHash[part]; }

    /** The cuboid this part came from, or null if it came from another source. */
    public ModelPart.Polygon[] getPartSides(int part) { return partSides[part]; }

    /** The sprite a cuboid part was painted with, or null; see {@link #acceptCuboid}. */
    public TextureAtlasSprite getPartSprite(int part) { return partSprite[part]; }

    public int getPartTint(int part) { return partTint[part]; }

    /** The terrain layer of the block item this part came from, or {@link EntityRenderContext#NO_BLOCK_ITEM}. */
    public int getPartBlockItemLayer(int part) { return partBlockItemLayer[part]; }

    /**
     * The first vertex of a full item-card face in the part whose vertices start at
     * {@code start}, or {@link #NO_VERTICES} when that part is not an item card.
     */
    public int cardFaceOfPartStartingAt(int start) {
        for (int part = 0; part < parts; part++) {
            if (partStart[part] == start) return partCardFace[part];
        }
        return NO_VERTICES;
    }


    /**
     * Records one {@code ModelPart.Cuboid} as a rigid part that carries no vertices:
     * the cuboid's identity, its tint and the bone it hangs off. Its geometry is
     * expanded only when the submitter misses the mesh pool, through
     * {@link #emitCuboidGeometry}, so a pooled cuboid costs no vertex work at capture.
     *
     * <p>{@code sides} is retained for its <em>identity</em>, not its contents.
     * {@code ModelPart.Cuboid.sides}, {@code Quad.vertices}/{@code direction} and
     * {@code Vertex.pos}/{@code u}/{@code v} are all final and written once at bake
     * time, so one array reference means one fixed set of positions, normals and
     * UVs for the life of the object. A resource reload bakes new objects, which
     * produce new keys rather than stale hits.
     *
     * <p>{@code sprite} is the atlas sprite the cuboid was painted with, null when it
     * was drawn without {@code SpriteTexturedVertexConsumer}. It is identity too: an
     * atlas re-stitch creates new Sprite objects, and the wrapper's UV remap is
     * reproduced by {@link #emitCuboidGeometry} when the cuboid is rebuilt.
     */
    public void acceptCuboid(ModelPart.Polygon[] sides, int color, Matrix4f pose, TextureAtlasSprite sprite) {
        int boneIndex = beginCuboid(pose);
        closeOpenPart();
        if (parts >= MAX_PARTS) return;
        partStart[parts] = NO_VERTICES;
        partVertexCount[parts] = sides.length * VERTICES_PER_QUAD;
        partBone[parts] = boneIndex;
        partSides[parts] = sides;
        partSprite[parts] = sprite;
        partTint[parts] = color;
        partHash[parts] = 0L;
        // A constant rather than a context sample, so a replayed cuboid, which has
        // no context to sample, agrees with the live one. Translucent and glass
        // blocks are baked-quad models, so the constants are exact.
        partBlockItemLayer[parts] = EntityRenderContext.NO_BLOCK_ITEM;
        partCardFace[parts] = NO_VERTICES;
        parts++;
    }

    /**
     * Re-records a cuboid part from a snapshot, without re-evaluating the model.
     *
     * <p>A cuboid part carries no vertices, only the cuboid it came from, its sprite,
     * its tint and the bone it hangs off. That is enough to submit it again, which is
     * what lets a distant off-screen entity keep its pose for a few frames rather than
     * pay a full model evaluation on every one. The bone index refers to the
     * caller's own snapshotted transforms, not to this consumer's bone tracking.
     */
    public void replayCuboidPart(ModelPart.Polygon[] sides, int color, int boneIndex, TextureAtlasSprite sprite) {
        closeOpenPart();
        if (parts >= MAX_PARTS) return;
        partStart[parts] = NO_VERTICES;
        partVertexCount[parts] = sides.length * VERTICES_PER_QUAD;
        partBone[parts] = boneIndex;
        partSides[parts] = sides;
        partSprite[parts] = sprite;
        partTint[parts] = color;
        partHash[parts] = 0L;
        partBlockItemLayer[parts] = EntityRenderContext.NO_BLOCK_ITEM;
        partCardFace[parts] = NO_VERTICES;
        parts++;
    }

    /**
     * Writes a cuboid's vertices into this consumer.
     *
     * <p>Capture records only the cuboid's identity, so this is where its geometry
     * is actually expanded: the submitter's scratch consumer takes this path when a
     * cuboid misses the pool. It goes through the ordinary per-vertex path so the
     * quad patches that fix up normals and texcoord bounds apply to it as well.
     *
     * <p>With a sprite, each UV is mapped into the sprite's atlas rectangle exactly as
     * {@code SpriteTexturedVertexConsumer} would have done had the cuboid not been
     * intercepted, so the pooled mesh carries the atlas UVs and the texcoord bounds
     * derived from them describe the sprite, not the whole atlas.
     */
    public void emitCuboidGeometry(ModelPart.Polygon[] sides, int color, int boneIndex, TextureAtlasSprite sprite) {
        emitCuboidGeometry(sides, color, boneIndex, sprite, 0f);
    }

    /**
     * {@link #emitCuboidGeometry(ModelPart.Polygon[], int, int, TextureAtlasSprite)} with every vertex
     * moved {@code shellExpand} block units along its face normal, so several paintings
     * of one cuboid can be stacked into a single mesh at increasing depths.
     */
    public void emitCuboidGeometry(ModelPart.Polygon[] sides, int color, int boneIndex, TextureAtlasSprite sprite,
                                   float shellExpand) {
        for (ModelPart.Polygon quad : sides) {
            float qnx = quad.normal.x();
            float qny = quad.normal.y();
            float qnz = quad.normal.z();
            for (ModelPart.Vertex vertex : quad.vertices) {
                float u = sprite == null ? vertex.u : sprite.getU(vertex.u);
                float v = sprite == null ? vertex.v : sprite.getV(vertex.v);
                acceptModelSpaceVertex(
                        vertex.pos.x / 16f + qnx * shellExpand,
                        vertex.pos.y / 16f + qny * shellExpand,
                        vertex.pos.z / 16f + qnz * shellExpand,
                        color,
                        u, v,
                        qnx, qny, qnz,
                        boneIndex);
            }
        }
    }
    /** True while this consumer is bound to the active Remix entity capture. */
    public boolean isRemixCaptureActive() {
        return EntityRenderContext.get().isRemixPass();
    }

    /**
     * Returns this consumer's allocation-free iterator for an immutable singleton
     * cuboid list. A pending iterator is never overwritten; reentrant or custom
     * traversal falls back to vanilla's fresh iterator.
     */
    public Iterator<ModelPart.Cube> acquireSingletonCuboidIterator(ModelPart.Cube cuboid) {
        return singletonCuboidIterator.tryReset(cuboid) ? singletonCuboidIterator : null;
    }

    private static final class SingletonCuboidIterator implements Iterator<ModelPart.Cube> {
        private ModelPart.Cube cuboid;

        boolean tryReset(ModelPart.Cube cuboid) {
            if (this.cuboid != null) return false;
            this.cuboid = cuboid;
            return true;
        }

        void clear() {
            cuboid = null;
        }

        @Override
        public boolean hasNext() {
            return cuboid != null;
        }

        @Override
        public ModelPart.Cube next() {
            ModelPart.Cube next = cuboid;
            if (next == null) throw new NoSuchElementException();
            cuboid = null;
            return next;
        }
    }

    /** Order in which the entity first requested this layer. Scales the shell offset. */
    public int getLayerIndex() { return layerIndex; }

    public void setLayerIndex(int layerIndex) { this.layerIndex = layerIndex; }

    /** The provider's reusable view of this consumer as a captured layer; see {@link RemixVertexConsumerProvider.CapturedLayer}. */
    private final RemixVertexConsumerProvider.CapturedLayer capturedLayer = new RemixVertexConsumerProvider.CapturedLayer(this);

    RemixVertexConsumerProvider.CapturedLayer capturedLayer() { return capturedLayer; }
    // ---- Accessors ----

    public ResourceLocation getTextureId() { return textureId; }
    public RenderLayerUtils.Topology getTopology() { return topology; }
    public boolean isTransientGeometry() { return transientGeometry; }
    public int getVertexCount() { return vertexCount; }

    public float[] getPx() { return px; }
    public float[] getPy() { return py; }
    public float[] getPz() { return pz; }
    public float[] getNx() { return nx; }
    public float[] getNy() { return ny; }
    public float[] getNz() { return nz; }
    public float[] getTu() { return tu; }
    public float[] getTv() { return tv; }
    public int[] getColors() { return colors; }
    public int[] getBoneIndices() { return boneIndices; }
    public int[] getTexcoordBoundsMin() { return texcoordBoundsMin; }
    public int[] getTexcoordBoundsMax() { return texcoordBoundsMax; }

    public void clear() {
        // Release the cuboid references BEFORE zeroing the counter that bounds
        // them. Writes only ever land at index `parts` before it increments, so
        // [0, parts] covers every slot that can hold one and clearing past it
        // costs barriered stores for nothing. The fill is a GC aid, not keying
        // correctness.
        Arrays.fill(partSides, 0, Math.min(parts + 1, MAX_PARTS), null);
        Arrays.fill(partSprite, 0, Math.min(parts + 1, MAX_PARTS), null);
        pendingBakedQuadStart = NO_VERTICES;
        pendingBakedQuadCache = null;
        pendingBakedQuadVertexData = null;
        singletonCuboidIterator.clear();
        parts = 0;
        partOpen = false;
        vertexCount = 0;
        resetOpenVertexState();
        lastPoseValid = false;
    }
}
