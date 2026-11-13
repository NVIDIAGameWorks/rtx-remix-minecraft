/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import com.mojang.math.Transformation;
import com.nvidia.rtxremix.Float3D;
import com.nvidia.rtxremix.LightInfo;
import com.nvidia.rtxremix.LightInfoDiskEXT;
import com.nvidia.rtxremix.LightInfoLightShaping;
import com.nvidia.rtxremix.LightInfoSphereEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixException;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlock;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlockEntity;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconPose;
import com.nvidia.rtxremix.minecraft.block.SpotLightBlock;
import com.nvidia.rtxremix.minecraft.entity.CopperBeaconAnimationClock;
import com.nvidia.rtxremix.minecraft.entity.RemixFirstPersonPass;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.sky.SkyLightRenderer;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/**
 * Manages entity and sky distant lights for RTX Remix.
 *
 * <p>Static block lights are owned by {@code TerrainState.SectionEntry} and
 * managed by {@code ChunkManager}. This class handles entity lights, articulated
 * Copper Beacon block-entity lights, and the directional sky light (sun/moon).
 *
 * <p>Entity lights are rebuilt every frame rather than cached against a block
 * state, which is what lets a display-entity beacon rotate smoothly; see
 * {@link #ensureDisplayBeaconLight}.
 */
public class LightManager {
    @FunctionalInterface
    public interface HeldLightPositionSource {
        Vec3 position(EquipmentSlot slot);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(LightManager.class);

    private static final float RULELESS_ITEM_RADIANCE_PER_LIGHT_LEVEL = 5.0f;
    private static final float ENTITY_LIGHT_RADIUS = 0.1f;
    private static final float HELD_LIGHT_FORWARD_OFFSET = 0.35f;
    private static final float HELD_LIGHT_SIDE_OFFSET = 0.38f;
    private static final float HELD_LIGHT_HEIGHT_OFFSET = 0.88f;
    private static final BlockPos ITEM_LIGHT_RULE_POS = BlockPos.ZERO;
    private static final float[] ITEM_LIGHT_RULE_CENTROID = {0.5f, 0.5f, 0.5f};
    /** Floor for a display beacon's emitter radius, so a tiny scale cannot collapse it to a point. */
    private static final float MIN_DISPLAY_BEACON_RADIUS = 0.01f;
    /** Below this the display's transform has collapsed an axis and the beam has no direction. */
    private static final float MIN_BEAM_LENGTH_SQ = 1.0e-12f;
    private final RemixAPI remixApi;
    private final BlockTextureManager textures;
    private final HeldLightPositionSource heldLightPositions;
    private final SkyLightRenderer skyLights;

    private final RetainedLightRegistry<EntityLightKey> entityLights =
            RetainedLightRegistry.equalityKeys();
    private final RetainedLightRegistry<CopperBeaconBlockEntity> placedBeaconLights =
            RetainedLightRegistry.identityKeys();
    /** Entities that claimed a light this frame; see {@link #ownsLightThisFrame}. */
    private final IntOpenHashSet lightOwnerIds = new IntOpenHashSet();
    private final HeldLightPlacement heldLightPlacement = new HeldLightPlacement();
    private final FirstPersonShadowModelPlacement shadowModelPlacement =
            new FirstPersonShadowModelPlacement();
    // Scratch for the display-beacon transform. Render thread only, overwritten
    // per entity; keeps a spinning lighthouse from allocating every frame.
    private final Quaternionf scratchBillboard = new Quaternionf();
    private final Vector3f scratchLens = new Vector3f();
    private final Vector3f scratchBeam = new Vector3f();
    private final Vector3f scratchDiskX = new Vector3f();
    private final Vector3f scratchDiskY = new Vector3f();
    private final CopperBeaconPose.Pose scratchPlacedBeaconPose =
            new CopperBeaconPose.Pose();
    private final CopperBeaconPose.Pose scratchDisplayBeaconPose =
            new CopperBeaconPose.Pose();
    private Vec3 firstPersonShadowModelOffset = Vec3.ZERO;
    private long lastEntityLightUpdateNanos;

    public LightManager(
            RemixAPI remixApi,
            BlockTextureManager textures,
            HeldLightPositionSource heldLightPositions,
            SkyLightRenderer skyLights) {
        this.remixApi = Objects.requireNonNull(remixApi, "remixApi");
        this.textures = Objects.requireNonNull(textures, "textures");
        this.heldLightPositions = Objects.requireNonNull(
                heldLightPositions, "heldLightPositions");
        this.skyLights = Objects.requireNonNull(skyLights, "skyLights");
        LOGGER.info("Light manager initialized");
    }

    public Vec3 getFirstPersonShadowModelOffset() {
        return firstPersonShadowModelOffset;
    }

    // ================================================================
    //  Per-frame entry point
    // ================================================================

    /**
     * Updates sky light and draws entity + sky lights.
     */
    public void updateLights(ClientLevel world) {
        if (world == null) return;
        updateSkyLight(world);
        drawActiveLights();
    }

