/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.logic;

import com.nvidia.rtxremix.RemixException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Exercises the pure parts of ConfLayerDriver: the smoothing step, the
 * send-threshold decision, layer discovery, and catalog invariants. No Remix
 * runtime needed; the layer catalog itself is plain data.
 */
class ConfLayerDriverTest {

    @Test
    void weatherStrengthsBeginAtZero() {
        ConfLayerDriver.WeatherStrengths weather = new ConfLayerDriver().weatherStrengths();
        assertEquals(0.0f, weather.rain());
        assertEquals(0.0f, weather.thunder());
    }

    @Test
    void twoHalfStepsEqualOneFullStep() {
        float full = ConfLayerDriver.smoothStep(0.0f, 1.0f, 0.5f);
        float halved = ConfLayerDriver.smoothStep(
                ConfLayerDriver.smoothStep(0.0f, 1.0f, 0.25f), 1.0f, 0.25f);
        assertEquals(full, halved, 1e-6f, "exponential decay must compose across substeps");
    }

    @Test
    void decayMatchesSmoothingFactorTwo() {
        // 2^(-2 * 0.5) = 0.5: half the gap remains after half a second.
        assertEquals(0.5f, ConfLayerDriver.smoothStep(0.0f, 1.0f, 0.5f), 1e-6f);
        // 2^(-2 * 1.0) = 0.25 of the gap remains after a full second.
        assertEquals(0.75f, ConfLayerDriver.smoothStep(0.0f, 1.0f, 1.0f), 1e-6f);
    }

    @Test
    void snapsWhenGapFallsBelowEpsilon() {
        // Already inside the snap band: lands exactly on the target.
        assertEquals(1.0f, ConfLayerDriver.smoothStep(1.0f - 0.0009f, 1.0f, 0.016f));
        // Decays into the snap band during the step.
        assertEquals(1.0f, ConfLayerDriver.smoothStep(1.0f - 0.0012f, 1.0f, 1.0f));
        // Well outside the band: no snap.
        assertNotEquals(1.0f, ConfLayerDriver.smoothStep(0.0f, 1.0f, 0.016f));
    }

    @Test
    void subEpsilonDeltasSkipTheSend() {
        assertFalse(ConfLayerDriver.shouldSend(0.50005f, 1.0f, 0.5f));
        assertFalse(ConfLayerDriver.shouldSend(0.5f, 1.0f, 0.5f));
    }

    @Test
    void settledValueAlwaysSendsExactly() {
        // Within SEND_EPSILON of lastSent but settled on the target: must send
        // so the runtime ends on the exact authored strength.
        assertTrue(ConfLayerDriver.shouldSend(1.0f, 1.0f, 1.0f - 5.0e-5f));
        // Already sent: never resend.
        assertFalse(ConfLayerDriver.shouldSend(1.0f, 1.0f, 1.0f));
    }

    @Test
    void deltasAccumulateAgainstLastSentUntilWorthSending() {
        // Creep toward a target 0.004 away at 60 FPS: every per-frame delta is
        // below SEND_EPSILON, but deltas accumulate against lastSent, so the
        // transition must still produce sends and finish exactly on target.
        float target = 0.504f;
        float value = 0.5f;
        float lastSent = 0.5f;
        int sends = 0;
        for (int frame = 0; frame < 10_000 && lastSent != target; frame++) {
            float previous = value;
            value = ConfLayerDriver.smoothStep(value, target, 1.0f / 60.0f);
            assertTrue(Math.abs(value - previous) <= ConfLayerDriver.SEND_EPSILON || value == target,
                    "test premise: per-frame deltas stay sub-epsilon until the snap");
            if (ConfLayerDriver.shouldSend(value, target, lastSent)) {
                lastSent = value;
                sends++;
            }
        }
        assertTrue(sends > 1, "accumulated sub-epsilon deltas must eventually send");
        assertEquals(target, lastSent, "the settled value must be sent exactly");
    }

    // --- fixed time-of-day schedule ---

    @Test
    void timeScheduleHoldsDayAndNight() {
        assertTime(ConfLayerDriver.timeLayerStrengths(1_000), 0.0f, 1.0f, 0.0f, 0.0f);
        assertTime(ConfLayerDriver.timeLayerStrengths(6_000), 0.0f, 1.0f, 0.0f, 0.0f);
        assertTime(ConfLayerDriver.timeLayerStrengths(10_999), 0.0f, 1.0f, 0.0f, 0.0f);

        assertTime(ConfLayerDriver.timeLayerStrengths(13_000), 0.0f, 0.0f, 0.0f, 1.0f);
        assertTime(ConfLayerDriver.timeLayerStrengths(18_000), 0.0f, 0.0f, 0.0f, 1.0f);
        assertTime(ConfLayerDriver.timeLayerStrengths(22_999), 0.0f, 0.0f, 0.0f, 1.0f);
    }

