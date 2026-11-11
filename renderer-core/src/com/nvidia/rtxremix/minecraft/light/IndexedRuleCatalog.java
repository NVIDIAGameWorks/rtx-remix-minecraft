/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.IntFunction;

/**
 * Precomputes immutable raw-state rule matches while retaining explicitly
 * marked dynamic candidates for evaluation against the live host state.
 */
public final class IndexedRuleCatalog {
    private IndexedRuleCatalog() {
    }

    public static final class CandidatePlan<R> {
        private static final CandidatePlan<?> EMPTY =
                new CandidatePlan<Object>(
                        Collections.emptyList(), new boolean[0]);

        private final List<R> rules;
        private final boolean[] dynamicMatches;

        private CandidatePlan(List<R> rules, boolean[] dynamicMatches) {
            this.rules = rules;
            this.dynamicMatches = dynamicMatches;
        }

        public int size() {
            return rules.size();
        }

        public R ruleAt(int index) {
            return rules.get(index);
        }

        public boolean requiresDynamicMatch(int index) {
            return dynamicMatches[index];
        }

        public <S> boolean matches(
                int index,
                S state,
                BiPredicate<? super R, ? super S> matcher) {
            return !dynamicMatches[index] || matcher.test(rules.get(index), state);
        }

        @SuppressWarnings("unchecked")
        private static <R> CandidatePlan<R> empty() {
            return (CandidatePlan<R>) EMPTY;
        }
    }

    @SuppressWarnings("unchecked")
    public static <S, R> List<R>[] precomputeMatches(
            int stateCount,
            IntFunction<S> stateByRawId,
            Iterable<R> rules,
            BiPredicate<R, S> matcher) {
        List<R>[] matchesByState = (List<R>[]) new List<?>[stateCount];
        for (int rawId = 0; rawId < matchesByState.length; rawId++) {
            S state = stateByRawId.apply(rawId);
            if (state == null) {
                matchesByState[rawId] = Collections.emptyList();
                continue;
            }

            List<R> matches = null;
            for (R rule : rules) {
                if (!matcher.test(rule, state)) {
                    continue;
                }
                if (matches == null) {
                    matches = new ArrayList<R>();
                }
                matches.add(rule);
            }
            matchesByState[rawId] = matches == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<R>(matches));
        }
        return matchesByState;
    }

    @SuppressWarnings("unchecked")
    public static <S, R> CandidatePlan<R>[] precomputeCandidatePlans(
            int stateCount,
            IntFunction<S> stateByRawId,
            Iterable<R> rules,
            BiPredicate<R, S> staticMatcher,
            BiPredicate<R, S> dynamicCandidate) {
        CandidatePlan<R>[] plansByState =
                (CandidatePlan<R>[]) new CandidatePlan<?>[stateCount];
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
                    candidates = new ArrayList<R>();
                    dynamicMatches = new ArrayList<Boolean>();
                }
                candidates.add(rule);
                dynamicMatches.add(Boolean.valueOf(!staticMatch));
            }

            if (candidates == null) {
                plansByState[rawId] = CandidatePlan.empty();
                continue;
            }
            boolean[] flags = new boolean[dynamicMatches.size()];
            for (int i = 0; i < flags.length; i++) {
                flags[i] = dynamicMatches.get(i).booleanValue();
            }
            plansByState[rawId] = new CandidatePlan<R>(
                    Collections.unmodifiableList(new ArrayList<R>(candidates)),
                    flags);
        }
        return plansByState;
    }
}