    // ================================================================
    //  Entity lights (per-frame)
    // ================================================================

    /**
     * Updates lights for light-emitting entities: held and dropped block items,
     * plus beacon beams carried by {@code block_display} entities.
     *
     * <p>Every loaded entity is considered, with no distance cull. That is not an
     * oversight to tidy up later: a display beacon is a lighthouse, and being
     * visible from far outside any sensible entity-light range is the whole point
     * of it. A cull added here would have to exempt the beacon paths.
     *
     * @param camera current render camera used to place local first-person item lights
     * @param tickDelta partial tick, used to match the display-entity renderer's
     *                  interpolated rotation so a spinning beam does not lag its
     *                  own housing
     */
    public void updateEntityLights(ClientLevel world, Camera camera, float tickDelta) {
        if (world == null || camera == null) return;

        entityLights.beginFrame();
        placedBeaconLights.beginFrame();
        lightOwnerIds.clear();
        float frameDtSeconds = entityLightFrameDtSeconds();
        boolean localFirstPersonRigUpdated = false;

        for (Entity entity : world.entitiesForRendering()) {
            if (entity instanceof ItemEntity itemEntity) {
                EntityLightSpec spec = itemLightSpec(itemEntity.getItem());
                if (spec != null) {
                    ensureEntityLight(
                            entity, spec, null, EntityLightKey.item(entity.getId()));
                }
            } else if (entity instanceof LivingEntity living) {
                if (isLocalFirstPersonPlayer(living)) {
                    updateLocalFirstPersonLights(
                            world, camera, living, tickDelta, frameDtSeconds);
                    localFirstPersonRigUpdated = true;
                } else {
                    updateHandLight(living, EquipmentSlot.MAINHAND,
                            EntityLightKey.mainHand(entity.getId()), tickDelta);
                    updateHandLight(living, EquipmentSlot.OFFHAND,
                            EntityLightKey.offHand(entity.getId()), tickDelta);
                }
            } else if (entity instanceof Display.BlockDisplay display) {
                ensureDisplayBeaconLight(display, tickDelta);
            }
        }

        double beaconAnimationTick = CopperBeaconAnimationClock.sample(world, tickDelta);
        for (CopperBeaconBlockEntity beacon : CopperBeaconBlockEntity.loaded()) {
            if (!beacon.isRemoved() && beacon.getLevel() == world) {
                ensurePlacedBeaconLight(beacon, beaconAnimationTick);
            }
        }

        if (!localFirstPersonRigUpdated) {
            heldLightPlacement.clear();
            shadowModelPlacement.clear();
            firstPersonShadowModelOffset = Vec3.ZERO;
        }

        entityLights.endFrame();
        placedBeaconLights.endFrame();
    }

    /**
     * Whether {@code entityId} claimed a light in this frame's {@link #updateEntityLights}.
     * The entity pass keeps such entities in the scene at any distance: their light already
     * reaches it from wherever they stand.
     */
    public boolean ownsLightThisFrame(int entityId) {
        return lightOwnerIds.contains(entityId);
    }

    private void ensurePlacedBeaconLight(
            CopperBeaconBlockEntity beacon, double animationTick) {
        if (!beacon.isActive() || beacon.getIntensityMultiplier() <= 0.0f) {
            return;
        }

        BlockState state = beacon.getBlockState();
        int luminance = state.getLightEmission();
        if (luminance <= 0) {
            return;
        }

        CopperBeaconPose.evaluate(
                state.getValue(CopperBeaconBlock.MOUNT),
                state.getValue(BlockStateProperties.FACING),
                beacon.yawAngleAt(animationTick),
                beacon.pitchAngleAt(animationTick),
                scratchPlacedBeaconPose);
        Vector3f direction = scratchPlacedBeaconPose.direction();
        Vector3f emitter = scratchPlacedBeaconPose.emitter();
        Vector3f xAxis = scratchPlacedBeaconPose.xAxis();
        Vector3f yAxis = scratchPlacedBeaconPose.yAxis();
        BlockPos pos = beacon.getBlockPos();

        float red = CopperBeaconBlock.BEAM_COLOR.r();
        float green = CopperBeaconBlock.BEAM_COLOR.g();
        float blue = CopperBeaconBlock.BEAM_COLOR.b();
        if (beacon.getDye().getItem() instanceof DyeItem) {
            int color = beacon.getLensTint();
            red = ((color >>> 16) & 0xFF) / 255.0f;
            green = ((color >>> 8) & 0xFF) / 255.0f;
            blue = (color & 0xFF) / 255.0f;
        }

        float intensity = BeaconBeam.intensity(luminance)
                * beacon.getIntensityMultiplier();
        LightInfoLightShaping shaping = new LightInfoLightShaping();
        shaping.direction = new Float3D(direction.x(), direction.y(), direction.z());
        shaping.coneAngleDegrees = beacon.getConeAngle();
        shaping.coneSoftness = BeaconBeam.CONE_SOFTNESS;
        shaping.focusExponent = BeaconBeam.FOCUS_EXPONENT;

        try {
            LightInfo info = LightInfo.builder()
                    .radiance(red * intensity, green * intensity, blue * intensity)
                    .diskExt(LightInfoDiskEXT.builder()
                            .position(
                                    pos.getX() + emitter.x(),
                                    pos.getY() + emitter.y(),
                                    pos.getZ() + emitter.z())
                            .xAxis(xAxis.x(), xAxis.y(), xAxis.z())
                            .xRadius(BeaconBeam.RADIUS)
                            .yAxis(yAxis.x(), yAxis.y(), yAxis.z())
                            .yRadius(BeaconBeam.RADIUS)
                            .direction(direction.x(), direction.y(), direction.z())
                            .shaping(shaping)
                            .volumetricRadianceScale(BeaconBeam.VOLUMETRIC_SCALE)
                            .build())
                    .build();
            placedBeaconLights.touch(beacon, info);
        } catch (RemixException e) {
            LOGGER.error(
                    "Failed to update Copper Beacon light at {}: {}",
                    beacon.getBlockPos(),
                    e.getMessage());
        }
    }

