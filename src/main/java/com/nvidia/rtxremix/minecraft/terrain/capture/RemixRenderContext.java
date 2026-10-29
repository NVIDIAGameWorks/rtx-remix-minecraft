/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * {@link RenderContext} implementation that captures quads emitted by FRAPI
 * models via {@code emitBlockQuads()}. The captured quads are converted to
 * {@link SectionBlockData.BlockQuad} records with full sprite/layer context.
 *
 * <p>Thread-local: one instance is reused per worker thread (via ThreadLocal
 * in {@link BlockQuadEmitter}).
 */
final class RemixRenderContext implements RenderContext {

    private final CapturingQuadEmitter emitter = new CapturingQuadEmitter();
    private final Consumer<Mesh> meshConsumer = mesh -> mesh.outputTo(getEmitter());
    private final Deque<QuadTransform> transformStack = new ArrayDeque<>();
    private final List<SectionBlockData.BlockQuad> captured = new ArrayList<>();

    private BlockRenderView world;
    private BlockState state;
    private BlockPos pos;
    private Predicate<Direction> cullTest;

    void prepare(BlockRenderView world, BlockState state, BlockPos pos,
                 Predicate<Direction> cullTest) {
        this.world = world;
        this.state = state;
        this.pos = pos;
        this.cullTest = cullTest;
        captured.clear();
        transformStack.clear();
        emitter.clear();
    }

    List<SectionBlockData.BlockQuad> harvest() {
        return List.copyOf(captured);
    }

    // --- RenderContext ---

    @Override
    public QuadEmitter getEmitter() {
        emitter.clear();
        return emitter;
    }

    @SuppressWarnings("deprecation")
    @Override
    public Consumer<Mesh> meshConsumer() {
        return meshConsumer;
    }

    @Override
    public boolean isFaceCulled(@Nullable Direction face) {
        if (face == null) return false;
        return cullTest != null && cullTest.test(face);
    }

    @Override
    public void pushTransform(QuadTransform transform) {
        transformStack.push(transform);
    }

    @Override
    public void popTransform() {
        transformStack.pop();
    }

    @Override
    public boolean hasTransform() {
        return !transformStack.isEmpty();
    }

    // BakedModelConsumer (and the bakedModelConsumer() method on RenderContext)
    // are @Deprecated(forRemoval = true) in Fabric API. Java 9+ tracks
    // forRemoval separately from regular deprecation, so we need both
    // suppressions. RenderContext still declares this as an abstract method,
    // so we have to implement it until Fabric removes it from the interface.
    // Replacement per the Fabric javadoc is to call FabricBakedModel.emitBlockQuads
    // directly, which is what BlockQuadEmitter.emitFrapiBlock already does.
    @SuppressWarnings({"deprecation", "removal"})
    @Override
    public BakedModelConsumer bakedModelConsumer() {
        return new BakedModelConsumer() {
            @Override
            public void accept(BakedModel model) {
                accept(model, RemixRenderContext.this.state);
            }

            @Override
            public void accept(BakedModel model, @Nullable BlockState state) {
                if (state == null) state = RemixRenderContext.this.state;

                // Vanilla's per-position model-randomisation seed, matching
                // BlockQuadEmitter.emitBlock. A constant here would put every
                // instance of a multi-variant block on the same variant, which
                // is the bug the seed plumbing exists to fix; this path is
                // deprecated but still reachable, so it has to agree.
                long seed = state.getRenderingSeed(RemixRenderContext.this.pos);
                Random random = Random.create(seed);

                for (Direction dir : Direction.values()) {
                    // Re-seeded per direction, as vanilla does and as the loops
                    // in BlockQuadEmitter.emitVanillaBlock do.
                    random.setSeed(seed);
                    for (BakedQuad quad : model.getQuads(state, dir, random)) {
                        emitter.fromVanilla(quad, null, dir);
                        emitter.emit();
                    }
                }
                random.setSeed(seed);
                for (BakedQuad quad : model.getQuads(state, null, random)) {
                    emitter.fromVanilla(quad, null, null);
                    emitter.emit();
                }
            }
        };
    }

