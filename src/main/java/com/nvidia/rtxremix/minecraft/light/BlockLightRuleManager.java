/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Owns block-attached Remix light rules and atomically swaps them on resource reload.
 */
public final class BlockLightRuleManager {
    public static final String RESOURCE_PATH = "rtx_remix/block_lights";
    public static final float RADIANCE_PER_LIGHT_LEVEL = 5.0f;

    private static final Logger LOGGER = LoggerFactory.getLogger(BlockLightRuleManager.class);
    private static final Gson GSON = new Gson();
    // The initial resource reload publishes the default catalog before section capture starts.
    private static volatile RuleCatalog activeCatalog = new RuleCatalog(Map.of());

    /** Immutable rule snapshot indexed by the canonical raw ID of each registered block state. */
    private static final class RuleCatalog {
        private final List<Rule>[] matchesByRawStateId;
        private final CandidatePlan<Rule>[] candidatePlansByRawStateId;

        RuleCatalog(Map<ResourceLocation, Rule> rules) {
            Map<ResourceLocation, Rule> immutableRules =
                    Collections.unmodifiableMap(new LinkedHashMap<>(rules));
            if (immutableRules.isEmpty()) {
                this.matchesByRawStateId = emptyMatchTable();
                this.candidatePlansByRawStateId = null;
                return;
            }

            boolean hasTagRules = immutableRules.values().stream()
                    .anyMatch(BlockLightRuleManager::hasDynamicTags);
            if (hasTagRules) {
                this.matchesByRawStateId = emptyMatchTable();
                this.candidatePlansByRawStateId = precomputeCandidatePlans(
                        Block.BLOCK_STATE_REGISTRY.size(), Block.BLOCK_STATE_REGISTRY::byId, immutableRules.values(),
                        BlockLightRuleManager::matchesStatically,
                        BlockLightRuleManager::isDynamicCandidate);
            } else {
                this.matchesByRawStateId = precomputeMatches(
                        Block.BLOCK_STATE_REGISTRY.size(), Block.BLOCK_STATE_REGISTRY::byId,
                        immutableRules.values(), Rule::matches);
                this.candidatePlansByRawStateId = null;
            }
        }

        boolean hasMatchingRules(BlockState state) {
            int rawId = Block.getId(state);
            if (!isCanonicalState(rawId, state)) {
                return false;
            }

            if (candidatePlansByRawStateId == null) {
                return !matchesByRawStateId[rawId].isEmpty();
            }

            CandidatePlan<Rule> plan = candidatePlansByRawStateId[rawId];
            for (int i = 0; i < plan.size(); i++) {
                if (!plan.requiresDynamicMatch(i)
                        || matchesDynamicTag(plan.ruleAt(i), state)) {
                    return true;
                }
            }
            return false;
        }

        List<LightEntry> resolve(BlockState state, BlockPos pos, ModelSampler sampler) {
            int rawId = Block.getId(state);
            if (!isCanonicalState(rawId, state)) {
                return List.of();
            }

            List<LightEntry> resolved = null;
            if (candidatePlansByRawStateId == null) {
                for (Rule rule : matchesByRawStateId[rawId]) {
                    resolved = appendResolved(resolved, rule, state, pos, sampler);
                }
                return resolved == null ? List.of() : resolved;
            }

            CandidatePlan<Rule> plan = candidatePlansByRawStateId[rawId];
            for (int i = 0; i < plan.size(); i++) {
                Rule rule = plan.ruleAt(i);
                if (plan.requiresDynamicMatch(i) && !matchesDynamicTag(rule, state)) {
                    continue;
                }
                resolved = appendResolved(resolved, rule, state, pos, sampler);
            }
            return resolved == null ? List.of() : resolved;
        }

        private boolean isCanonicalState(int rawId, BlockState state) {
            int tableLength = candidatePlansByRawStateId == null
                    ? matchesByRawStateId.length
                    : candidatePlansByRawStateId.length;
            // Minecraft maps an unregistered state to raw ID zero, so range alone is insufficient.
            return rawId >= 0 && rawId < tableLength && Block.BLOCK_STATE_REGISTRY.byId(rawId) == state;
        }

        @SuppressWarnings("unchecked")
        private static List<Rule>[] emptyMatchTable() {
            return (List<Rule>[]) new List<?>[0];
        }
    }