    /**
     * The light for one hand.
     *
     * <p>A {@link SpotLightBlock} takes the shaped-beam path and every other
     * light-emitting block item takes the plain omnidirectional one. They share a
     * light key, so the beam path claiming it is what keeps the two exclusive;
     * {@link OverridesItemLight} is a second line of defence here rather than the
     * mechanism, since {@link #ensureHeldBeaconLight} claims every beacon stack
     * before {@link #itemLightSpec} is ever consulted. It is the load-bearing
     * guard on the <em>dropped</em>-item path, which has no such claim.
     */
    private void updateHandLight(LivingEntity holder, EquipmentSlot slot,
                                 EntityLightKey key, float tickDelta) {
        ItemStack stack = holder.getItemBySlot(slot);
        // Leaving the key unseen is what puts a light out: the sweep in
        // updateEntityLights destroys anything not claimed this frame.
        if (!HeldLightState.isLit(holder, stack)) return;
        if (ensureHeldBeaconLight(holder, stack, slot, key, tickDelta)) return;

        EntityLightSpec spec = itemLightSpec(stack);
        if (spec != null) {
            ensureEntityLight(holder, spec, slot, key);
        }
    }

    private void updateLocalFirstPersonLights(
            ClientLevel world, Camera camera, LivingEntity player,
            float tickDelta, float frameDtSeconds) {
        List<PendingAnalyticLight> pending = new ArrayList<>(2);
        collectLocalFirstPersonLight(
                world, camera, player, EquipmentSlot.MAINHAND,
                EntityLightKey.mainHand(player.getId()), tickDelta, frameDtSeconds, pending);
        collectLocalFirstPersonLight(
                world, camera, player, EquipmentSlot.OFFHAND,
                EntityLightKey.offHand(player.getId()), tickDelta, frameDtSeconds, pending);

        List<FirstPersonShadowModelPlacement.LightConstraint> constraints =
                new ArrayList<>(pending.size());
        for (PendingAnalyticLight light : pending) {
            constraints.add(new FirstPersonShadowModelPlacement.LightConstraint(
                    light.position, light.emitterRadius, light.shadowClearance));
        }

        firstPersonShadowModelOffset = shadowModelPlacement.resolve(
                world, camera, player, constraints, frameDtSeconds);
        Vec3 displacedHeadCenter = camera.getPosition().add(firstPersonShadowModelOffset);

        // The rig lights surfaces but never the air. A first-person emitter is
        // locked to the camera, and the volumetric grid reuses the previous
        // frame's radiance as if lighting were world-stable, so a light that
        // moves with the view smears into outlines around distant geometry,
        // worst in rain. World-space held lights keep their scattering.
        for (int index = 0; index < pending.size(); index++) {
            PendingAnalyticLight light = pending.get(index).withoutVolumetricContribution();
            submitEntityLight(
                    light,
                    FirstPersonShadowModelPlacement.clearanceRadianceScale(
                            displacedHeadCenter, constraints.get(index)));
        }
    }

    private void collectLocalFirstPersonLight(
            ClientLevel world, Camera camera, LivingEntity player,
            EquipmentSlot slot, EntityLightKey key,
            float tickDelta, float frameDtSeconds,
            List<PendingAnalyticLight> pending) {
        ItemStack stack = player.getItemBySlot(slot);
        if (!HeldLightState.isLit(player, stack)) {
            heldLightPlacement.reset(slot);
            return;
        }
        SpotLightBlock beacon = heldBeacon(stack);
        if (beacon != null) {
            heldLightPlacement.reset(slot);
            PendingAnalyticLight light = createPendingHeldBeaconLight(
                    world, camera, player, beacon, slot, key, tickDelta);
            if (light != null) {
                pending.add(light);
            }
            return;
        }

        EntityLightSpec spec = itemLightSpec(stack);
        if (spec == null) {
            heldLightPlacement.reset(slot);
            return;
        }

        float emitterRadius = spec.radius * RemixFirstPersonPass.VIEW_MODEL_SCALE;
        Vec3 position = heldLightPlacement.resolve(
                world,
                camera,
                player,
                slot,
                emitterRadius,
                frameDtSeconds);
        pending.add(createPendingSphereLight(
                key,
                spec,
                position,
                emitterRadius,
                powerPreservingRadianceScale(RemixFirstPersonPass.VIEW_MODEL_SCALE)));
    }

