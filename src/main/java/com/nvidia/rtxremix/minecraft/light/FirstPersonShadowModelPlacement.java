/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import net.minecraft.client.render.Camera;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

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

    private Vec3d offset = Vec3d.ZERO;
    private Vec3d velocity = Vec3d.ZERO;

    Vec3d resolve(ClientWorld world, Camera camera, Entity player,
                  List<LightConstraint> lights, float dtSeconds) {
        Vec3d headCenter = camera.getPos();
        Vec3d target = lights.isEmpty()
                ? Vec3d.ZERO
                : chooseTargetOffset(world, camera, player, headCenter, lights, offset);

        SpringStep spring = advanceSpring(
                offset,
                velocity,
                target,
                dtSeconds,
                SPRING_ANGULAR_FREQUENCY);
        Vec3d collisionSafeOffset = clampToBlockCollisions(world, player, spring.position);
        boolean constrained = collisionSafeOffset.squaredDistanceTo(spring.position)
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
            velocity = Vec3d.ZERO;
        } else {
            velocity = spring.velocity;
        }
        offset = collisionSafeOffset;
        return offset;
    }

    void clear() {
        offset = Vec3d.ZERO;
        velocity = Vec3d.ZERO;
    }

    private static Vec3d chooseTargetOffset(
            ClientWorld world, Camera camera, Entity player, Vec3d headCenter,
            List<LightConstraint> lights, Vec3d currentOffset) {
        List<Vec3d> directions = new ArrayList<>(lights.size() + 7);
        Vec3d currentDirection = horizontal(currentOffset);
        if (currentDirection.lengthSquared() > DIRECTION_EPSILON_SQ) {
            addUniqueDirection(directions, currentDirection.normalize());
        }

        Vec3d averageAway = Vec3d.ZERO;
        for (LightConstraint light : lights) {
            Vec3d away = horizontal(headCenter.subtract(light.x, light.y, light.z));
            if (away.lengthSquared() > DIRECTION_EPSILON_SQ) {
                away = away.normalize();
                addUniqueDirection(directions, away);
                averageAway = averageAway.add(away);
            }
        }
        if (averageAway.lengthSquared() > DIRECTION_EPSILON_SQ) {
            addUniqueDirection(directions, averageAway.normalize());
        }
        Vec3d backward = HeldLightPlacement.horizontalForward(camera.getYaw()).negate();
        addUniqueDirection(directions, backward);

        // A wall can block the preferred backward displacement without
        // blocking either direction along its face. These alternatives keep
        // the hidden model away from the light through concave corners.
        Vec3d tangent = new Vec3d(-backward.z, 0.0, backward.x);
        addUniqueDirection(directions, backward.add(tangent).normalize());
        addUniqueDirection(directions, backward.subtract(tangent).normalize());
        addUniqueDirection(directions, tangent);
        addUniqueDirection(directions, tangent.negate());

        List<Vec3d> candidates = new ArrayList<>(directions.size() + 1);
        candidates.add(clampToBlockCollisions(world, player, currentOffset));
        for (Vec3d direction : directions) {
            double requiredLength = 0.0;
            for (LightConstraint light : lights) {
                double requiredCenterDistance = HEAD_KEEP_OUT_RADIUS
                        + light.emitterRadius + light.targetGap;
                requiredLength = Math.max(
                        requiredLength,
                        requiredTranslation(
                                headCenter.subtract(light.x, light.y, light.z),
                                direction,
                                requiredCenterDistance));
            }

            Vec3d desired = direction.multiply(Math.min(requiredLength, MAX_MODEL_OFFSET));
            candidates.add(clampToBlockCollisions(world, player, desired));
        }
        return selectContinuousCandidate(currentOffset, headCenter, lights, candidates);
    }

    static Vec3d selectContinuousCandidate(
            Vec3d currentOffset, Vec3d headCenter,
            List<LightConstraint> lights, List<Vec3d> candidates) {
        GapDeficit optimum = new GapDeficit(
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        for (Vec3d candidate : candidates) {
            GapDeficit deficit = gapDeficit(headCenter.add(candidate), lights);
            if (isBetter(deficit, optimum)) {
                optimum = deficit;
            }
        }

        Vec3d selected = candidates.getFirst();
        double selectedMotion = Double.POSITIVE_INFINITY;
        double totalHysteresis = TARGET_CLEARANCE_HYSTERESIS * lights.size();
        for (Vec3d candidate : candidates) {
            GapDeficit deficit = gapDeficit(headCenter.add(candidate), lights);
            if (deficit.max > optimum.max + TARGET_CLEARANCE_HYSTERESIS
                    || deficit.total > optimum.total + totalHysteresis) {
                continue;
            }

            double motion = candidate.squaredDistanceTo(currentOffset);
            if (motion < selectedMotion) {
                selected = candidate;
                selectedMotion = motion;
            }
        }
        return selected;
    }

    static double requiredTranslation(
            Vec3d headCenterFromLight, Vec3d direction, double requiredCenterDistance) {
        double distanceSquared = headCenterFromLight.lengthSquared();
        double requiredSquared = requiredCenterDistance * requiredCenterDistance;
        if (distanceSquared >= requiredSquared) {
            return 0.0;
        }
        double projection = headCenterFromLight.dotProduct(direction);
        double discriminant = projection * projection - distanceSquared + requiredSquared;
        return Math.max(0.0, -projection + Math.sqrt(Math.max(0.0, discriminant)));
    }

    static float clearanceRadianceScale(Vec3d displacedHeadCenter, LightConstraint light) {
        double surfaceGap = Math.sqrt(displacedHeadCenter.squaredDistanceTo(light.x, light.y, light.z))
                - HEAD_KEEP_OUT_RADIUS
                - light.emitterRadius;
        float normalized = (float) Math.clamp(surfaceGap / light.targetGap, 0.0, 1.0);
        return normalized * normalized * (3.0f - 2.0f * normalized);
    }

    static SpringStep advanceSpring(
            Vec3d position, Vec3d velocity, Vec3d target,
            float dtSeconds, double angularFrequency) {
        double dt = Math.clamp(
                dtSeconds, 0.0f, HeldLightPlacement.MAX_FRAME_DT_SECONDS);
        double omega = Math.max(0.0, angularFrequency);
        if (dt == 0.0 || omega == 0.0) {
            return new SpringStep(position, velocity);
        }

        double decay = Math.exp(-omega * dt);
        Vec3d displacement = position.subtract(target);
        Vec3d temporary = velocity.add(displacement.multiply(omega)).multiply(dt);
        Vec3d nextPosition = target.add(displacement.add(temporary).multiply(decay));
        Vec3d nextVelocity = velocity.subtract(temporary.multiply(omega)).multiply(decay);
        return new SpringStep(nextPosition, nextVelocity);
    }

    private static Vec3d clampToBlockCollisions(
            ClientWorld world, Entity player, Vec3d desiredOffset) {
        if (desiredOffset.lengthSquared() <= DIRECTION_EPSILON_SQ) {
            return Vec3d.ZERO;
        }

        Box modelBounds = player.getBoundingBox().contract(COLLISION_EPSILON);
        Box desiredBounds = modelBounds.offset(desiredOffset);
        Box queryBounds = new Box(
                Math.min(modelBounds.minX, desiredBounds.minX) - COLLISION_EPSILON,
                Math.min(modelBounds.minY, desiredBounds.minY) - COLLISION_EPSILON,
                Math.min(modelBounds.minZ, desiredBounds.minZ) - COLLISION_EPSILON,
                Math.max(modelBounds.maxX, desiredBounds.maxX) + COLLISION_EPSILON,
                Math.max(modelBounds.maxY, desiredBounds.maxY) + COLLISION_EPSILON,
                Math.max(modelBounds.maxZ, desiredBounds.maxZ) + COLLISION_EPSILON);
        List<Box> nearbyBlocks = new ArrayList<>();
        world.getBlockCollisions(player, queryBounds).forEach(
                shape -> nearbyBlocks.addAll(shape.getBoundingBoxes()));
        return slideAgainstBlocks(modelBounds, desiredOffset, nearbyBlocks);
    }

    static Vec3d slideAgainstBlocks(
            Box modelBounds, Vec3d desiredOffset, List<Box> nearbyBlocks) {
        double xFirstX = clampX(modelBounds, desiredOffset.x, nearbyBlocks);
        Box afterX = modelBounds.offset(xFirstX, 0.0, 0.0);
        double xFirstZ = clampZ(afterX, desiredOffset.z, nearbyBlocks);
        Vec3d xFirst = new Vec3d(xFirstX, desiredOffset.y, xFirstZ);

        double zFirstZ = clampZ(modelBounds, desiredOffset.z, nearbyBlocks);
        Box afterZ = modelBounds.offset(0.0, 0.0, zFirstZ);
        double zFirstX = clampX(afterZ, desiredOffset.x, nearbyBlocks);
        Vec3d zFirst = new Vec3d(zFirstX, desiredOffset.y, zFirstZ);

        return horizontalErrorSquared(xFirst, desiredOffset)
                <= horizontalErrorSquared(zFirst, desiredOffset)
                ? xFirst
                : zFirst;
    }

    private static double clampX(Box bounds, double desiredX, List<Box> blocks) {
        double allowedX = desiredX;
        for (Box block : blocks) {
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

    private static double clampZ(Box bounds, double desiredZ, List<Box> blocks) {
        double allowedZ = desiredZ;
        for (Box block : blocks) {
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

    private static double horizontalErrorSquared(Vec3d candidate, Vec3d desired) {
        double xError = candidate.x - desired.x;
        double zError = candidate.z - desired.z;
        return xError * xError + zError * zError;
    }

    private static boolean overlaps(
            double firstMin, double firstMax, double secondMin, double secondMax) {
        return firstMax > secondMin && firstMin < secondMax;
    }

    private static GapDeficit gapDeficit(
            Vec3d displacedHeadCenter, List<LightConstraint> lights) {
        double max = 0.0;
        double total = 0.0;
        for (LightConstraint light : lights) {
            double gap = Math.sqrt(displacedHeadCenter.squaredDistanceTo(light.x, light.y, light.z))
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

    private static Vec3d horizontal(Vec3d vector) {
        return new Vec3d(vector.x, 0.0, vector.z);
    }

    private static void addUniqueDirection(List<Vec3d> directions, Vec3d direction) {
        for (Vec3d existing : directions) {
            if (existing.dotProduct(direction) > 0.999) {
                return;
            }
        }
        directions.add(direction);
    }

    record LightConstraint(double x, double y, double z, double emitterRadius, double targetGap) {
        LightConstraint(Vec3d position, double emitterRadius, double targetGap) {
            this(position.x, position.y, position.z, emitterRadius, targetGap);
        }

        LightConstraint(Vec3d position, double emitterRadius) {
            this(position, emitterRadius, TARGET_LIGHT_GAP);
        }
    }

    record SpringStep(Vec3d position, Vec3d velocity) {
    }

    private record GapDeficit(double max, double total) {
    }
}
