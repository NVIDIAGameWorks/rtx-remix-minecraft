/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Applies a render-only offset to the hidden local-player model so first-person
 * analytic lights have clear space around their emitter.
 */
final class FirstPersonShadowModelPlacement {

    static final double HEAD_KEEP_OUT_RADIUS = 0.58;
    static final double TARGET_LIGHT_GAP = 0.39;
    static final double MAX_MODEL_OFFSET = 0.40;
    static final double COLLISION_EPSILON = 1.0e-4;
    static final double SPRING_ANGULAR_FREQUENCY = 14.0;
    static final double TARGET_CLEARANCE_HYSTERESIS = 0.01;

    private static final double DIRECTION_EPSILON_SQ = 1.0e-10;

    private Vec3 offset = Vec3.ZERO;
    private Vec3 velocity = Vec3.ZERO;

    Vec3 resolve(ClientLevel world, Camera camera, Entity player,
                  List<LightConstraint> lights, float dtSeconds) {
        Vec3 headCenter = camera.position();
        Vec3 target = lights.isEmpty()
                ? Vec3.ZERO
                : chooseTargetOffset(world, camera, player, headCenter, lights, offset);

        SpringStep spring = advanceSpring(
                offset,
                velocity,
                target,
                dtSeconds,
                SPRING_ANGULAR_FREQUENCY);
        Vec3 collisionSafeOffset = clampToBlockCollisions(world, player, spring.position);
        boolean constrained = collisionSafeOffset.distanceToSqr(spring.position)
                > DIRECTION_EPSILON_SQ;

        // Clearance is unilateral: a new occlusion constraint pushes the
        // hidden model immediately, while the spring governs its relaxation.
        if (!lights.isEmpty()) {
            GapDeficit springDeficit = gapDeficit(headCenter.add(collisionSafeOffset), lights);
            GapDeficit targetDeficit = gapDeficit(headCenter.add(target), lights);
            if (isBetter(targetDeficit, springDeficit)) {
                collisionSafeOffset = target;
                constrained = true;
            }
        }

        if (constrained) {
            velocity = Vec3.ZERO;
        } else {
            velocity = spring.velocity;
        }
        offset = collisionSafeOffset;
        return offset;
    }

    void clear() {
        offset = Vec3.ZERO;
        velocity = Vec3.ZERO;
    }

    private static Vec3 chooseTargetOffset(
            ClientLevel world, Camera camera, Entity player, Vec3 headCenter,
            List<LightConstraint> lights, Vec3 currentOffset) {
        List<Vec3> directions = new ArrayList<>(lights.size() + 7);
        Vec3 currentDirection = horizontal(currentOffset);
        if (currentDirection.lengthSqr() > DIRECTION_EPSILON_SQ) {
            addUniqueDirection(directions, currentDirection.normalize());
        }

        Vec3 averageAway = Vec3.ZERO;
        for (LightConstraint light : lights) {
            Vec3 away = horizontal(headCenter.subtract(light.position));
            if (away.lengthSqr() > DIRECTION_EPSILON_SQ) {
                away = away.normalize();
                addUniqueDirection(directions, away);
                averageAway = averageAway.add(away);
            }
        }
        if (averageAway.lengthSqr() > DIRECTION_EPSILON_SQ) {
            addUniqueDirection(directions, averageAway.normalize());
        }
        Vec3 backward = HeldLightPlacement.horizontalForward(camera.yRot()).reverse();
        addUniqueDirection(directions, backward);

        // A wall can block the preferred backward displacement without
        // blocking either direction along its face. These alternatives keep
        // the hidden model away from the light through concave corners.
        Vec3 tangent = new Vec3(-backward.z, 0.0, backward.x);
        addUniqueDirection(directions, backward.add(tangent).normalize());
        addUniqueDirection(directions, backward.subtract(tangent).normalize());
        addUniqueDirection(directions, tangent);
        addUniqueDirection(directions, tangent.reverse());

        List<Vec3> candidates = new ArrayList<>(directions.size() + 1);
        candidates.add(clampToBlockCollisions(world, player, currentOffset));
        for (Vec3 direction : directions) {
            double requiredLength = 0.0;
            for (LightConstraint light : lights) {
                double requiredCenterDistance = HEAD_KEEP_OUT_RADIUS
                        + light.emitterRadius + light.targetGap;
                requiredLength = Math.max(
                        requiredLength,
                        requiredTranslation(
                                headCenter.subtract(light.position),
                                direction,
                                requiredCenterDistance));
            }

            Vec3 desired = direction.scale(Math.min(requiredLength, MAX_MODEL_OFFSET));
            candidates.add(clampToBlockCollisions(world, player, desired));
        }
        return selectContinuousCandidate(currentOffset, headCenter, lights, candidates);
    }