    private void ensureEntityLight(Entity entity, EntityLightSpec spec,
                                   EquipmentSlot lightSlot, EntityLightKey key) {
        ensureSphereEntityLight(key, spec, lightPosition(entity, lightSlot), spec.radius, 1.0f);
    }

    private void ensureSphereEntityLight(
            EntityLightKey key, EntityLightSpec spec, Vec3 position,
            float emitterRadius, float radianceScale) {
        submitEntityLight(
                createPendingSphereLight(key, spec, position, emitterRadius, radianceScale),
                1.0f);
    }

    private static PendingAnalyticLight createPendingSphereLight(
            EntityLightKey key, EntityLightSpec spec, Vec3 position,
            float emitterRadius, float radianceScale) {
        return new PendingAnalyticLight(
                key,
                position,
                emitterRadius,
                FirstPersonShadowModelPlacement.TARGET_LIGHT_GAP,
                spec.color[0] * spec.intensity * radianceScale,
                spec.color[1] * spec.intensity * radianceScale,
                spec.color[2] * spec.intensity * radianceScale,
                spec.volumetricRadianceScale,
                null);
    }

    private void submitEntityLight(
            PendingAnalyticLight light, float radianceScale) {
        lightOwnerIds.add(light.key.entityId());
        try {
            LightInfo.Builder lightBuilder = LightInfo.builder()
                    .radiance(
                            light.radianceR * radianceScale,
                            light.radianceG * radianceScale,
                            light.radianceB * radianceScale);
            if (light.diskBasis != null) {
                DiskBasis basis = light.diskBasis;
                var diskBuilder = LightInfoDiskEXT.builder()
                        .position(
                                (float) light.position.x,
                                (float) light.position.y,
                                (float) light.position.z)
                        .xAxis(
                                (float) basis.xAxis.x,
                                (float) basis.xAxis.y,
                                (float) basis.xAxis.z)
                        .xRadius(light.emitterRadius)
                        .yAxis(
                                (float) basis.yAxis.x,
                                (float) basis.yAxis.y,
                                (float) basis.yAxis.z)
                        .yRadius(light.emitterRadius)
                        .direction(
                                (float) basis.direction.x,
                                (float) basis.direction.y,
                                (float) basis.direction.z)
                        .volumetricRadianceScale(light.volumetricRadianceScale);
                if (light.shaping != null) {
                    diskBuilder.shaping(light.shaping);
                }
                putLight(light.key, lightBuilder.diskExt(diskBuilder.build()).build());
                return;
            }

            var sphereBuilder = LightInfoSphereEXT.builder()
                    .position(
                            (float) light.position.x,
                            (float) light.position.y,
                            (float) light.position.z)
                    .radius(light.emitterRadius)
                    .volumetricRadianceScale(light.volumetricRadianceScale);
            if (light.shaping != null) {
                sphereBuilder.shaping(light.shaping);
            }

            LightInfo info = lightBuilder.sphereExt(sphereBuilder.build()).build();

            putLight(light.key, info);
        } catch (RemixException e) {
            LOGGER.error("Failed to create entity light for {}: {}", light.key, e.getMessage());
        }
    }

    static float powerPreservingRadianceScale(float radiusScale) {
        return 1.0f / (radiusScale * radiusScale);
    }

    /** Creates the light for {@code key}, or updates it in place if it already exists. */
    private void putLight(EntityLightKey key, LightInfo info) throws RemixException {
        entityLights.touch(key, info);
    }

    // ================================================================
    //  Display-entity beacons (the spinning lighthouse)
    // ================================================================