    /**
     * Complete {@link QuadEmitter} that stores quad data in flat arrays
     * and captures on {@link #emit()}.
     *
     * <p>All setters return {@code QuadEmitter} (covariant with interface).
     */
    final class CapturingQuadEmitter implements QuadEmitter {

        private final float[] posData = new float[12];
        private final float[] uvData  = new float[8];
        private final float[] normData = new float[12];
        private final boolean[] normSet = new boolean[4];
        private final int[] colors = new int[4];
        private final int[] lightmaps = new int[4];

        private Direction cullFaceVal;
        private Direction nominalFaceVal;
        private RenderMaterial materialVal;
        private int colorIndexVal = -1;
        private int tagVal;

        private Sprite bakedSprite;
        private final Vector3f faceNormalCache = new Vector3f();
        private boolean faceNormalDirty = true;

        void clear() {
            for (int i = 0; i < 4; i++) {
                colors[i] = -1; // 0xFFFFFFFF
                lightmaps[i] = 0;
                normSet[i] = false;
            }
            Arrays.fill(posData, 0);
            Arrays.fill(uvData, 0);
            Arrays.fill(normData, 0);
            cullFaceVal = null;
            nominalFaceVal = null;
            materialVal = null;
            colorIndexVal = -1;
            tagVal = 0;
            bakedSprite = null;
            faceNormalDirty = true;
        }

        @Override
        public QuadEmitter emit() {
            if (!transformStack.isEmpty()) {
                for (var it = transformStack.iterator(); it.hasNext(); ) {
                    if (!it.next().transform(this)) {
                        clear();
                        return this;
                    }
                }
            }

            Direction cull = cullFace();
            if (cull != null && isFaceCulled(cull)) {
                clear();
                return this;
            }

            float[] capturedPos = posData.clone();
            float[] capturedUv = uvData.clone();

            Vector3f fn = faceNormal();
            float nx = fn.x(), ny = fn.y(), nz = fn.z();

            int capturedColor = colors[0];

            Identifier spriteId = null;
            float su0 = 0, sv0 = 0, su1 = 1, sv1 = 1;
            if (bakedSprite != null) {
                spriteId = bakedSprite.getContents().getId();
                su0 = bakedSprite.getMinU();
                sv0 = bakedSprite.getMinV();
                su1 = bakedSprite.getMaxU();
                sv1 = bakedSprite.getMaxV();
            }

            captured.add(new SectionBlockData.BlockQuad(
                capturedPos, capturedUv,
                nx, ny, nz,
                capturedColor,
                spriteId,
                su0, sv0, su1, sv1,
                "solid",
                false, false
            ));

            clear();
            return this;
        }

        @Override
        public QuadEmitter square(Direction nominalFace, float left, float bottom,
                                   float right, float top, float depth) {
            nominalFace(nominalFace);
            switch (nominalFace) {
                case UP -> {
                    pos(0, left, 1 - depth, bottom);
                    pos(1, left, 1 - depth, top);
                    pos(2, right, 1 - depth, top);
                    pos(3, right, 1 - depth, bottom);
                }
                case DOWN -> {
                    pos(0, left, depth, top);
                    pos(1, left, depth, bottom);
                    pos(2, right, depth, bottom);
                    pos(3, right, depth, top);
                }
                case NORTH -> {
                    pos(0, 1 - left, top, depth);
                    pos(1, 1 - left, bottom, depth);
                    pos(2, 1 - right, bottom, depth);
                    pos(3, 1 - right, top, depth);
                }
                case SOUTH -> {
                    pos(0, left, top, 1 - depth);
                    pos(1, left, bottom, 1 - depth);
                    pos(2, right, bottom, 1 - depth);
                    pos(3, right, top, 1 - depth);
                }
                case EAST -> {
                    pos(0, 1 - depth, top, 1 - left);
                    pos(1, 1 - depth, bottom, 1 - left);
                    pos(2, 1 - depth, bottom, 1 - right);
                    pos(3, 1 - depth, top, 1 - right);
                }
                case WEST -> {
                    pos(0, depth, top, left);
                    pos(1, depth, bottom, left);
                    pos(2, depth, bottom, right);
                    pos(3, depth, top, right);
                }
            }
            return this;
        }

        // --- Setters (return QuadEmitter per interface contract) ---