    @Test
    void timeScheduleUsesSmoothstepTransitions() {
        assertTime(ConfLayerDriver.timeLayerStrengths(500), 1.0f, 0.5f, 0.0f, 0.0f);
        assertTime(ConfLayerDriver.timeLayerStrengths(11_500), 0.0f, 1.0f, 0.5f, 0.0f);
        assertTime(ConfLayerDriver.timeLayerStrengths(12_500), 0.0f, 0.0f, 1.0f, 0.5f);
        assertTime(ConfLayerDriver.timeLayerStrengths(23_500), 1.0f, 0.0f, 0.0f, 0.5f);

        // smoothstep(0.25) = 0.15625, which catches an accidental linear ramp.
        assertTime(ConfLayerDriver.timeLayerStrengths(250), 1.0f, 0.15625f, 0.0f, 0.0f);
        assertTime(ConfLayerDriver.timeLayerStrengths(23_250), 1.0f, 0.0f, 0.0f, 0.84375f);
    }

    @Test
    void timeScheduleIsContinuousAtEveryBoundary() {
        assertTime(ConfLayerDriver.timeLayerStrengths(0), 1.0f, 0.0f, 0.0f, 0.0f);
        assertTime(ConfLayerDriver.timeLayerStrengths(1_000), 0.0f, 1.0f, 0.0f, 0.0f);
        assertTime(ConfLayerDriver.timeLayerStrengths(11_000), 0.0f, 1.0f, 0.0f, 0.0f);
        assertTime(ConfLayerDriver.timeLayerStrengths(12_000), 0.0f, 0.0f, 1.0f, 0.0f);
        assertTime(ConfLayerDriver.timeLayerStrengths(13_000), 0.0f, 0.0f, 0.0f, 1.0f);
        // Both are full at the hidden-anchor swap, but night wins at its
        // higher priority; subsequent ticks fade night away over dawn.
        assertTime(ConfLayerDriver.timeLayerStrengths(23_000), 1.0f, 0.0f, 0.0f, 1.0f);
    }

    @Test
    void timeScheduleNormalizesWorldTime() {
        assertEquals(ConfLayerDriver.timeLayerStrengths(0),
                ConfLayerDriver.timeLayerStrengths(24_000));
        assertEquals(ConfLayerDriver.timeLayerStrengths(500),
                ConfLayerDriver.timeLayerStrengths(48_500));
        assertEquals(ConfLayerDriver.timeLayerStrengths(23_999),
                ConfLayerDriver.timeLayerStrengths(-1));
    }

    @Test
    void timeTargetsResolveToExactProfileLerps() {
        float dawn = 10.0f;
        float day = 20.0f;
        float dusk = 30.0f;
        float night = 40.0f;

        assertEquals(15.0f, resolveTimeValue(ConfLayerDriver.timeLayerStrengths(500),
                dawn, day, dusk, night), 1e-6f);
        assertEquals(25.0f, resolveTimeValue(ConfLayerDriver.timeLayerStrengths(11_500),
                dawn, day, dusk, night), 1e-6f);
        assertEquals(35.0f, resolveTimeValue(ConfLayerDriver.timeLayerStrengths(12_500),
                dawn, day, dusk, night), 1e-6f);
        assertEquals(25.0f, resolveTimeValue(ConfLayerDriver.timeLayerStrengths(23_500),
                dawn, day, dusk, night), 1e-6f);
    }