    /**
     * Per-frame beacon beam for a {@code block_display} holding a
     * {@link SpotLightBlock} state.
     *
     * <p>This is what lets a lighthouse sweep. A display entity has a real yaw,
     * so a command block can turn it a few degrees per tick and the beam follows
     * every frame:
     *
     * <pre>{@code
     * /summon block_display ~ ~ ~ {Tags:["lighthouse"],teleport_duration:1,
     *     block_state:{Name:"rtx-remix-renderer:copper_beacon"}}
     * /execute as @e[tag=lighthouse] at @s run tp @s ~ ~ ~ ~5 ~
     * }</pre>
     *
     * <p>Placed articulated beacons use the same per-frame light path, but take
     * their motion from synchronized block-entity state rather than an entity
     * transform.
     *
     * <p>The emitter is placed by running the block-local lens point through the
     * same transform chain {@code DisplayEntityRenderer} uses (the interpolated
     * display transformation, then the entity's yaw/pitch), so the light stays
     * glued to the visible lens under translation, scaling, and rotation.
     *
     * <p>Billboard modes other than {@code FIXED} are deliberately ignored. Those
     * swing the model to face the camera; a light that did the same would be lit
     * correctly for one viewer and wrong for every other.
     */
    private void ensureDisplayBeaconLight(Display.BlockDisplay display, float tickDelta) {
        Display.BlockDisplay.BlockRenderState data = display.blockRenderState();
        if (data == null) return;

        BlockState state = data.blockState();
        if (!(state.getBlock() instanceof SpotLightBlock beacon)) return;

        int luminance = state.getLightEmission();
        if (luminance <= 0) return;

        // Rendering data arrives a tick after the entity spawns; until then there
        // is no transformation to read and the beam would be misplaced.
        Display.RenderState renderState = display.renderState();
        if (renderState == null) return;

        EntityLightKey key = EntityLightKey.blockDisplay(display.getId());

        Transformation transform =
                renderState.transformation().get(display.calculateInterpolationProgress(tickDelta));
        Matrix4f matrix = transform.getMatrix();

        // Matches DisplayEntityRenderer.getBillboardRotation for BillboardMode.FIXED.
        float yaw = Mth.rotLerp(tickDelta, display.yRotO, display.getYRot());
        float pitch = Mth.lerp(tickDelta, display.xRotO, display.getXRot());
        Quaternionf billboard = scratchBillboard.rotationYXZ(
                -Mth.DEG_TO_RAD * yaw,
                Mth.DEG_TO_RAD * pitch,
                0.0f);

        Direction facing = state.getValue(BlockStateProperties.FACING);
        Direction mount = state.getValue(CopperBeaconBlock.MOUNT);
        CopperBeaconPose.evaluate(
                mount, facing, 0.0f, 0.0f, scratchDisplayBeaconPose);

        Vector3f beam = scratchBeam.set(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        matrix.transformDirection(beam);
        billboard.transform(beam);
        if (beam.lengthSquared() < MIN_BEAM_LENGTH_SQ) return;
        beam.normalize();

        // A block_display renders its model in [0,1]^3 with the minimum corner at
        // the entity position, so the lens point is block-local and needs the
        // entity's own position added back after the transform chain.
        Vector3f lens = scratchLens.set(
                BeaconBeam.lensLocal(mount.getStepX(), facing.getStepX()),
                BeaconBeam.lensLocal(mount.getStepY(), facing.getStepY()),
                BeaconBeam.lensLocal(mount.getStepZ(), facing.getStepZ()));
        matrix.transformPosition(lens);
        billboard.transform(lens);
        Vec3 origin = display.getPosition(tickDelta);

        Vector3f diskX = scratchDiskX.set(scratchDisplayBeaconPose.xAxis());
        matrix.transformDirection(diskX);
        billboard.transform(diskX);
        float xRadius = BeaconBeam.RADIUS * diskX.length();
        if (xRadius < MIN_DISPLAY_BEACON_RADIUS) return;
        diskX.normalize();

        Vector3f diskY = scratchDiskY.set(scratchDisplayBeaconPose.yAxis());
        matrix.transformDirection(diskY);
        billboard.transform(diskY);
        diskY.fma(-diskY.dot(diskX), diskX);
        float yRadius = BeaconBeam.RADIUS * diskY.length();
        if (yRadius < MIN_DISPLAY_BEACON_RADIUS) return;
        diskY.normalize();

        LightEntry.ColorOverride color = beacon.beamColor();
        // The sweeping block_display lighthouse is tuned independently and sits
        // outside the placed/held Copper Beacon user control.
        float intensity = BeaconBeam.displayIntensity(luminance);
        float r = color.hasColor() ? color.r() : 1.0f;
        float g = color.hasColor() ? color.g() : 1.0f;
        float b = color.hasColor() ? color.b() : 1.0f;

        LightInfoLightShaping shaping = new LightInfoLightShaping();
        shaping.direction = new Float3D(beam.x(), beam.y(), beam.z());
        shaping.coneAngleDegrees = BeaconBeam.CONE_ANGLE_DEGREES;
        shaping.coneSoftness = BeaconBeam.CONE_SOFTNESS;
        shaping.focusExponent = BeaconBeam.FOCUS_EXPONENT;

        // Claimed only once the beam is known good: a frame that bails out above
        // leaves the key unseen, so the sweep cleanup below drops a stale light
        // instead of freezing it at its last direction.
        lightOwnerIds.add(key.entityId());

        try {
            LightInfo info = LightInfo.builder()
                    .radiance(r * intensity, g * intensity, b * intensity)
                    .diskExt(LightInfoDiskEXT.builder()
                            .position(
                                    (float) (origin.x + lens.x()),
                                    (float) (origin.y + lens.y()),
                                    (float) (origin.z + lens.z()))
                            .xAxis(diskX.x(), diskX.y(), diskX.z())
                            .xRadius(xRadius)
                            .yAxis(diskY.x(), diskY.y(), diskY.z())
                            .yRadius(yRadius)
                            .direction(beam.x(), beam.y(), beam.z())
                            .shaping(shaping)
                            .volumetricRadianceScale(BeaconBeam.DISPLAY_VOLUMETRIC_SCALE)
                            .build())
                    .build();

            putLight(key, info);
        } catch (RemixException e) {
            LOGGER.error("Failed to create display beacon light for {}: {}", key, e.getMessage());
        }
    }

    /**
     * Per-frame beam for a {@link SpotLightBlock} held in an entity's hand,
     * aimed along the holder's look direction.
     *
     * <p>Base cone shape and colour are shared with the placed and
     * {@code block_display} paths so a beacon reads as the same instrument
     * wherever it is. A local first-person beam widens near obstructing geometry.
     * Radiance and god-ray strength are path-specific, via
     * {@link BeaconBeam#HELD_INTENSITY_SCALE} and
     * {@link BeaconBeam#HELD_VOLUMETRIC_SCALE}; the local first-person beam
     * lights surfaces only, since {@link #updateLocalFirstPersonLights} strips
     * volumetric contribution from every light on the rig.
     *
     * <p>{@link OverridesItemLight} still suppresses the ordinary item light for
     * these blocks, so this is the only analytic light a held beacon emits. Its
     * authored lens material remains emissive. A <em>dropped</em> beacon has no
     * analytic light because an {@link ItemEntity} has no aim worth pointing a
     * beam along.
     *
     * <p>Whether the beam is lit at all is decided before this path is reached,
     * by {@link HeldLightState}: the "Toggle Held Lights" key puts out every
     * light in the local player's hands, and that toggle is client state with
     * no packet behind it, so a beacon in someone else's hand stays lit.
     *
     * @return true if the stack is a beacon, in which case this path owns
     *         {@code key} and the generic item light must not run, whether or
     *         not a beam was actually emitted
     */
    private boolean ensureHeldBeaconLight(LivingEntity holder, ItemStack stack, EquipmentSlot slot,
                                          EntityLightKey key, float tickDelta) {
        SpotLightBlock beacon = heldBeacon(stack);
        if (beacon == null) {
            return false;
        }

        // Leaving the key unseen is what puts the beam out: the sweep in
        // updateEntityLights destroys anything not claimed this frame rather
        // than freezing it at its last direction.

        PendingAnalyticLight light = createPendingHeldBeaconLight(
                null, null, holder, beacon, slot, key, tickDelta);
        if (light != null) {
            submitEntityLight(light, 1.0f);
        }
        return true;
    }

    private static SpotLightBlock heldBeacon(ItemStack stack) {
        if (stack == null || stack.isEmpty()
                || !(stack.getItem() instanceof BlockItem blockItem)
                || !(blockItem.getBlock() instanceof SpotLightBlock beacon)) {
            return null;
        }
        return beacon;
    }

    private PendingAnalyticLight createPendingHeldBeaconLight(
            ClientLevel collisionWorld, Camera collisionCamera, LivingEntity holder,
            SpotLightBlock beacon, EquipmentSlot slot,
            EntityLightKey key, float tickDelta) {
        int luminance = beacon.defaultBlockState().getLightEmission();
        if (luminance <= 0) return null;

        // Unit by construction, but only to the precision of MathHelper's sine
        // table, so it still gets normalized before it becomes a cone axis. No
        // degenerate-length guard here, unlike the display path: a look vector
        // cannot collapse the way a display transform can.
        Vec3 look = holder.getViewVector(tickDelta).normalize();

        boolean localFirstPerson = isLocalFirstPersonPlayer(holder);
        Vec3 capturedPosition = localFirstPerson
                ? heldLightPositions.position(slot)
                : null;
        float viewModelScale = capturedPosition != null
                ? RemixFirstPersonPass.VIEW_MODEL_SCALE
                : 1.0f;
        Vec3 handPosition = capturedPosition != null
                ? capturedPosition
                : lightPosition(holder, slot);
        Vec3 lensOffsetDirection = localFirstPerson
                ? HeldLightPlacement.horizontalForward(holder.getYRot())
                : look;
        float emitterRadius = BeaconBeam.RADIUS * viewModelScale;
        Vec3 pos = handPosition.add(
                lensOffsetDirection.scale(BeaconBeam.HELD_LENS_OFFSET));
        float coneAngle = BeaconBeam.CONE_ANGLE_DEGREES;
        if (localFirstPerson && collisionWorld != null && collisionCamera != null) {
            Vec3 collisionOrigin = collisionCamera.getPosition();
            Vec3 preferredOffset = pos.subtract(collisionOrigin);
            double preferredDistance = preferredOffset.length();
            if (preferredDistance > 1.0e-6) {
                Vec3 collisionDirection = preferredOffset.scale(1.0 / preferredDistance);
                double collisionLimitedDistance = HeldLightPlacement.collisionLimitedDistance(
                        collisionWorld,
                        holder,
                        collisionOrigin,
                        collisionDirection,
                        preferredDistance,
                        emitterRadius + HeldLightPlacement.WALL_CLEARANCE);
                pos = collisionOrigin.add(
                        collisionDirection.scale(collisionLimitedDistance));
                coneAngle = BeaconBeam.heldConeAngle(
                        collisionLimitedDistance / preferredDistance);
            }
        }

        LightEntry.ColorOverride color = beacon.beamColor();
        float intensity = BeaconBeam.heldIntensity(luminance)
                * BeaconBeam.heldConeRadianceScale(coneAngle)
                * powerPreservingRadianceScale(viewModelScale);
        float r = color.hasColor() ? color.r() : 1.0f;
        float g = color.hasColor() ? color.g() : 1.0f;
        float b = color.hasColor() ? color.b() : 1.0f;

        LightInfoLightShaping shaping = new LightInfoLightShaping();
        shaping.direction = new Float3D((float) look.x, (float) look.y, (float) look.z);
        shaping.coneAngleDegrees = coneAngle;
        shaping.coneSoftness = BeaconBeam.CONE_SOFTNESS;
        shaping.focusExponent = BeaconBeam.FOCUS_EXPONENT;

        float yawRadians = holder.getYRot() * Mth.DEG_TO_RAD;
        Vec3 diskX = new Vec3(
                Mth.cos(yawRadians),
                0.0,
                Mth.sin(yawRadians));
        Vec3 diskY = look.cross(diskX).normalize();
        return new PendingAnalyticLight(
                key,
                pos,
                emitterRadius,
                BeaconBeam.HELD_SHADOW_CLEARANCE,
                r * intensity,
                g * intensity,
                b * intensity,
                BeaconBeam.HELD_VOLUMETRIC_SCALE,
                shaping,
                new DiskBasis(diskX, diskY, look));
    }

    private Vec3 lightPosition(Entity entity, EquipmentSlot lightSlot) {
        if (lightSlot != null && entity instanceof LivingEntity living) {
            float yawRad = living.getVisualRotationYInDegrees() * Mth.DEG_TO_RAD;
            double forwardX = -Mth.sin(yawRad);
            double forwardZ = Mth.cos(yawRad);
            double rightX = forwardZ;
            double rightZ = -forwardX;
            double sideSign = handSideSign(living, lightSlot);
            return entity.position().add(
                    forwardX * HELD_LIGHT_FORWARD_OFFSET + rightX * sideSign * HELD_LIGHT_SIDE_OFFSET,
                    HELD_LIGHT_HEIGHT_OFFSET,
                    forwardZ * HELD_LIGHT_FORWARD_OFFSET + rightZ * sideSign * HELD_LIGHT_SIDE_OFFSET);
        }

        return entity.position().add(0, entity.getBbHeight() * 0.75, 0);
    }

    private static boolean isLocalFirstPersonPlayer(LivingEntity holder) {
        Minecraft client = Minecraft.getInstance();
        return client != null
                && holder == client.player
                && client.options.getCameraType().isFirstPerson();
    }

    private float entityLightFrameDtSeconds() {
        long now = System.nanoTime();
        long previous = lastEntityLightUpdateNanos;
        lastEntityLightUpdateNanos = now;
        return previous == 0L ? 0.0f : (now - previous) / 1.0e9f;
    }

    private static double handSideSign(LivingEntity living, EquipmentSlot slot) {
        boolean mainHandIsRight = living.getMainArm() == HumanoidArm.RIGHT;
        boolean selectedHandIsMain = slot == EquipmentSlot.MAINHAND;
        return mainHandIsRight == selectedHandIsMain ? -1.0 : 1.0;
    }

    private EntityLightSpec itemLightSpec(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem bi)) {
            return null;
        }