    static final class CandidatePlan<R> {
        private static final CandidatePlan<?> EMPTY =
                new CandidatePlan<>(List.of(), new boolean[0]);

        private final List<R> rules;
        private final boolean[] dynamicMatches;

        private CandidatePlan(List<R> rules, boolean[] dynamicMatches) {
            this.rules = rules;
            this.dynamicMatches = dynamicMatches;
        }

        int size() {
            return rules.size();
        }

        R ruleAt(int index) {
            return rules.get(index);
        }

        boolean requiresDynamicMatch(int index) {
            return dynamicMatches[index];
        }

        <S> boolean matches(int index, S state, BiPredicate<? super R, ? super S> matcher) {
            return !dynamicMatches[index] || matcher.test(rules.get(index), state);
        }

        @SuppressWarnings("unchecked")
        private static <R> CandidatePlan<R> empty() {
            return (CandidatePlan<R>) EMPTY;
        }
    }

    @SuppressWarnings("unchecked")
    static <S, R> List<R>[] precomputeMatches(int stateCount,
                                              IntFunction<S> stateByRawId,
                                              Iterable<R> rules,
                                              BiPredicate<R, S> matcher) {
        List<R>[] matchesByState = (List<R>[]) new List<?>[stateCount];
        for (int rawId = 0; rawId < matchesByState.length; rawId++) {
            S state = stateByRawId.apply(rawId);
            if (state == null) {
                matchesByState[rawId] = List.of();
                continue;
            }

            List<R> matches = null;
            for (R rule : rules) {
                if (!matcher.test(rule, state)) continue;
                if (matches == null) matches = new ArrayList<>();
                matches.add(rule);
            }
            matchesByState[rawId] = matches == null ? List.of() : List.copyOf(matches);
        }
        return matchesByState;
    }

    @SuppressWarnings("unchecked")
    static <S, R> CandidatePlan<R>[] precomputeCandidatePlans(
            int stateCount,
            IntFunction<S> stateByRawId,
            Iterable<R> rules,
            BiPredicate<R, S> staticMatcher,
            BiPredicate<R, S> dynamicCandidate) {
        CandidatePlan<R>[] plansByState = (CandidatePlan<R>[]) new CandidatePlan<?>[stateCount];
        for (int rawId = 0; rawId < plansByState.length; rawId++) {
            S state = stateByRawId.apply(rawId);
            if (state == null) {
                plansByState[rawId] = CandidatePlan.empty();
                continue;
            }

            List<R> candidates = null;
            List<Boolean> dynamicMatches = null;
            for (R rule : rules) {
                boolean staticMatch = staticMatcher.test(rule, state);
                if (!staticMatch && !dynamicCandidate.test(rule, state)) {
                    continue;
                }
                if (candidates == null) {
                    candidates = new ArrayList<>();
                    dynamicMatches = new ArrayList<>();
                }
                candidates.add(rule);
                dynamicMatches.add(!staticMatch);
            }

            if (candidates == null) {
                plansByState[rawId] = CandidatePlan.empty();
                continue;
            }
            boolean[] flags = new boolean[dynamicMatches.size()];
            for (int i = 0; i < flags.length; i++) {
                flags[i] = dynamicMatches.get(i);
            }
            plansByState[rawId] = new CandidatePlan<>(List.copyOf(candidates), flags);
        }
        return plansByState;
    }

    private BlockLightRuleManager() {}

    public static List<LightEntry> resolve(BlockState state, BlockPos pos, ModelSampler sampler) {
        if (state.isAir()) {
            return List.of();
        }
        return activeCatalog.resolve(state, pos, sampler);
    }

    /** Fast preflight used by section capture to avoid model-sampler setup for ordinary blocks. */
    public static boolean hasMatchingRules(BlockState state) {
        return !state.isAir() && activeCatalog.hasMatchingRules(state);
    }

    private static List<LightEntry> appendResolved(List<LightEntry> resolved, Rule rule,
                                                   BlockState state, BlockPos pos,
                                                   ModelSampler sampler) {
        List<LightEntry> entries = rule.resolve(state, pos, sampler);
        if (entries.isEmpty()) {
            return resolved;
        }
        if (resolved == null) {
            resolved = new ArrayList<>(entries.size());
        }
        resolved.addAll(entries);
        return resolved;
    }