        @Override
        public QuadEmitter pos(int vertexIndex, float x, float y, float z) {
            int i = vertexIndex * 3;
            posData[i] = x; posData[i + 1] = y; posData[i + 2] = z;
            faceNormalDirty = true;
            return this;
        }

        @Override
        public QuadEmitter color(int vertexIndex, int color) {
            colors[vertexIndex] = color;
            return this;
        }

        @Override
        public QuadEmitter uv(int vertexIndex, float u, float v) {
            int i = vertexIndex * 2;
            uvData[i] = u; uvData[i + 1] = v;
            return this;
        }

        @Override
        public QuadEmitter lightmap(int vertexIndex, int lightmap) {
            lightmaps[vertexIndex] = lightmap;
            return this;
        }

        @Override
        public QuadEmitter normal(int vertexIndex, float x, float y, float z) {
            int i = vertexIndex * 3;
            normData[i] = x; normData[i + 1] = y; normData[i + 2] = z;
            normSet[vertexIndex] = true;
            return this;
        }

        @Override
        public QuadEmitter cullFace(@Nullable Direction face) {
            this.cullFaceVal = face;
            return this;
        }

        @Override
        public QuadEmitter nominalFace(@Nullable Direction face) {
            this.nominalFaceVal = face;
            return this;
        }

        @Override
        public QuadEmitter material(RenderMaterial material) {
            this.materialVal = material;
            return this;
        }

        @Override
        public QuadEmitter colorIndex(int colorIndex) {
            this.colorIndexVal = colorIndex;
            return this;
        }

        @Override
        public QuadEmitter tag(int tag) {
            this.tagVal = tag;
            return this;
        }

        @Override
        public QuadEmitter spriteBake(Sprite sprite, int bakeFlags) {
            this.bakedSprite = sprite;

            float sprMinU = sprite.getMinU();
            float sprMaxU = sprite.getMaxU();
            float sprMinV = sprite.getMinV();
            float sprMaxV = sprite.getMaxV();

            boolean lockUv    = (bakeFlags & MutableQuadView.BAKE_LOCK_UV)    != 0;
            boolean flipU     = (bakeFlags & MutableQuadView.BAKE_FLIP_U)     != 0;
            boolean flipV     = (bakeFlags & MutableQuadView.BAKE_FLIP_V)     != 0;
            boolean normalized = (bakeFlags & MutableQuadView.BAKE_NORMALIZED) != 0;
            int rotation = bakeFlags & 6;

            if (lockUv) {
                Direction face = nominalFaceVal != null ? nominalFaceVal : lightFace();
                for (int v = 0; v < 4; v++) {
                    float rawU, rawV;
                    float px = posData[v * 3], py = posData[v * 3 + 1], pz = posData[v * 3 + 2];
                    switch (face) {
                        case UP, DOWN -> { rawU = px; rawV = pz; }
                        case NORTH    -> { rawU = 1 - px; rawV = 1 - py; }
                        case SOUTH    -> { rawU = px; rawV = 1 - py; }
                        case EAST     -> { rawU = 1 - pz; rawV = 1 - py; }
                        case WEST     -> { rawU = pz; rawV = 1 - py; }
                        default       -> { rawU = 0; rawV = 0; }
                    }
                    if (normalized) { rawU *= 16f; rawV *= 16f; }
                    uvData[v * 2] = rawU;
                    uvData[v * 2 + 1] = rawV;
                }
            } else if (normalized) {
                for (int v = 0; v < 4; v++) {
                    uvData[v * 2] /= 16f;
                    uvData[v * 2 + 1] /= 16f;
                }
            }

            if (rotation != MutableQuadView.BAKE_ROTATE_NONE) {
                for (int v = 0; v < 4; v++) {
                    float u = uvData[v * 2], vv = uvData[v * 2 + 1];
                    float du = u - 0.5f, dv = vv - 0.5f;
                    switch (rotation) {
                        case MutableQuadView.BAKE_ROTATE_90  -> { uvData[v * 2] = 0.5f + dv; uvData[v * 2 + 1] = 0.5f - du; }
                        case MutableQuadView.BAKE_ROTATE_180 -> { uvData[v * 2] = 0.5f - du; uvData[v * 2 + 1] = 0.5f - dv; }
                        case MutableQuadView.BAKE_ROTATE_270 -> { uvData[v * 2] = 0.5f - dv; uvData[v * 2 + 1] = 0.5f + du; }
                    }
                }
            }

            for (int v = 0; v < 4; v++) {
                float u = uvData[v * 2], vv = uvData[v * 2 + 1];
                if (flipU) u = 1 - u;
                if (flipV) vv = 1 - vv;
                uvData[v * 2]     = sprMinU + u * (sprMaxU - sprMinU);
                uvData[v * 2 + 1] = sprMinV + vv * (sprMaxV - sprMinV);
            }

            return this;
        }