        Block block = bi.getBlock();
        // Skips both the rule-derived light below and the luminance fallback at
        // the end of this method; see OverridesItemLight.
        if (block instanceof OverridesItemLight) {
            return null;
        }

        BlockState state = block.defaultBlockState();
        int luminance = state.getLightEmission();
        if (luminance <= 0) {
            return null;
        }

        float[] fallbackColor = itemLightColor(block);
        EntityLightSpec best = null;
        for (LightEntry entry : BlockLightRuleManager.resolve(state, ITEM_LIGHT_RULE_POS,
                BlockLightRuleManager.fixedCentroid(ITEM_LIGHT_RULE_CENTROID))) {
            float intensity = effectiveIntensity(entry);
            float[] color = entry.hasColor()
                    ? new float[]{entry.colorR(), entry.colorG(), entry.colorB()}
                    : fallbackColor;
            // The radius floor below is applied after radiance, and a sphere
            // light's power goes as radiance times radius squared, so the two
            // are tuned as a pair: a rule whose authored emitter is smaller
            // than the floor emits more as a held light than it does in the
            // world. Moving the floor moves the whole set of held lights.
            best = EntityLightSpec.brighter(best, new EntityLightSpec(
                    intensity,
                    Math.max(ENTITY_LIGHT_RADIUS, entry.radius()),
                    color,
                    entry.volumetricRadianceScale()));
        }