    static Vec3 selectContinuousCandidate(
            Vec3 currentOffset, Vec3 headCenter,
            List<LightConstraint> lights, List<Vec3> candidates) {
        GapDeficit optimum = new GapDeficit(
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        for (Vec3 candidate : candidates) {
            GapDeficit deficit = gapDeficit(headCenter.add(candidate), lights);
            if (isBetter(deficit, optimum)) {
                optimum = deficit;
            }
        }

        Vec3 selected = candidates.getFirst();
        double selectedMotion = Double.POSITIVE_INFINITY;
        double totalHysteresis = TARGET_CLEARANCE_HYSTERESIS * lights.size();
        for (Vec3 candidate : candidates) {
            GapDeficit deficit = gapDeficit(headCenter.add(candidate), lights);
            if (deficit.max > optimum.max + TARGET_CLEARANCE_HYSTERESIS
                    || deficit.total > optimum.total + totalHysteresis) {
                continue;
            }

            double motion = candidate.distanceToSqr(currentOffset);
            if (motion < selectedMotion) {
                selected = candidate;
                selectedMotion = motion;
            }
        }
        return selected;
    }

    static double requiredTranslation(
            Vec3 headCenterFromLight, Vec3 direction, double requiredCenterDistance) {
        double distanceSquared = headCenterFromLight.lengthSqr();
        double requiredSquared = requiredCenterDistance * requiredCenterDistance;
        if (distanceSquared >= requiredSquared) {
            return 0.0;
        }
        double projection = headCenterFromLight.dot(direction);
        double discriminant = projection * projection - distanceSquared + requiredSquared;
        return Math.max(0.0, -projection + Math.sqrt(Math.max(0.0, discriminant)));
    }

    static float clearanceRadianceScale(Vec3 displacedHeadCenter, LightConstraint light) {
        double surfaceGap = displacedHeadCenter.distanceTo(light.position)
                - HEAD_KEEP_OUT_RADIUS
                - light.emitterRadius;
        float normalized = (float) Math.clamp(surfaceGap / light.targetGap, 0.0, 1.0);
        return normalized * normalized * (3.0f - 2.0f * normalized);
    }

    static SpringStep advanceSpring(
            Vec3 position, Vec3 velocity, Vec3 target,
            float dtSeconds, double angularFrequency) {
        double dt = Math.clamp(
                dtSeconds, 0.0f, HeldLightPlacement.MAX_FRAME_DT_SECONDS);
        double omega = Math.max(0.0, angularFrequency);
        if (dt == 0.0 || omega == 0.0) {
            return new SpringStep(position, velocity);
        }

        double decay = Math.exp(-omega * dt);
        Vec3 displacement = position.subtract(target);
        Vec3 temporary = velocity.add(displacement.scale(omega)).scale(dt);
        Vec3 nextPosition = target.add(displacement.add(temporary).scale(decay));
        Vec3 nextVelocity = velocity.subtract(temporary.scale(omega)).scale(decay);
        return new SpringStep(nextPosition, nextVelocity);
    }

    private static Vec3 clampToBlockCollisions(
            ClientLevel world, Entity player, Vec3 desiredOffset) {
        if (desiredOffset.lengthSqr() <= DIRECTION_EPSILON_SQ) {
            return Vec3.ZERO;
        }

        AABB modelBounds = player.getBoundingBox().deflate(COLLISION_EPSILON);
        AABB desiredBounds = modelBounds.move(desiredOffset);
        AABB queryBounds = new AABB(
                Math.min(modelBounds.minX, desiredBounds.minX) - COLLISION_EPSILON,
                Math.min(modelBounds.minY, desiredBounds.minY) - COLLISION_EPSILON,
                Math.min(modelBounds.minZ, desiredBounds.minZ) - COLLISION_EPSILON,
                Math.max(modelBounds.maxX, desiredBounds.maxX) + COLLISION_EPSILON,
                Math.max(modelBounds.maxY, desiredBounds.maxY) + COLLISION_EPSILON,
                Math.max(modelBounds.maxZ, desiredBounds.maxZ) + COLLISION_EPSILON);
        List<AABB> nearbyBlocks = new ArrayList<>();
        world.getBlockCollisions(player, queryBounds).forEach(
                shape -> nearbyBlocks.addAll(shape.toAabbs()));
        return slideAgainstBlocks(modelBounds, desiredOffset, nearbyBlocks);
    }

    static Vec3 slideAgainstBlocks(
            AABB modelBounds, Vec3 desiredOffset, List<AABB> nearbyBlocks) {
        double xFirstX = clampX(modelBounds, desiredOffset.x, nearbyBlocks);
        AABB afterX = modelBounds.move(xFirstX, 0.0, 0.0);
        double xFirstZ = clampZ(afterX, desiredOffset.z, nearbyBlocks);
        Vec3 xFirst = new Vec3(xFirstX, desiredOffset.y, xFirstZ);

        double zFirstZ = clampZ(modelBounds, desiredOffset.z, nearbyBlocks);
        AABB afterZ = modelBounds.move(0.0, 0.0, zFirstZ);
        double zFirstX = clampX(afterZ, desiredOffset.x, nearbyBlocks);
        Vec3 zFirst = new Vec3(zFirstX, desiredOffset.y, zFirstZ);

        return horizontalErrorSquared(xFirst, desiredOffset)
                <= horizontalErrorSquared(zFirst, desiredOffset)
                ? xFirst
                : zFirst;
    }

    private static double clampX(AABB bounds, double desiredX, List<AABB> blocks) {
        double allowedX = desiredX;
        for (AABB block : blocks) {
            if (!overlaps(bounds.minY, bounds.maxY, block.minY, block.maxY)
                    || !overlaps(bounds.minZ, bounds.maxZ, block.minZ, block.maxZ)) {
                continue;
            }
            if (desiredX > 0.0 && bounds.maxX <= block.minX) {
                allowedX = Math.min(allowedX, block.minX - bounds.maxX);
            } else if (desiredX < 0.0 && bounds.minX >= block.maxX) {
                allowedX = Math.max(allowedX, block.maxX - bounds.minX);
            }
        }
        return allowedX;
    }

    private static double clampZ(AABB bounds, double desiredZ, List<AABB> blocks) {
        double allowedZ = desiredZ;
        for (AABB block : blocks) {
            if (!overlaps(bounds.minY, bounds.maxY, block.minY, block.maxY)
                    || !overlaps(bounds.minX, bounds.maxX, block.minX, block.maxX)) {
                continue;
            }
            if (desiredZ > 0.0 && bounds.maxZ <= block.minZ) {
                allowedZ = Math.min(allowedZ, block.minZ - bounds.maxZ);
            } else if (desiredZ < 0.0 && bounds.minZ >= block.maxZ) {
                allowedZ = Math.max(allowedZ, block.maxZ - bounds.minZ);
            }
        }
        return allowedZ;
    }

    private static double horizontalErrorSquared(Vec3 candidate, Vec3 desired) {
        double xError = candidate.x - desired.x;
        double zError = candidate.z - desired.z;
        return xError * xError + zError * zError;
    }

    private static boolean overlaps(
            double firstMin, double firstMax, double secondMin, double secondMax) {
        return firstMax > secondMin && firstMin < secondMax;
    }

    private static GapDeficit gapDeficit(
            Vec3 displacedHeadCenter, List<LightConstraint> lights) {
        double max = 0.0;
        double total = 0.0;
        for (LightConstraint light : lights) {
            double gap = displacedHeadCenter.distanceTo(light.position)
                    - HEAD_KEEP_OUT_RADIUS
                    - light.emitterRadius;
            double deficit = Math.max(0.0, light.targetGap - gap);
            max = Math.max(max, deficit);
            total += deficit;
        }
        return new GapDeficit(max, total);
    }

    private static boolean isBetter(GapDeficit candidate, GapDeficit reference) {
        return candidate.max < reference.max
                || (candidate.max == reference.max && candidate.total < reference.total);
    }

    private static Vec3 horizontal(Vec3 vector) {
        return new Vec3(vector.x, 0.0, vector.z);
    }

    private static void addUniqueDirection(List<Vec3> directions, Vec3 direction) {
        for (Vec3 existing : directions) {
            if (existing.dot(direction) > 0.999) {
                return;
            }
        }
        directions.add(direction);
    }

    record LightConstraint(Vec3 position, double emitterRadius, double targetGap) {
        LightConstraint(Vec3 position, double emitterRadius) {
            this(position, emitterRadius, TARGET_LIGHT_GAP);
        }
    }

    record SpringStep(Vec3 position, Vec3 velocity) {
    }

    private record GapDeficit(double max, double total) {
    }
}