    @Test
    void statusSummaryHealthyQuietIsOneLine() {
        List<ConfLayerDriver.LayerStatus> layers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            layers.add(new ConfLayerDriver.LayerStatus(LogicChannels.BIOME_PREFIX + "b" + i, 0.0f, true));
        }
        assertEquals(List.of("Conf layers: 0 active, 8 idle, 0 unacquired"),
                ConfLayerDriver.formatStatusSummary(layers));
    }

    @Test
    void statusSummaryNamesAFewUnacquiredLayers() {
        List<ConfLayerDriver.LayerStatus> layers = new ArrayList<>();
        layers.add(new ConfLayerDriver.LayerStatus(LogicChannels.UNDERWATER, 1.0f, true));
        layers.add(new ConfLayerDriver.LayerStatus(LogicChannels.BIOME_PREFIX + "plains", 0.0f, true));
        layers.add(new ConfLayerDriver.LayerStatus(LogicChannels.WEATHER_RAIN, 0.0f, false));
        layers.add(new ConfLayerDriver.LayerStatus(LogicChannels.WEATHER_THUNDER, 0.0f, false));
        assertEquals(List.of(
                        "Conf layers: 1 active, 1 idle, 2 unacquired",
                        "  mc.underwater = 1.0",
                        "  unacquired: mc.weather.rain, mc.weather.thunder (see log)"),
                ConfLayerDriver.formatStatusSummary(layers));
    }

    @Test
    void statusSummaryCollapsesBulkUnacquiredAndCapsActive() {
        // A deliberately large authored catalog: the active block stays
        // capped at 10 lines (9 rows + overflow) and bulk anomalies collapse
        // to per-category counts - 12 lines total.
        List<ConfLayerDriver.LayerStatus> layers = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            layers.add(new ConfLayerDriver.LayerStatus(
                    LogicChannels.ZONE_PREFIX + String.format("z%02d", i), 0.5f, true));
        }
        for (int i = 0; i < 6; i++) {
            layers.add(new ConfLayerDriver.LayerStatus(LogicChannels.BIOME_PREFIX + "b" + i, 0.0f, false));
        }
        layers.add(new ConfLayerDriver.LayerStatus(LogicChannels.VIEW_DISTANCE, 0.0f, false));
        layers.add(new ConfLayerDriver.LayerStatus(LogicChannels.VOLUMETRICS_SETTLING, 0.0f, false));
        layers.add(new ConfLayerDriver.LayerStatus(LogicChannels.WEATHER_RAIN, 0.0f, false));
        layers.add(new ConfLayerDriver.LayerStatus(LogicChannels.WEATHER_THUNDER, 0.0f, false));
        layers.add(new ConfLayerDriver.LayerStatus(LogicChannels.TIME_DAWN, 0.0f, false));
        layers.add(new ConfLayerDriver.LayerStatus(LogicChannels.TIME_DAY, 0.0f, false));
        layers.add(new ConfLayerDriver.LayerStatus(LogicChannels.TIME_DUSK, 0.0f, false));
        layers.add(new ConfLayerDriver.LayerStatus(LogicChannels.TIME_NIGHT, 0.0f, false));

        var lines = ConfLayerDriver.formatStatusSummary(layers);
        assertEquals(12, lines.size(), "worst case is bounded at 12 lines");
        assertEquals("Conf layers: 12 active, 0 idle, 14 unacquired", lines.get(0));
        assertEquals("  mc.zone.z00 = 0.5", lines.get(1));
        assertEquals("  mc.zone.z08 = 0.5", lines.get(9));
        assertEquals("  +3 more active (see /rtx conf status all)", lines.get(10));
        assertEquals("  unacquired: 6 biomes, 2 weather, 4 time profiles, 1 view distance, "
                + "1 volumetric settling (see log; /rtx conf status all)", lines.get(11));
    }

    // --- volumetric settling envelope ------------------------------------

    @Test
    void movementIsJudgedAtTheSendTolerance() {
        assertFalse(ConfLayerDriver.moved(0.5f, 0.5f));
        assertFalse(ConfLayerDriver.moved(0.5f, 0.5f + 0.5e-4f));
        assertTrue(ConfLayerDriver.moved(0.5f, 0.5f + 2.0e-4f));
        assertTrue(ConfLayerDriver.moved(0.5f, 0.5f - 2.0e-4f));
    }

    @Test
    void aSmoothedLayerCountsAsEasingUntilItSnaps() {
        // The snap band (0.001) is wider than the send tolerance, so a
        // smoothed layer is either visibly easing or exactly on target; there
        // is no dead zone in which the settling envelope would release early.
        float current = 0.0f;
        int frames = 0;
        while (current != 1.0f) {
            assertTrue(ConfLayerDriver.moved(current, 1.0f), "frame " + frames);
            current = ConfLayerDriver.smoothStep(current, 1.0f, 1.0f / 60.0f);
            frames++;
        }
        assertFalse(ConfLayerDriver.moved(current, 1.0f));
        assertTrue(frames > 60, "a full swing eases for seconds, not frames: " + frames);
    }

    @Test
    void settlingEnvelopeStartsReleased() {
        var envelope = new ConfLayerDriver.SettlingEnvelope();
        assertEquals(0.0f, envelope.advance(false, 1.0f / 60.0f));
    }

    @Test
    void settlingEnvelopeIsFullWhileAnInputMoves() {
        var envelope = new ConfLayerDriver.SettlingEnvelope();
        for (int frame = 0; frame < 600; frame++) {
            assertEquals(1.0f, envelope.advance(true, 1.0f / 60.0f), "frame " + frame);
        }
    }

    @Test
    void settlingEnvelopeHoldsForThreeQuartersOfASecondAfterTheLastMovement() {
        var envelope = new ConfLayerDriver.SettlingEnvelope();
        envelope.advance(true, 1.0f / 60.0f);
        assertEquals(1.0f, envelope.advance(false, 0.5f), "still held at 0.5 s");
        assertEquals(1.0f, envelope.advance(false, 0.2f), "still held at 0.7 s");
        assertEquals(0.0f, envelope.advance(false, 0.1f), "released past 0.75 s");
        assertEquals(0.0f, envelope.advance(false, 1.0f / 60.0f), "stays released");
    }

    @Test
    void settlingEnvelopeRestartsTheHoldOnEveryMovement() {
        var envelope = new ConfLayerDriver.SettlingEnvelope();
        envelope.advance(true, 1.0f / 60.0f);
        envelope.advance(false, 0.7f);
        envelope.advance(true, 1.0f / 60.0f);
        assertEquals(1.0f, envelope.advance(false, 0.7f),
                "the hold restarts from the most recent movement");
    }

    // --- inert profiles --------------------------------------------------

    @Test
    void aCommentOnlyConfSetsNoOptions() {
        // The shipped plains.conf shape: prose plus a commented-out example
        // assignment, which must not read as an option.
        assertFalse(ConfLayerDriver.confSetsOptions(
                "# Sample profile, blank on purpose.\n"
                        + "# rtx.volumetrics.transmittanceMeasurementDistanceMeters = 120.0\n"));
    }

    @Test
    void aConfOfBlankLinesSetsNoOptions() {
        assertFalse(ConfLayerDriver.confSetsOptions(""));
        assertFalse(ConfLayerDriver.confSetsOptions("\n\n  \n\t\r\n"));
    }

    @Test
    void aConfWithOneOptionSetsOptions() {
        assertTrue(ConfLayerDriver.confSetsOptions(
                "# header\n\n  rtx.volumetrics.transmittanceMeasurementDistanceMeters = 120.0\n"));
    }

    // --- layer table invariants ---

    @Test
    void staticTableHasNineUniqueRows() {
        // 1 water + 1 view-distance controller + 1 volumetric settling
        // controller + 2 weather + 4 time profiles. Optional biome and zone
        // rows are discovered from disk.
        assertEquals(67, ConfLayerDriver.vanillaBiomeNames().size(),
                "vanilla 26.3 biome count (Biomes)");
        assertEquals(9, ConfLayerDriver.STATIC_LAYER_TABLE.size());
        Set<String> names = new HashSet<>();
        Set<String> paths = new HashSet<>();
        for (var spec : ConfLayerDriver.STATIC_LAYER_TABLE) {
            assertTrue(names.add(spec.name()), "duplicate layer name: " + spec.name());
            assertTrue(paths.add(spec.confPath()), "duplicate conf path: " + spec.confPath());
        }
    }

    @Test
    void staticTableHasNoDiscoveredRows() {
        for (var spec : ConfLayerDriver.STATIC_LAYER_TABLE) {
            assertFalse(spec.name().startsWith(LogicChannels.ZONE_PREFIX),
                    "zone layers must come from the confs/zones/ scan, not the static table: " + spec.name());
            assertFalse(spec.name().startsWith(LogicChannels.BIOME_PREFIX),
                    "biome layers must come from the confs/biomes/ scan, not the static table: " + spec.name());
        }
    }

    @Test
    void biomeStemsMapToDiscoveredLayerRows() {
        var rows = ConfLayerDriver.biomeRowsFromStems(List.of("swamp", "plains"), ConfLayerDriver.LayerSource.MOD);
        assertEquals(2, rows.size());
        assertEquals(LogicChannels.BIOME_PREFIX + "plains", rows.get(0).name()); // sorted
        assertEquals("confs/biomes/plains.conf", rows.get(0).confPath());
        assertEquals(LogicChannels.BIOME_PREFIX + "swamp", rows.get(1).name());
    }

    @Test
    void unknownBiomeStemsAreSkipped() {
        assertTrue(ConfLayerDriver.biomeRowsFromStems(List.of(), ConfLayerDriver.LayerSource.MOD).isEmpty());
        var rows = ConfLayerDriver.biomeRowsFromStems(
                List.of("", "PLAINS", "not_a_biome", "two words", "plains"),
                ConfLayerDriver.LayerSource.MOD);
        assertEquals(1, rows.size());
        assertEquals(LogicChannels.BIOME_PREFIX + "plains", rows.get(0).name());
    }

    @Test
    void zoneStemsMapToDiscoveredLayerRows() {
        // The conf FILE NAME is the zone channel contract: mc.zone.<stem>,
        // matching how ZoneRegistry keys weights by mc.zone.<name>.
        var rows = ConfLayerDriver.zoneRowsFromStems(List.of("lighthouse", "cave"), ConfLayerDriver.LayerSource.MOD);
        assertEquals(2, rows.size());
        assertEquals(LogicChannels.ZONE_PREFIX + "cave", rows.get(0).name());       // sorted
        assertEquals("confs/zones/cave.conf", rows.get(0).confPath());
        assertEquals(LogicChannels.ZONE_PREFIX + "lighthouse", rows.get(1).name());
    }

    @Test
    void invalidZoneStemsAreSkipped() {
        assertTrue(ConfLayerDriver.zoneRowsFromStems(List.of(), ConfLayerDriver.LayerSource.MOD).isEmpty());
        var rows = ConfLayerDriver.zoneRowsFromStems(List.of("", "--flag", "two words", "ok"), ConfLayerDriver.LayerSource.MOD);
        assertEquals(1, rows.size());
        assertEquals(LogicChannels.ZONE_PREFIX + "ok", rows.get(0).name());
    }

    @Test
    void prioritiesMatchTheDocumentedBands() {
        for (var spec : representativeLayerSpecs()) {
            float expected;
            if (spec.name().equals(LogicChannels.UNDERWATER)) {
                expected = 20000f;
            } else if (spec.name().equals(LogicChannels.VIEW_DISTANCE)) {
                expected = 3000f;
            } else if (spec.name().equals(LogicChannels.VOLUMETRICS_SETTLING)) {
                expected = 3100f;
            } else if (spec.name().startsWith(LogicChannels.ZONE_PREFIX)) {
                expected = 11000f;
            } else if (spec.name().startsWith(LogicChannels.BIOME_PREFIX)) {
                expected = 6000f;
            } else if (spec.name().equals(LogicChannels.WEATHER_THUNDER)) {
                expected = 5500f;
            } else if (spec.name().equals(LogicChannels.WEATHER_RAIN)) {
                expected = 5000f;
            } else if (spec.name().equals(LogicChannels.TIME_DAWN)) {
                expected = 4000f;
            } else if (spec.name().equals(LogicChannels.TIME_DAY)) {
                expected = 4001f;
            } else if (spec.name().equals(LogicChannels.TIME_DUSK)) {
                expected = 4002f;
            } else if (spec.name().equals(LogicChannels.TIME_NIGHT)) {
                expected = 4003f;
            } else {
                fail("layer outside the documented priority bands: " + spec.name());
                return;
            }
            assertEquals(expected, spec.priority(), spec.name());
            assertNotEquals(10000f, spec.priority(),
                    "10000 is the runtime's default dynamic-layer priority");
        }
    }

    @Test
    void blendThresholdsMatchTheAuthoredLayerBehavior() {
        for (var spec : representativeLayerSpecs()) {
            float expected = spec.name().equals(LogicChannels.UNDERWATER)
                    || spec.name().startsWith(LogicChannels.TIME_PREFIX) ? 0.5f : 0.01f;
            assertEquals(expected, spec.blendThreshold(), spec.name());
        }
    }

    @Test
    void onlyBiomeAndZoneProfilesAreSmoothed() {
        // Everything else already arrives as a curve (vanilla's weather
        // gradients, the time clock, the settling envelope) or must switch at
        // once (water, render distance).
        for (var spec : representativeLayerSpecs()) {
            boolean expectedSmoothed = spec.name().startsWith(LogicChannels.BIOME_PREFIX)
                    || spec.name().startsWith(LogicChannels.ZONE_PREFIX);
            assertEquals(expectedSmoothed, spec.smoothed(), spec.name() + " smoothed flag");
        }
    }

    @Test
    void onlyWaterGatesTheDriverOnAcquireFailure() {
        // water.conf replaces the legacy volumetric path, so losing it must
        // disable the driver; every other layer is an additive extra that is
        // skipped and retried on reload instead.
        assertTrue(ConfLayerDriver.acquireFailureDisablesDriver(LogicChannels.UNDERWATER));
        assertFalse(ConfLayerDriver.acquireFailureDisablesDriver(LogicChannels.VIEW_DISTANCE));
        assertFalse(ConfLayerDriver.acquireFailureDisablesDriver(LogicChannels.VOLUMETRICS_SETTLING));
        assertFalse(ConfLayerDriver.acquireFailureDisablesDriver(LogicChannels.ZONE_PREFIX + "cave"));
        assertFalse(ConfLayerDriver.acquireFailureDisablesDriver(LogicChannels.BIOME_PREFIX + "swamp"));
        assertFalse(ConfLayerDriver.acquireFailureDisablesDriver(LogicChannels.WEATHER_RAIN));
        assertFalse(ConfLayerDriver.acquireFailureDisablesDriver(LogicChannels.TIME_DUSK));
        int gateLayers = 0;
        for (var spec : ConfLayerDriver.STATIC_LAYER_TABLE) {
            if (ConfLayerDriver.acquireFailureDisablesDriver(spec.name())) {
                gateLayers++;
                assertEquals(LogicChannels.UNDERWATER, spec.name());
            }
        }
        assertEquals(1, gateLayers, "exactly one gate layer in the table");
    }

    @Test
    void biomeFilesAreRediscoveredOnReload(@TempDir Path gameDir) throws IOException {
        Path biomeDir = gameDir.resolve("rtx-remix").resolve("mods")
                .resolve("minecraft-logic").resolve("confs").resolve("biomes");
        Files.createDirectories(biomeDir);
        Path plains = biomeDir.resolve("plains.conf");
        Files.writeString(plains, "# plains test profile\n");

        FakeConfigLayerApi api = new FakeConfigLayerApi();
        ConfLayerDriver driver = new ConfLayerDriver();
        assertTrue(driver.acquireAll(api, gameDir));
        Map<String, ConfLayerDriver.LayerStatus> firstStatus = statusesByName(driver);
        assertTrue(firstStatus.get(LogicChannels.BIOME_PREFIX + "plains").acquired());
        assertFalse(firstStatus.containsKey(LogicChannels.BIOME_PREFIX + "swamp"));

        Files.delete(plains);
        Files.writeString(biomeDir.resolve("swamp.conf"), "# swamp test profile\n");
        assertTrue(driver.reload());

        Map<String, ConfLayerDriver.LayerStatus> reloadedStatus = statusesByName(driver);
        assertFalse(reloadedStatus.containsKey(LogicChannels.BIOME_PREFIX + "plains"));
        assertTrue(reloadedStatus.get(LogicChannels.BIOME_PREFIX + "swamp").acquired());
        assertTrue(api.wasReleased("confs/biomes/plains.conf"),
                "the removed biome's old handle must be released before rediscovery");
    }

    @Test
    void incompleteTimeFamilyIsAtomicAndReloadable(@TempDir Path gameDir) {
        FakeConfigLayerApi api = new FakeConfigLayerApi();
        api.failDusk = true;
        ConfLayerDriver driver = new ConfLayerDriver();

        assertTrue(driver.acquireAll(api, gameDir),
                "a time-profile failure must not disable unrelated conf layers");
        Map<String, ConfLayerDriver.LayerStatus> firstStatus = statusesByName(driver);
        for (String timeLayer : List.of(LogicChannels.TIME_DAWN, LogicChannels.TIME_DAY,
                LogicChannels.TIME_DUSK, LogicChannels.TIME_NIGHT)) {
            assertFalse(firstStatus.get(timeLayer).acquired(), timeLayer);
        }
        assertTrue(firstStatus.get(LogicChannels.UNDERWATER).acquired());
        assertTrue(firstStatus.get(LogicChannels.WEATHER_RAIN).acquired());
        assertEquals(Set.of("confs/time/dawn.conf", "confs/time/day.conf", "confs/time/night.conf"),
                api.releasedTimePaths(),
                "successfully acquired siblings must be released when dusk fails");

        api.failDusk = false;
        assertTrue(driver.reload());
        Map<String, ConfLayerDriver.LayerStatus> reloadedStatus = statusesByName(driver);
        for (String timeLayer : List.of(LogicChannels.TIME_DAWN, LogicChannels.TIME_DAY,
                LogicChannels.TIME_DUSK, LogicChannels.TIME_NIGHT)) {
            assertTrue(reloadedStatus.get(timeLayer).acquired(), timeLayer);
        }
        assertTrue(reloadedStatus.get(LogicChannels.UNDERWATER).acquired());
    }

    /** A map's own profiles live inside its save, so they arrive on join and leave on disconnect. */
    @Test
    void aJoinedWorldAddsItsProfilesAboveTheModArchetypes(@TempDir Path gameDir, @TempDir Path worldDir)
            throws IOException {
        Path modZones = gameDir.resolve("rtx-remix").resolve("mods").resolve("minecraft-logic")
                .resolve("confs").resolve("zones");
        Files.createDirectories(modZones);
        Files.writeString(modZones.resolve("cave.conf"), "# shared archetype\n");

        Path worldZones = worldDir.resolve("rtx-remix").resolve("confs").resolve("zones");
        Files.createDirectories(worldZones);
        Files.writeString(worldZones.resolve("cave.conf"), "# this map's take on cave\n");
        Files.writeString(worldZones.resolve("ruins.conf"), "# map-only profile\n");

        FakeConfigLayerApi api = new FakeConfigLayerApi();
        ConfLayerDriver driver = new ConfLayerDriver();
        assertTrue(driver.acquireAll(api, gameDir));
        assertTrue(driver.hasAcquiredLayer(LogicChannels.ZONE_PREFIX + "cave"));
        assertFalse(driver.hasAcquiredLayer(LogicChannels.ZONE_PREFIX + "ruins"),
                "the map's profile must not be visible before its world is joined");

        driver.onWorldJoin(worldDir);

        Map<String, ConfLayerDriver.LayerStatus> joined = statusesByName(driver);
        String worldCave = ConfLayerDriver.WORLD_ID_PREFIX + LogicChannels.ZONE_PREFIX + "cave";
        String worldRuins = ConfLayerDriver.WORLD_ID_PREFIX + LogicChannels.ZONE_PREFIX + "ruins";
        assertTrue(joined.get(LogicChannels.ZONE_PREFIX + "cave").acquired(),
                "the mod archetype keeps driving so it can still fill in options the map omits");
        assertTrue(joined.get(worldCave).acquired(),
                "a same-named map profile is acquired alongside the archetype, not instead of it");
        assertTrue(joined.get(worldRuins).acquired());
        assertTrue(driver.hasAcquiredLayer(LogicChannels.ZONE_PREFIX + "ruins"),
                "a channel driven only by the world still counts as driven");
        assertTrue(api.priorityOf(worldZones.resolve("cave.conf"))
                        > api.priorityOf(modZones.resolve("cave.conf")),
                "the map's profile must outrank the archetype for shared options");
    }

    @Test
    void leavingAWorldReleasesOnlyItsProfiles(@TempDir Path gameDir, @TempDir Path worldDir)
            throws IOException {
        Path modZones = gameDir.resolve("rtx-remix").resolve("mods").resolve("minecraft-logic")
                .resolve("confs").resolve("zones");
        Files.createDirectories(modZones);
        Files.writeString(modZones.resolve("cave.conf"), "# shared archetype\n");
        Path worldZones = worldDir.resolve("rtx-remix").resolve("confs").resolve("zones");
        Files.createDirectories(worldZones);
        Files.writeString(worldZones.resolve("ruins.conf"), "# map-only profile\n");

        FakeConfigLayerApi api = new FakeConfigLayerApi();
        ConfLayerDriver driver = new ConfLayerDriver();
        assertTrue(driver.acquireAll(api, gameDir));
        driver.onWorldJoin(worldDir);
        assertTrue(api.isLive(worldZones.resolve("ruins.conf")));

        driver.onWorldLeave();

        assertFalse(api.isLive(worldZones.resolve("ruins.conf")),
                "the map's layer must be released when its world is left");
        assertTrue(api.isLive(modZones.resolve("cave.conf")),
                "the mod catalog keeps driving after leaving a world");
        assertFalse(driver.hasAcquiredLayer(LogicChannels.ZONE_PREFIX + "ruins"));
        assertTrue(driver.isActive());
        assertFalse(statusesByName(driver)
                        .containsKey(ConfLayerDriver.WORLD_ID_PREFIX + LogicChannels.ZONE_PREFIX + "ruins"),
                "a left world's rows must not linger in status");
    }

    @Test
    void joiningAWorldWithoutProfilesDropsThePreviousWorlds(@TempDir Path gameDir,
                                                            @TempDir Path firstWorld,
                                                            @TempDir Path plainWorld) throws IOException {
        Files.createDirectories(gameDir.resolve("rtx-remix").resolve("mods").resolve("minecraft-logic")
                .resolve("confs").resolve("zones"));
        Path firstZones = firstWorld.resolve("rtx-remix").resolve("confs").resolve("zones");
        Files.createDirectories(firstZones);
        Files.writeString(firstZones.resolve("ruins.conf"), "# first map\n");

        FakeConfigLayerApi api = new FakeConfigLayerApi();
        ConfLayerDriver driver = new ConfLayerDriver();
        assertTrue(driver.acquireAll(api, gameDir));
        driver.onWorldJoin(firstWorld);
        assertTrue(api.isLive(firstZones.resolve("ruins.conf")));

        // Second world ships no rtx-remix payload at all.
        driver.onWorldJoin(plainWorld);

        assertFalse(api.isLive(firstZones.resolve("ruins.conf")),
                "one map's look must not bleed into the next world");
        assertFalse(driver.hasAcquiredLayer(LogicChannels.ZONE_PREFIX + "ruins"));
    }

    @Test
    void reloadReacquiresBothRoots(@TempDir Path gameDir, @TempDir Path worldDir) throws IOException {
        Files.createDirectories(gameDir.resolve("rtx-remix").resolve("mods").resolve("minecraft-logic")
                .resolve("confs").resolve("zones"));
        Path worldZones = worldDir.resolve("rtx-remix").resolve("confs").resolve("zones");
        Files.createDirectories(worldZones);
        Files.writeString(worldZones.resolve("ruins.conf"), "# map-only profile\n");

        FakeConfigLayerApi api = new FakeConfigLayerApi();
        ConfLayerDriver driver = new ConfLayerDriver();
        assertTrue(driver.acquireAll(api, gameDir));
        driver.onWorldJoin(worldDir);

        // An author edits the map's conf and adds another, then reloads in-game.
        Files.writeString(worldZones.resolve("ruins.conf"), "# retuned\n");
        Files.writeString(worldZones.resolve("crypt.conf"), "# new map profile\n");
        assertTrue(driver.reload());

        Map<String, ConfLayerDriver.LayerStatus> reloaded = statusesByName(driver);
        assertTrue(reloaded.get(ConfLayerDriver.WORLD_ID_PREFIX + LogicChannels.ZONE_PREFIX + "ruins")
                .acquired(), "reload must re-read the world root, not just the mod root");
        assertTrue(reloaded.get(ConfLayerDriver.WORLD_ID_PREFIX + LogicChannels.ZONE_PREFIX + "crypt")
                .acquired(), "a conf dropped into the world root becomes live on reload");
        assertTrue(api.isLive(worldZones.resolve("crypt.conf")));
    }

    private static Map<String, ConfLayerDriver.LayerStatus> statusesByName(ConfLayerDriver driver) {
        Map<String, ConfLayerDriver.LayerStatus> statuses = new HashMap<>();
        for (ConfLayerDriver.LayerStatus status : driver.snapshot()) {
            statuses.put(status.name(), status);
        }
        return statuses;
    }

    private static final class FakeConfigLayerApi implements ConfLayerDriver.ConfigLayerApi {
        private final Map<Long, String> livePaths = new HashMap<>();
        private final List<String> releasedPaths = new ArrayList<>();
        private final Map<String, Float> priorities = new HashMap<>();
        private long nextHandle = 1L;
        boolean failDusk;
        boolean failWater;

        @Override
        public long acquireConfigLayer(String path, float priority, float blendThreshold) {
            String normalized = path.replace('\\', '/');
            if (failDusk && normalized.endsWith("confs/time/dusk.conf")) {
                throw new RemixException("simulated dusk acquire failure");
            }
            if (failWater && normalized.endsWith("confs/water.conf")) {
                throw new RemixException("simulated water acquire failure");
            }
            long handle = nextHandle++;
            livePaths.put(handle, normalized);
            priorities.put(normalized, priority);
            return handle;
        }

        float priorityOf(Path conf) {
            String key = conf.toAbsolutePath().toString().replace('\\', '/');
            Float priority = priorities.get(key);
            assertNotNull(priority, "no layer was acquired from " + key);
            return priority;
        }

        boolean isLive(Path conf) {
            String key = conf.toAbsolutePath().toString().replace('\\', '/');
            return livePaths.containsValue(key);
        }

        @Override
        public void setConfigLayerBlending(long handle, float strength, float blendThreshold) {
        }

        @Override
        public void releaseConfigLayer(long handle) {
            String path = livePaths.remove(handle);
            assertNotNull(path, "released handle must be live");
            releasedPaths.add(path);
        }

        Set<String> releasedTimePaths() {
            Set<String> relative = new HashSet<>();
            for (String path : releasedPaths) {
                int index = path.lastIndexOf("confs/time/");
                if (index >= 0) {
                    relative.add(path.substring(index));
                }
            }
            return relative;
        }

        boolean wasReleased(String suffix) {
            return releasedPaths.stream().anyMatch(path -> path.endsWith(suffix));
        }
    }

    private static List<ConfLayerDriver.LayerSpec> representativeLayerSpecs() {
        List<ConfLayerDriver.LayerSpec> specs = new ArrayList<>(ConfLayerDriver.STATIC_LAYER_TABLE);
        specs.addAll(ConfLayerDriver.biomeRowsFromStems(List.of("plains"), ConfLayerDriver.LayerSource.MOD));
        specs.addAll(ConfLayerDriver.zoneRowsFromStems(List.of("cave"), ConfLayerDriver.LayerSource.MOD));
        return specs;
    }

    private static void assertTime(ConfLayerDriver.TimeLayerStrengths actual,
                                   float dawn, float day, float dusk, float night) {
        assertAll(
                () -> assertEquals(dawn, actual.dawn(), 1e-6f, "dawn"),
                () -> assertEquals(day, actual.day(), 1e-6f, "day"),
                () -> assertEquals(dusk, actual.dusk(), 1e-6f, "dusk"),
                () -> assertEquals(night, actual.night(), 1e-6f, "night"));
    }

    /** Reproduces the runtime's ascending-priority nested LERP for one scalar option. */
    private static float resolveTimeValue(ConfLayerDriver.TimeLayerStrengths strengths,
                                          float dawn, float day, float dusk, float night) {
        float resolved = -100.0f; // lower-stack value must disappear once an endpoint is full
        resolved = lerp(resolved, dawn, strengths.dawn());
        resolved = lerp(resolved, day, strengths.day());
        resolved = lerp(resolved, dusk, strengths.dusk());
        return lerp(resolved, night, strengths.night());
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    @Test
    void tableAndShippedConfCatalogMatchBothWays() throws IOException {
        // Catches catalog drift in both directions. Fixed rows need files;
        // discovered biome/zone files need valid stems. Only plains.conf ships
        // as the biome authoring sample.
        Path root = Path.of("packaging", "rtx-remix", "mods", "minecraft-logic");
        assertTrue(Files.isDirectory(root),
                "conf catalog not found at " + root.toAbsolutePath()
                        + " (this test expects the repo root as the working directory)");
        Set<String> tablePaths = new HashSet<>();
        for (var spec : ConfLayerDriver.STATIC_LAYER_TABLE) {
            tablePaths.add(spec.confPath());
            assertTrue(Files.isRegularFile(root.resolve(spec.confPath())),
                    "static table row has no conf on disk: " + spec.confPath());
        }
        Set<String> shippedBiomeStems = new HashSet<>();
        Path confsDir = root.resolve("confs");
        try (var files = Files.walk(confsDir)) {
            for (Path conf : (Iterable<Path>) files
                    .filter(path -> path.toString().endsWith(".conf"))::iterator) {
                String relative = "confs/" + confsDir.relativize(conf).toString().replace('\\', '/');
                String fileName = conf.getFileName().toString();
                if (relative.startsWith("confs/zones/")) {
                    assertEquals(confsDir.resolve("zones"), conf.getParent(),
                            "zone discovery scans only direct confs/zones/ children: " + relative);
                    assertTrue(ConfLayerDriver.isValidZoneStem(
                                    fileName.substring(0, fileName.length() - ".conf".length())),
                            "shipped zone conf has an invalid zone-name stem: " + relative);
                } else if (relative.startsWith("confs/biomes/")) {
                    assertEquals(confsDir.resolve("biomes"), conf.getParent(),
                            "biome discovery scans only direct confs/biomes/ children: " + relative);
                    String stem = fileName.substring(0, fileName.length() - ".conf".length());
                    assertTrue(ConfLayerDriver.vanillaBiomeNames().contains(stem),
                            "shipped biome conf is not an exact vanilla-biome stem: " + relative);
                    shippedBiomeStems.add(stem);
                } else {
                    assertTrue(tablePaths.contains(relative),
                            "shipped conf not driven by any static table row: " + relative);
                }
            }
        }
        assertEquals(Set.of("plains"), shippedBiomeStems,
                "ship one reusable biome sample instead of a full stub catalog");
        assertFalse(ConfLayerDriver.confSetsOptions(
                        Files.readString(confsDir.resolve("biomes").resolve("plains.conf"))),
                "the shipped plains sample must remain a no-op until an author edits it");
    }
}