    private static boolean hasDynamicTags(Rule rule) {
        return rule instanceof JsonRule jsonRule && jsonRule.hasDynamicTags();
    }

    private static boolean matchesStatically(Rule rule, BlockState state) {
        return rule instanceof JsonRule jsonRule && jsonRule.hasDynamicTags()
                ? jsonRule.matchesExplicitBlock(state)
                : rule.matches(state);
    }

    private static boolean isDynamicCandidate(Rule rule, BlockState state) {
        return rule instanceof JsonRule jsonRule && jsonRule.isDynamicCandidate(state);
    }

    private static boolean matchesDynamicTag(Rule rule, BlockState state) {
        return ((JsonRule) rule).matchesDynamicTag(state);
    }

    public static void reload(ResourceManager manager) {
        reload(manager, BlockLightDefaults.create());
    }

    static void reload(ResourceManager manager, Map<ResourceLocation, Rule> defaults) {
        Map<ResourceLocation, Rule> rules = loadRules(manager, defaults);
        activeCatalog = new RuleCatalog(rules);
        LOGGER.info("Loaded {} block light rules", rules.size());
    }

    static Map<ResourceLocation, Rule> loadRules(ResourceManager manager) {
        return loadRules(manager, BlockLightDefaults.create());
    }

    static Map<ResourceLocation, Rule> loadRules(ResourceManager manager, Map<ResourceLocation, Rule> defaults) {
        LinkedHashMap<ResourceLocation, Rule> rules = new LinkedHashMap<>(defaults);
        manager.listResourceStacks(RESOURCE_PATH, id -> id.getPath().endsWith(".json"))
                .entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)))
                .forEach(entry -> {
                    for (Resource resource : entry.getValue()) {
                        loadJsonRule(rules, entry.getKey(), resource);
                    }
                });
        return Collections.unmodifiableMap(new LinkedHashMap<>(rules));
    }

    static Rule rule(ResourceLocation id, Predicate<BlockState> matcher, Resolver resolver) {
        return new FunctionalRule(id, matcher, resolver);
    }

    static Rule parseJsonRule(ResourceLocation id, JsonObject root) {
        return JsonRule.parse(id, root);
    }

    static LightEntry sphere(ResourceLocation ruleId, int index, BlockState state, BlockPos pos,
                             float x, float y, float z, float radius,
                             LightEntry.ColorOverride color, Float intensity) {
        return LightEntry.sphere(key(pos, ruleId, index), state.getLightEmission(), state.getBlock(),
                pos.getX() + x, pos.getY() + y, pos.getZ() + z,
                radius, color, intensity, LightEntry.defaultVolumetricRadianceScale());
    }

    static LightEntry shapedSphere(ResourceLocation ruleId, int index, BlockState state, BlockPos pos,
                                   float x, float y, float z, float radius,
                                   Vec3 direction,
                                   float coneAngleDegrees, float coneSoftness, float focusExponent,
                                   LightEntry.ColorOverride color, Float intensity,
                                   float volumetricRadianceScale) {
        Vec3 dir = direction.normalized();
        return LightEntry.shapedSphere(key(pos, ruleId, index), state.getLightEmission(), state.getBlock(),
                pos.getX() + x, pos.getY() + y, pos.getZ() + z,
                radius, color, intensity,
                dir.x, dir.y, dir.z,
                coneAngleDegrees, coneSoftness, focusExponent,
                volumetricRadianceScale);
    }

    static LightEntry cylinder(ResourceLocation ruleId, int index, BlockState state, BlockPos pos,
                               float x, float y, float z, float radius,
                               Vec3 axis, float axisLength,
                               LightEntry.ColorOverride color, Float intensity) {
        Vec3 normal = axis.normalized();
        return LightEntry.cylinder(key(pos, ruleId, index), state.getLightEmission(), state.getBlock(),
                pos.getX() + x, pos.getY() + y, pos.getZ() + z,
                radius, normal.x, normal.y, normal.z, axisLength,
                color, intensity, LightEntry.defaultVolumetricRadianceScale());
    }

    static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, RESOURCE_PATH + "/" + path);
    }

    private static BlockLightKey key(BlockPos pos, ResourceLocation ruleId, int index) {
        return new BlockLightKey(pos.immutable(), ruleId, index);
    }

    private static void loadJsonRule(Map<ResourceLocation, Rule> rules, ResourceLocation resourceId, Resource resource) {
        try (Reader reader = resource.openAsReader()) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) {
                throw new JsonParseException("empty rule file");
            }
            rules.put(resourceId, JsonRule.parse(resourceId, root));
        } catch (Exception e) {
            LOGGER.warn("Skipping block light rule {} from {}: {}", resourceId, resource.sourcePackId(), e.getMessage());
        }
    }

    /**
     * Read-only view of the baked model of the block being resolved, supplied by
     * the mesh emitter because only it knows which variant this position drew.
     *
     * <p>Everything here is lazy, so a rule that asks for nothing costs nothing
     * on a path that runs for every block of every section build.
     *
     * <p>Also note that positions here are in the model's own space, which for a
     * block with an {@code AbstractBlock.OffsetType} is not where the block's
     * geometry was drawn; see {@code BlockQuadEmitter.emitBlock}.
     */
    public interface ModelSampler {
        /** Mean of the model's vertices, in block-local [0,1] space. */
        float[] centroid();
    }

    /**
     * Sampler for callers with no baked model to look at: the item-light path,
     * which resolves rules against a held or dropped stack rather than a block
     * in the world.
     */
    public static ModelSampler fixedCentroid(float[] centroid) {
        return new ModelSampler() {
            @Override
            public float[] centroid() {
                return centroid;
            }
        };
    }

    interface Rule {
        ResourceLocation id();
        boolean matches(BlockState state);
        List<LightEntry> resolve(BlockState state, BlockPos pos, ModelSampler sampler);
    }

    interface Resolver {
        List<LightEntry> resolve(ResourceLocation id, BlockState state, BlockPos pos, ModelSampler sampler);
    }

    private record FunctionalRule(ResourceLocation id, Predicate<BlockState> matcher, Resolver resolver) implements Rule {
        @Override
        public boolean matches(BlockState state) {
            return matcher.test(state);
        }

        @Override
        public List<LightEntry> resolve(BlockState state, BlockPos pos, ModelSampler sampler) {
            return resolver.resolve(id, state, pos, sampler);
        }
    }

    private record JsonRule(
            ResourceLocation id,
            Set<ResourceLocation> blockIds,
            Set<TagKey<Block>> blockTags,
            Map<String, String> properties,
            List<JsonLightSpec> lights) implements Rule {

        static JsonRule parse(ResourceLocation id, JsonObject root) {
            Set<ResourceLocation> blocks = new HashSet<>();
            Set<TagKey<Block>> tags = new HashSet<>();
            JsonArray blockArray = requiredArray(root, "blocks");
            for (JsonElement element : blockArray) {
                String value = element.getAsString();
                if (value.startsWith("#")) {
                    tags.add(TagKey.create(Registries.BLOCK, ResourceLocation.parse(value.substring(1))));
                } else {
                    ResourceLocation blockId = ResourceLocation.parse(value);
                    blocks.add(blockId);
                }
            }
            if (blocks.isEmpty() && tags.isEmpty()) {
                throw new JsonParseException("blocks must not be empty");
            }

            Map<String, String> properties = parseProperties(root);
            List<JsonLightSpec> specs = parseLights(root);
            return new JsonRule(id, Set.copyOf(blocks), Set.copyOf(tags), Map.copyOf(properties), List.copyOf(specs));
        }

        @Override
        public boolean matches(BlockState state) {
            return propertiesMatch(state, properties)
                    && (blockIds.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()))
                    || matchesDynamicTag(state));
        }

        boolean hasDynamicTags() {
            return !blockTags.isEmpty();
        }

        boolean matchesExplicitBlock(BlockState state) {
            return blockIds.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()))
                    && propertiesMatch(state, properties);
        }

        boolean isDynamicCandidate(BlockState state) {
            return hasDynamicTags() && propertiesMatch(state, properties);
        }

        boolean matchesDynamicTag(BlockState state) {
            for (TagKey<Block> tag : blockTags) {
                if (state.is(tag)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public List<LightEntry> resolve(BlockState state, BlockPos pos, ModelSampler sampler) {
            List<LightEntry> entries = new ArrayList<>(lights.size());
            for (int i = 0; i < lights.size(); i++) {
                entries.add(lights.get(i).resolve(id, i, state, pos, sampler));
            }
            return entries;
        }
    }

    private record JsonLightSpec(
            LightShape shape,
            PositionSpec position,
            LightEntry.ColorOverride color,
            Float intensity,
            float volumetricRadianceScale,
            float radius,
            VectorSpec axis,
            Float axisLength,
            VectorSpec xAxis,
            Float xSize,
            Float xRadius,
            VectorSpec yAxis,
            Float ySize,
            Float yRadius,
            VectorSpec direction) {

        static JsonLightSpec parse(JsonObject json) {
            LightShape shape = parseShape(stringOrDefault(json, "type", "sphere"));
            PositionSpec position = json.has("position")
                    ? PositionSpec.parse(json.getAsJsonObject("position"))
                    : PositionSpec.centroid();
            LightEntry.ColorOverride color = json.has("color") ? parseColor(json.getAsJsonArray("color")) : null;
            Float intensity = json.has("intensity") ? positiveFloat(json, "intensity") : null;
            float volumetricRadianceScale = json.has("volumetricRadianceScale")
                    ? nonNegativeFloat(json, "volumetricRadianceScale")
                    : LightEntry.defaultVolumetricRadianceScale();
            float radius = json.has("radius") ? positiveFloat(json, "radius") : 0.2f;
            VectorSpec axis = json.has("axis") ? VectorSpec.parse(json.get("axis")) : VectorSpec.fixed(0, 1, 0);
            Float axisLength = json.has("axisLength") ? positiveFloat(json, "axisLength") : null;
            VectorSpec xAxis = json.has("xAxis") ? VectorSpec.parse(json.get("xAxis")) : VectorSpec.fixed(1, 0, 0);
            VectorSpec yAxis = json.has("yAxis") ? VectorSpec.parse(json.get("yAxis")) : VectorSpec.fixed(0, 1, 0);
            VectorSpec direction = json.has("direction") ? VectorSpec.parse(json.get("direction")) : VectorSpec.fixed(0, 0, -1);
            return new JsonLightSpec(shape, position, color, intensity, volumetricRadianceScale,
                    radius, axis, json.has("axisLength") ? axisLength : 1.0f,
                    xAxis, json.has("xSize") ? positiveFloat(json, "xSize") : null,
                    json.has("xRadius") ? positiveFloat(json, "xRadius") : null,
                    yAxis, json.has("ySize") ? positiveFloat(json, "ySize") : null,
                    json.has("yRadius") ? positiveFloat(json, "yRadius") : null,
                    direction);
        }

        LightEntry resolve(ResourceLocation ruleId, int index, BlockState state, BlockPos pos, ModelSampler sampler) {
            Vec3 p = position.resolve(state, sampler);
            return switch (shape) {
                case SPHERE -> LightEntry.sphere(key(pos, ruleId, index), state.getLightEmission(), state.getBlock(),
                        pos.getX() + p.x, pos.getY() + p.y, pos.getZ() + p.z,
                        radius, color, intensity, volumetricRadianceScale);
                case CYLINDER -> {
                    Vec3 resolvedAxis = axis.resolve(state).normalized();
                    yield LightEntry.cylinder(key(pos, ruleId, index), state.getLightEmission(), state.getBlock(),
                            pos.getX() + p.x, pos.getY() + p.y, pos.getZ() + p.z,
                            radius, resolvedAxis.x, resolvedAxis.y, resolvedAxis.z, axisLength,
                            color, intensity, volumetricRadianceScale);
                }
                case RECT -> {
                    Vec3 xa = xAxis.resolve(state).normalized();
                    Vec3 ya = yAxis.resolve(state).normalized();
                    Vec3 dir = direction.resolve(state).normalized();
                    yield LightEntry.rect(key(pos, ruleId, index), state.getLightEmission(), state.getBlock(),
                            pos.getX() + p.x, pos.getY() + p.y, pos.getZ() + p.z,
                            xa.x, xa.y, xa.z, Objects.requireNonNullElse(xSize, 1.0f),
                            ya.x, ya.y, ya.z, Objects.requireNonNullElse(ySize, 1.0f),
                            dir.x, dir.y, dir.z,
                            color, intensity, volumetricRadianceScale);
                }
                case DISK -> {
                    Vec3 xa = xAxis.resolve(state).normalized();
                    Vec3 ya = yAxis.resolve(state).normalized();
                    Vec3 dir = direction.resolve(state).normalized();
                    yield LightEntry.disk(key(pos, ruleId, index), state.getLightEmission(), state.getBlock(),
                            pos.getX() + p.x, pos.getY() + p.y, pos.getZ() + p.z,
                            xa.x, xa.y, xa.z, Objects.requireNonNullElse(xRadius, 0.5f),
                            ya.x, ya.y, ya.z, Objects.requireNonNullElse(yRadius, 0.5f),
                            dir.x, dir.y, dir.z,
                            color, intensity, volumetricRadianceScale);
                }
            };
        }
    }

    private sealed interface PositionSpec permits FixedPositionSpec, FacingOffsetPositionSpec, CentroidPositionSpec {
        Vec3 resolve(BlockState state, ModelSampler sampler);

        static PositionSpec centroid() {
            return new CentroidPositionSpec();
        }

        static PositionSpec parse(JsonObject json) {
            String type = stringOrDefault(json, "type", "offset");
            return switch (type) {
                case "offset" -> new FixedPositionSpec(readVec3(json));
                case "facing_offset" -> new FacingOffsetPositionSpec(
                        readVec3(requiredArray(json, "base")),
                        requiredString(json, "property"),
                        floatOrDefault(json, "scale", 1.0f));
                case "centroid" -> centroid();
                default -> throw new JsonParseException("unknown position type " + type);
            };
        }
    }

    private record FixedPositionSpec(Vec3 value) implements PositionSpec {
        @Override
        public Vec3 resolve(BlockState state, ModelSampler sampler) {
            return value;
        }
    }

    private record FacingOffsetPositionSpec(Vec3 base, String property, float scale) implements PositionSpec {
        @Override
        public Vec3 resolve(BlockState state, ModelSampler sampler) {
            Direction direction = directionProperty(state, property);
            Direction opposite = direction.getOpposite();
            return new Vec3(
                    base.x + scale * opposite.getStepX(),
                    base.y + scale * opposite.getStepY(),
                    base.z + scale * opposite.getStepZ());
        }
    }

    private record CentroidPositionSpec() implements PositionSpec {
        @Override
        public Vec3 resolve(BlockState state, ModelSampler sampler) {
            float[] c = sampler.centroid();
            return new Vec3(c[0], c[1], c[2]);
        }
    }

    private sealed interface VectorSpec permits FixedVectorSpec, StateVectorSpec {
        Vec3 resolve(BlockState state);

        static VectorSpec fixed(float x, float y, float z) {
            return new FixedVectorSpec(new Vec3(x, y, z));
        }

        static VectorSpec parse(JsonElement element) {
            if (element.isJsonPrimitive()) {
                return new StateVectorSpec(element.getAsString());
            }
            return new FixedVectorSpec(readVec3(element.getAsJsonArray()));
        }
    }

    private record FixedVectorSpec(Vec3 value) implements VectorSpec {
        @Override
        public Vec3 resolve(BlockState state) {
            return value;
        }
    }

    private record StateVectorSpec(String type) implements VectorSpec {
        @Override
        public Vec3 resolve(BlockState state) {
            Direction facing = state.hasProperty(BlockStateProperties.FACING)
                    ? state.getValue(BlockStateProperties.FACING)
                    : Direction.UP;
            return switch (type) {
                case "facing" -> Vec3.from(facing);
                case "facing_opposite" -> Vec3.from(facing.getOpposite());
                case "facing_axis" -> Vec3.fromAxis(facing.getAxis());
                default -> throw new JsonParseException("unknown vector source " + type);
            };
        }
    }

    record Vec3(float x, float y, float z) {
        static Vec3 from(Direction direction) {
            return new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ());
        }

        static Vec3 fromAxis(Direction.Axis axis) {
            return switch (axis) {
                case X -> new Vec3(1, 0, 0);
                case Y -> new Vec3(0, 1, 0);
                case Z -> new Vec3(0, 0, 1);
            };
        }

        Vec3 normalized() {
            float len = (float)Math.sqrt(x * x + y * y + z * z);
            if (len == 0.0f) {
                throw new JsonParseException("vector must not be zero");
            }
            return new Vec3(x / len, y / len, z / len);
        }
    }

    private static List<JsonLightSpec> parseLights(JsonObject root) {
        List<JsonLightSpec> specs = new ArrayList<>();
        if (root.has("light")) {
            specs.add(JsonLightSpec.parse(root.getAsJsonObject("light")));
        }
        if (root.has("lights")) {
            for (JsonElement element : root.getAsJsonArray("lights")) {
                specs.add(JsonLightSpec.parse(element.getAsJsonObject()));
            }
        }
        if (specs.isEmpty()) {
            throw new JsonParseException("rule must define light or lights");
        }
        return specs;
    }

    private static Map<String, String> parseProperties(JsonObject root) {
        if (!root.has("when")) {
            return Map.of();
        }
        JsonObject when = root.getAsJsonObject("when");
        if (!when.has("properties")) {
            return Map.of();
        }
        Map<String, String> properties = new LinkedHashMap<>();
        JsonObject json = when.getAsJsonObject("properties");
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            properties.put(entry.getKey(), entry.getValue().getAsString());
        }
        return properties;
    }

    private static boolean propertiesMatch(BlockState state, Map<String, String> expected) {
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            Property<?> property = propertyByName(state, entry.getKey());
            if (property == null) {
                return false;
            }
            if (!propertyValueName(state, property).equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static Property<?> propertyByName(BlockState state, String name) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(name)) {
                return property;
            }
        }
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyValueName(BlockState state, Property property) {
        return property.getName(state.getValue(property));
    }

    private static Direction directionProperty(BlockState state, String propertyName) {
        Property<?> property = propertyByName(state, propertyName);
        if (property == null || !Direction.class.isAssignableFrom(property.getValueClass())) {
            throw new JsonParseException("property " + propertyName + " is not a direction property");
        }
        return (Direction)state.getValue(property);
    }

    private static JsonArray requiredArray(JsonObject json, String name) {
        if (!json.has(name) || !json.get(name).isJsonArray()) {
            throw new JsonParseException("missing array " + name);
        }
        return json.getAsJsonArray(name);
    }

    private static String requiredString(JsonObject json, String name) {
        if (!json.has(name)) {
            throw new JsonParseException("missing string " + name);
        }
        return json.get(name).getAsString();
    }

    private static String stringOrDefault(JsonObject json, String name, String fallback) {
        return json.has(name) ? json.get(name).getAsString() : fallback;
    }

    private static float floatOrDefault(JsonObject json, String name, float fallback) {
        return json.has(name) ? json.get(name).getAsFloat() : fallback;
    }

    private static float positiveFloat(JsonObject json, String name) {
        float value = json.get(name).getAsFloat();
        if (value <= 0.0f) {
            throw new JsonParseException(name + " must be > 0");
        }
        return value;
    }

    private static float nonNegativeFloat(JsonObject json, String name) {
        float value = json.get(name).getAsFloat();
        if (value < 0.0f) {
            throw new JsonParseException(name + " must be >= 0");
        }
        return value;
    }

    private static Vec3 readVec3(JsonObject json) {
        return new Vec3(json.get("x").getAsFloat(), json.get("y").getAsFloat(), json.get("z").getAsFloat());
    }

    private static Vec3 readVec3(JsonArray array) {
        if (array.size() != 3) {
            throw new JsonParseException("vector must have 3 elements");
        }
        return new Vec3(array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat());
    }

    private static LightEntry.ColorOverride parseColor(JsonArray array) {
        Vec3 color = readVec3(array);
        if (color.x < 0 || color.y < 0 || color.z < 0) {
            throw new JsonParseException("color values must be >= 0");
        }
        return LightEntry.ColorOverride.of(color.x, color.y, color.z);
    }

    private static LightShape parseShape(String value) {
        return switch (value) {
            case "sphere" -> LightShape.SPHERE;
            case "cylinder" -> LightShape.CYLINDER;
            case "rect" -> LightShape.RECT;
            case "disk" -> LightShape.DISK;
            default -> throw new JsonParseException("unknown light type " + value);
        };
    }
}