        @Override
        public QuadEmitter fromVanilla(int[] quadData, int startIndex) {
            for (int v = 0; v < 4; v++) {
                int off = startIndex + v * 8;
                pos(v, Float.intBitsToFloat(quadData[off]),
                       Float.intBitsToFloat(quadData[off + 1]),
                       Float.intBitsToFloat(quadData[off + 2]));
                color(v, quadData[off + 3]);
                uv(v, Float.intBitsToFloat(quadData[off + 4]),
                      Float.intBitsToFloat(quadData[off + 5]));
                lightmap(v, quadData[off + 6]);
                int packed = quadData[off + 7];
                float nx = (byte) (packed & 0xFF) / 127f;
                float ny = (byte) ((packed >> 8) & 0xFF) / 127f;
                float nz = (byte) ((packed >> 16) & 0xFF) / 127f;
                if (nx != 0 || ny != 0 || nz != 0) {
                    normal(v, nx, ny, nz);
                }
            }
            return this;
        }

        @Override
        public QuadEmitter fromVanilla(BakedQuad quad, RenderMaterial material,
                                        @Nullable Direction cullFace) {
            this.cullFaceVal = cullFace;
            this.materialVal = material;
            this.colorIndexVal = quad.getColorIndex();
            this.bakedSprite = quad.getSprite();
            this.nominalFaceVal = quad.getFace();

            int[] vd = quad.getVertexData();
            for (int v = 0; v < 4; v++) {
                int off = v * 8;
                pos(v, Float.intBitsToFloat(vd[off]),
                       Float.intBitsToFloat(vd[off + 1]),
                       Float.intBitsToFloat(vd[off + 2]));
                color(v, vd[off + 3]);
                uv(v, Float.intBitsToFloat(vd[off + 4]),
                      Float.intBitsToFloat(vd[off + 5]));
                lightmap(v, vd[off + 6]);
                int packed = vd[off + 7];
                float nx = (byte) (packed & 0xFF) / 127f;
                float ny = (byte) ((packed >> 8) & 0xFF) / 127f;
                float nz = (byte) ((packed >> 16) & 0xFF) / 127f;
                if (nx != 0 || ny != 0 || nz != 0) {
                    normal(v, nx, ny, nz);
                }
            }
            return this;
        }

        @Override
        public QuadEmitter copyFrom(QuadView source) {
            for (int v = 0; v < 4; v++) {
                pos(v, source.x(v), source.y(v), source.z(v));
                color(v, source.color(v));
                uv(v, source.u(v), source.v(v));
                lightmap(v, source.lightmap(v));
                if (source.hasNormal(v)) {
                    normal(v, source.normalX(v), source.normalY(v), source.normalZ(v));
                } else {
                    normSet[v] = false;
                    int i = v * 3;
                    normData[i] = 0; normData[i + 1] = 0; normData[i + 2] = 0;
                }
            }
            cullFace(source.cullFace());
            nominalFace(source.nominalFace());
            material(source.material());
            colorIndex(source.colorIndex());
            tag(source.tag());
            return this;
        }

        // --- QuadView getters ---

        @Override public float x(int vertexIndex) { return posData[vertexIndex * 3]; }
        @Override public float y(int vertexIndex) { return posData[vertexIndex * 3 + 1]; }
        @Override public float z(int vertexIndex) { return posData[vertexIndex * 3 + 2]; }

        @Override
        public float posByIndex(int vertexIndex, int coordinateIndex) {
            return posData[vertexIndex * 3 + coordinateIndex];
        }