        if (best != null) {
            return best;
        }

        return new EntityLightSpec(
                luminance * RULELESS_ITEM_RADIANCE_PER_LIGHT_LEVEL,
                ENTITY_LIGHT_RADIUS,
                fallbackColor,
                0.3f);
    }

    private static float effectiveIntensity(LightEntry entry) {
        float baseIntensity = entry.hasIntensity()
                ? entry.intensity()
                : entry.lightLevel() * LightPowerPolicy.RADIANCE_PER_LIGHT_LEVEL;
        baseIntensity *= entry.authoredIntensityMultiplier();
        return BlockLightIntensityControls.apply(
                ResourceLocation.parse(entry.key().ruleId()), baseIntensity);
    }

    private float[] itemLightColor(Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        return textures.getLightColorForBlock(id.getNamespace(), id.getPath());
    }

    record PendingAnalyticLight(
            EntityLightKey key,
            Vec3 position,
            float emitterRadius,
            double shadowClearance,
            float radianceR,
            float radianceG,
            float radianceB,
            float volumetricRadianceScale,
            LightInfoLightShaping shaping,
            DiskBasis diskBasis) {

        PendingAnalyticLight(
                EntityLightKey key,
                Vec3 position,
                float emitterRadius,
                double shadowClearance,
                float radianceR,
                float radianceG,
                float radianceB,
                float volumetricRadianceScale,
                LightInfoLightShaping shaping) {
            this(
                    key,
                    position,
                    emitterRadius,
                    shadowClearance,
                    radianceR,
                    radianceG,
                    radianceB,
                    volumetricRadianceScale,
                    shaping,
                    null);
        }

        /** The same light, absent from fog and rain scattering; surface lighting is unchanged. */
        PendingAnalyticLight withoutVolumetricContribution() {
            return new PendingAnalyticLight(
                    key, position, emitterRadius, shadowClearance,
                    radianceR, radianceG, radianceB, 0.0f, shaping, diskBasis);
        }
    }

