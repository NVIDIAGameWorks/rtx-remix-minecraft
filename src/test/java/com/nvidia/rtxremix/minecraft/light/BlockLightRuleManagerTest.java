/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockLightRuleManagerTest {

    @Test
    void parseJsonRulePreservesTheAuthoredRectSpec() throws Exception {
        Identifier id = Identifier.fromNamespaceAndPath("custom", "rtx_remix/block_lights/daylight_panel.json");
        BlockLightRuleManager.Rule rule = BlockLightRuleManager.parseJsonRule(id, JsonParser.parseString("""
                {
                  "blocks": ["minecraft:daylight_detector"],
                  "light": {
                    "type": "rect",
                    "position": { "type": "offset", "x": 0.5, "y": 0.95, "z": 0.5 },
                    "xAxis": [1, 0, 0],
                    "xSize": 1.0,
                    "yAxis": [0, 0, 1],
                    "ySize": 1.0,
                    "direction": [0, 1, 0],
                    "intensity": 12.0
                  }
                }
                """).getAsJsonObject());

        Field lightsField = rule.getClass().getDeclaredField("lights");
        lightsField.setAccessible(true);
        List<?> lights = (List<?>) lightsField.get(rule);
        assertEquals(1, lights.size());
        Object spec = lights.getFirst();

        for (var expected : Map.of(
                "shape", LightShape.RECT,
                "xSize", 1.0f,
                "ySize", 1.0f,
                "intensity", 12.0f).entrySet()) {
            Field field = spec.getClass().getDeclaredField(expected.getKey());
            field.setAccessible(true);
            assertEquals(expected.getValue(), field.get(spec), expected.getKey());
        }
        String[] components = {"x", "y", "z"};
        for (var expected : Map.of(
                "position", new float[]{0.5f, 0.95f, 0.5f},
                "xAxis", new float[]{1.0f, 0.0f, 0.0f},
                "yAxis", new float[]{0.0f, 0.0f, 1.0f},
                "direction", new float[]{0.0f, 1.0f, 0.0f}).entrySet()) {
            Field field = spec.getClass().getDeclaredField(expected.getKey());
            field.setAccessible(true);
            Object vectorSpec = field.get(spec);
            Field valueField = vectorSpec.getClass().getDeclaredField("value");
            valueField.setAccessible(true);
            Object vector = valueField.get(vectorSpec);
            for (int i = 0; i < components.length; i++) {
                Field component = vector.getClass().getDeclaredField(components[i]);
                component.setAccessible(true);
                assertEquals(expected.getValue()[i], component.getFloat(vector),
                        expected.getKey() + "." + components[i]);
            }
        }
    }

    @Test
    void parseJsonRuleRejectsAnInvalidRadius() {
        Identifier id = Identifier.fromNamespaceAndPath("custom", "rtx_remix/block_lights/bad.json");

        assertThrows(RuntimeException.class, () -> BlockLightRuleManager.parseJsonRule(id, JsonParser.parseString("""
                {
                  "blocks": ["minecraft:torch"],
                  "light": {
                    "type": "sphere",
                    "radius": 0.0
                  }
                }
                """).getAsJsonObject()));
    }

    @Test
    void loadRulesReplacesASamePathResourceInPackOrder() throws Exception {
        Identifier ruleId = Identifier.fromNamespaceAndPath("custom", "rtx_remix/block_lights/replaced.json");
        Resource lowPriority = jsonResource("low", """
                {
                  "blocks": ["minecraft:torch"],
                  "light": { "type": "sphere" }
                }
                """);
        Resource highPriority = jsonResource("high", """
                {
                  "blocks": ["minecraft:lantern"],
                  "light": { "type": "sphere" }
                }
                """);
        ResourceManager manager = new FakeResourceManager(Map.of(ruleId, List.of(lowPriority, highPriority)));

        BlockLightRuleManager.Rule rule = BlockLightRuleManager.loadRules(manager, Map.of()).get(ruleId);

        assertEquals(Set.of(Identifier.fromNamespaceAndPath("minecraft", "lantern")), parsedBlockIds(rule));
    }

    @Test
    void theMatchTablePrecomputesImmutableResults() {
        List<String> states = List.of("torch", "stone", "lantern");
        List<String> rules = List.of("all", "torch-only");
        AtomicInteger matcherCalls = new AtomicInteger();
        List<String>[] matchesByRawId = BlockLightRuleManager.precomputeMatches(
                states.size(), states::get, rules,
                (rule, state) -> {
                    matcherCalls.incrementAndGet();
                    return rule.equals("all") || rule.equals("torch-only") && state.equals("torch");
                });

        assertEquals(states.size() * rules.size(), matcherCalls.get());
        List<String> torchMatches = matchesByRawId[0];
        List<String> stoneMatches = matchesByRawId[1];
        assertEquals(List.of("all", "torch-only"), torchMatches);
        assertEquals(List.of("all"), stoneMatches);
        assertThrows(UnsupportedOperationException.class, () -> torchMatches.add("another"));
    }

    @Test
    void candidatePlansPreserveRuleOrderAndReevaluateOnlyDynamicCandidates() {
        List<String> states = List.of("lit", "unlit");
        List<String> rules = List.of(
                "tag-first", "explicit-hybrid", "static-middle", "irrelevant", "tag-last");
        BlockLightRuleManager.CandidatePlan<String>[] plans =
                BlockLightRuleManager.precomputeCandidatePlans(
                        states.size(), states::get, rules,
                        (rule, state) -> state.equals("lit")
                                && (rule.equals("explicit-hybrid") || rule.equals("static-middle")),
                        (rule, state) -> state.equals("lit")
                                && (rule.equals("tag-first")
                                || rule.equals("explicit-hybrid")
                                || rule.equals("tag-last")));

        BlockLightRuleManager.CandidatePlan<String> litPlan = plans[0];
        assertEquals(4, litPlan.size());
        assertEquals(List.of("tag-first", "explicit-hybrid", "static-middle", "tag-last"),
                candidateRules(litPlan));
        assertTrue(litPlan.requiresDynamicMatch(0));
        assertFalse(litPlan.requiresDynamicMatch(1),
                "An explicit match must not be downgraded to a dynamic tag check");
        assertFalse(litPlan.requiresDynamicMatch(2));
        assertTrue(litPlan.requiresDynamicMatch(3));
        assertEquals(0, plans[1].size());

        AtomicInteger dynamicChecks = new AtomicInteger();
        BiPredicate<String, String> firstTagSnapshot = (rule, state) -> {
            dynamicChecks.incrementAndGet();
            return rule.equals("tag-last");
        };
        assertEquals(List.of("explicit-hybrid", "static-middle", "tag-last"),
                matchingCandidates(litPlan, "lit", firstTagSnapshot));
        assertEquals(2, dynamicChecks.get(),
                "Only tag-dependent candidates should be evaluated dynamically");

        dynamicChecks.set(0);
        BiPredicate<String, String> secondTagSnapshot = (rule, state) -> {
            dynamicChecks.incrementAndGet();
            return rule.equals("tag-first");
        };
        assertEquals(List.of("tag-first", "explicit-hybrid", "static-middle"),
                matchingCandidates(litPlan, "lit", secondTagSnapshot));
        assertEquals(2, dynamicChecks.get(),
                "Changing tag membership must not require rebuilding the candidate plan");
    }

    @Test
    void reloadAtomicallyPublishesFullyPrecomputedCatalog() throws Exception {
        Field activeCatalogField = BlockLightRuleManager.class.getDeclaredField("activeCatalog");
        activeCatalogField.setAccessible(true);
        Object previousCatalog = activeCatalogField.get(null);
        BlockingResourceManager manager = new BlockingResourceManager();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        Future<?> reload = null;
        try {
            Object oldCatalog = activeCatalogField.get(null);
            Identifier newRuleId = Identifier.fromNamespaceAndPath("test", "new-rule");
            BlockLightRuleManager.Rule newRule = BlockLightRuleManager.rule(
                    newRuleId, state -> false, (id, state, pos, sampler) -> List.of());

            reload = executor.submit(() -> BlockLightRuleManager.reload(manager, Map.of(newRuleId, newRule)));
            assertTrue(manager.findEntered.await(5, TimeUnit.SECONDS));
            assertConcurrentCatalog(executor, activeCatalogField, oldCatalog, 7);

            manager.releaseFind.countDown();
            reload.get(5, TimeUnit.SECONDS);
            Object newCatalog = activeCatalogField.get(null);
            assertNotSame(oldCatalog, newCatalog);
            assertConcurrentCatalog(executor, activeCatalogField, newCatalog, 8);
        } finally {
            manager.releaseFind.countDown();
            executor.shutdownNow();
            try {
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            } finally {
                activeCatalogField.set(null, previousCatalog);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<Identifier> parsedBlockIds(BlockLightRuleManager.Rule rule) throws Exception {
        Method method = rule.getClass().getDeclaredMethod("blockIds");
        method.setAccessible(true);
        return (Set<Identifier>) method.invoke(rule);
    }

    private static Resource jsonResource(String packId, String json) {
        IoSupplier<InputStream> input = () -> new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
        return new Resource(new FakePack(packId), input);
    }

    private static <S, R> List<R> matchingCandidates(
            BlockLightRuleManager.CandidatePlan<R> plan,
            S state,
            BiPredicate<? super R, ? super S> dynamicMatcher) {
        List<R> matching = new ArrayList<>();
        for (int i = 0; i < plan.size(); i++) {
            if (plan.matches(i, state, dynamicMatcher)) {
                matching.add(plan.ruleAt(i));
            }
        }
        return matching;
    }

    private static <R> List<R> candidateRules(BlockLightRuleManager.CandidatePlan<R> plan) {
        List<R> rules = new ArrayList<>(plan.size());
        for (int i = 0; i < plan.size(); i++) {
            rules.add(plan.ruleAt(i));
        }
        return rules;
    }

    private static void assertConcurrentCatalog(ExecutorService executor, Field activeCatalogField,
                                                Object expected, int readers) throws Exception {
        List<Future<?>> futures = new ArrayList<>();
        for (int reader = 0; reader < readers; reader++) {
            futures.add(executor.submit(() -> {
                for (int iteration = 0; iteration < 10_000; iteration++) {
                    try {
                        assertSame(expected, activeCatalogField.get(null));
                    } catch (IllegalAccessException e) {
                        throw new AssertionError(e);
                    }
                }
            }));
        }
        for (Future<?> future : futures) future.get(5, TimeUnit.SECONDS);
    }

    private static final class BlockingResourceManager implements ResourceManager {
        private final CountDownLatch findEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFind = new CountDownLatch(1);

        @Override
        public Set<String> getNamespaces() {
            return Set.of();
        }

        @Override
        public Optional<Resource> getResource(Identifier identifier) {
            return Optional.empty();
        }

        @Override
        public List<Resource> getResourceStack(Identifier id) {
            return List.of();
        }

        @Override
        public Map<Identifier, Resource> listResources(String startingPath,
                                                       ResourceManager.Selector allowedPathPredicate) {
            return Map.of();
        }

        @Override
        public Map<Identifier, List<Resource>> listResourceStacks(String startingPath,
                                                                ResourceManager.Selector allowedPathPredicate) {
            findEntered.countDown();
            try {
                if (!releaseFind.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to complete resource reload");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Resource reload interrupted", e);
            }
            return Map.of();
        }

        @Override
        public Stream<PackResources> listPacks() {
            return Stream.empty();
        }
    }

    private record FakeResourceManager(Map<Identifier, List<Resource>> resources) implements ResourceManager {
        @Override
        public Set<String> getNamespaces() {
            return Set.of("custom");
        }

        @Override
        public Optional<Resource> getResource(Identifier identifier) {
            List<Resource> found = resources.get(identifier);
            return found == null || found.isEmpty() ? Optional.empty() : Optional.of(found.getLast());
        }

        @Override
        public List<Resource> getResourceStack(Identifier id) {
            return resources.getOrDefault(id, List.of());
        }

        @Override
        public Map<Identifier, Resource> listResources(String startingPath, ResourceManager.Selector allowedPathPredicate) {
            return Map.of();
        }

        @Override
        public Map<Identifier, List<Resource>> listResourceStacks(String startingPath, ResourceManager.Selector allowedPathPredicate) {
            return resources.entrySet().stream()
                    .filter(entry -> entry.getKey().getPath().startsWith(startingPath))
                    .filter(entry -> allowedPathPredicate.isIncluded(entry.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        @Override
        public Stream<PackResources> listPacks() {
            return Stream.empty();
        }
    }

    private record FakePack(String id) implements PackResources {
        @Override
        public IoSupplier<InputStream> getRootResource(String... segments) {
            return null;
        }

        @Override
        public IoSupplier<InputStream> getResource(PackType type, Identifier id) {
            return null;
        }

        @Override
        public void listResources(PackType type, String namespace, String prefix, ResourceOutput consumer) {
        }

        @Override
        public Set<String> getNamespaces(PackType type) {
            return Set.of();
        }

        @Override
        public <T> T getMetadataSection(MetadataSectionType<T> metaReader) throws IOException {
            return null;
        }

        @Override
        public PackLocationInfo location() {
            return new PackLocationInfo(id, Component.literal(id), PackSource.DEFAULT, Optional.<KnownPack>empty());
        }

        @Override
        public void close() {
        }
    }
}