        @Override
        public Vector3f copyPos(int vertexIndex, @Nullable Vector3f target) {
            if (target == null) target = new Vector3f();
            int i = vertexIndex * 3;
            target.set(posData[i], posData[i + 1], posData[i + 2]);
            return target;
        }

        @Override public int color(int vertexIndex) { return colors[vertexIndex]; }

        @Override public float u(int vertexIndex) { return uvData[vertexIndex * 2]; }
        @Override public float v(int vertexIndex) { return uvData[vertexIndex * 2 + 1]; }

        @Override
        public Vector2f copyUv(int vertexIndex, @Nullable Vector2f target) {
            if (target == null) target = new Vector2f();
            target.set(uvData[vertexIndex * 2], uvData[vertexIndex * 2 + 1]);
            return target;
        }

        @Override public int lightmap(int vertexIndex) { return lightmaps[vertexIndex]; }
        @Override public boolean hasNormal(int vertexIndex) { return normSet[vertexIndex]; }

        @Override
        public float normalX(int vertexIndex) {
            return normSet[vertexIndex] ? normData[vertexIndex * 3] : Float.NaN;
        }

        @Override
        public float normalY(int vertexIndex) {
            return normSet[vertexIndex] ? normData[vertexIndex * 3 + 1] : Float.NaN;
        }

        @Override
        public float normalZ(int vertexIndex) {
            return normSet[vertexIndex] ? normData[vertexIndex * 3 + 2] : Float.NaN;
        }

        @Override
        public @Nullable Vector3f copyNormal(int vertexIndex, @Nullable Vector3f target) {
            if (!normSet[vertexIndex]) return null;
            if (target == null) target = new Vector3f();
            int i = vertexIndex * 3;
            target.set(normData[i], normData[i + 1], normData[i + 2]);
            return target;
        }

        @Override public @Nullable Direction cullFace() { return cullFaceVal; }

        @Override
        public Direction lightFace() {
            if (nominalFaceVal != null) return nominalFaceVal;
            return Direction.getFacing(faceNormal().x(), faceNormal().y(), faceNormal().z());
        }

        @Override public @Nullable Direction nominalFace() { return nominalFaceVal; }

        @Override
        public Vector3f faceNormal() {
            if (faceNormalDirty) {
                float x0 = posData[0], y0 = posData[1], z0 = posData[2];
                float x1 = posData[3], y1 = posData[4], z1 = posData[5];
                float x2 = posData[6], y2 = posData[7], z2 = posData[8];
                float e1x = x1 - x0, e1y = y1 - y0, e1z = z1 - z0;
                float e2x = x2 - x0, e2y = y2 - y0, e2z = z2 - z0;
                faceNormalCache.set(
                    e1y * e2z - e1z * e2y,
                    e1z * e2x - e1x * e2z,
                    e1x * e2y - e1y * e2x
                ).normalize();
                faceNormalDirty = false;
            }
            return faceNormalCache;
        }

        @Override public RenderMaterial material() { return materialVal; }
        @Override public int colorIndex() { return colorIndexVal; }
        @Override public int tag() { return tagVal; }

        @Override
        public void toVanilla(int[] target, int targetIndex) {
            for (int v = 0; v < 4; v++) {
                int off = targetIndex + v * 8;
                target[off]     = Float.floatToRawIntBits(posData[v * 3]);
                target[off + 1] = Float.floatToRawIntBits(posData[v * 3 + 1]);
                target[off + 2] = Float.floatToRawIntBits(posData[v * 3 + 2]);
                target[off + 3] = colors[v];
                target[off + 4] = Float.floatToRawIntBits(uvData[v * 2]);
                target[off + 5] = Float.floatToRawIntBits(uvData[v * 2 + 1]);
                target[off + 6] = lightmaps[v];
                int nx = (int) (normData[v * 3] * 127) & 0xFF;
                int ny = (int) (normData[v * 3 + 1] * 127) & 0xFF;
                int nz = (int) (normData[v * 3 + 2] * 127) & 0xFF;
                target[off + 7] = nx | (ny << 8) | (nz << 16);
            }
        }

        Sprite getBakedSprite() { return bakedSprite; }
    }
}