    record DiskBasis(Vec3 xAxis, Vec3 yAxis, Vec3 direction) {}

    private record EntityLightSpec(float intensity, float radius, float[] color, float volumetricRadianceScale) {
        private static EntityLightSpec brighter(EntityLightSpec a, EntityLightSpec b) {
            if (a == null) return b;
            if (b == null) return a;
            return b.intensity > a.intensity ? b : a;
        }
    }

    private record EntityLightKey(int entityId, Kind kind) {
        private static EntityLightKey item(int entityId) {
            return new EntityLightKey(entityId, Kind.ITEM);
        }

        private static EntityLightKey mainHand(int entityId) {
            return new EntityLightKey(entityId, Kind.MAIN_HAND);
        }

        private static EntityLightKey offHand(int entityId) {
            return new EntityLightKey(entityId, Kind.OFF_HAND);
        }

        private static EntityLightKey blockDisplay(int entityId) {
            return new EntityLightKey(entityId, Kind.BLOCK_DISPLAY);
        }

    }

    private enum Kind {
        ITEM,
        MAIN_HAND,
        OFF_HAND,
        BLOCK_DISPLAY
    }

    // ================================================================
    //  Sky light
    // ================================================================

    private void updateSkyLight(ClientLevel world) {
        // Only a NORMAL sky has a sun and a moon. The End and the Nether pin their time, so
        // evaluating them would light a permanent noon or midnight that vanilla never draws.
        skyLights.update(
                world.effects().skyType() == DimensionSpecialEffects.SkyType.NORMAL,
                world.getTimeOfDay(0f),
                world.getRainLevel(0.0f),
                world.getThunderLevel(0.0f));
    }

    // ================================================================
    //  Draw + lifecycle
    // ================================================================

    private void drawActiveLights() {
        try {
            skyLights.draw();
            entityLights.draw(remixApi);
            placedBeaconLights.draw(remixApi);
        } catch (RemixException e) {
            LOGGER.error("Failed to draw lights: {}", e.getMessage());
        }
    }

    public int getLightCount() {
        return entityLights.size()
                + placedBeaconLights.size()
                + skyLights.lightCount();
    }

    public void clear() {
        entityLights.clear();
        placedBeaconLights.clear();
        lightOwnerIds.clear();
        heldLightPlacement.clear();
        shadowModelPlacement.clear();
        firstPersonShadowModelOffset = Vec3.ZERO;
        lastEntityLightUpdateNanos = 0L;

        LOGGER.info("Light manager cleared");
    }

}
