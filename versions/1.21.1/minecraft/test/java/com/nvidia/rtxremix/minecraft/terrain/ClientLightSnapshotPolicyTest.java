/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import com.nvidia.rtxremix.minecraft.terrain.ClientLightSnapshotPolicy.Decision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientLightSnapshotPolicyTest {

    private static ClientLightSnapshotPolicy clientEngine() {
        return new ClientLightSnapshotPolicy(true, true);
    }

    @Test
    void clientEngineAliasesEveryPassWhileCaptureIsOn() {
        ClientLightSnapshotPolicy policy = clientEngine();
        assertEquals(Decision.ALIAS_LIVE_MAP, policy.onNotifyChanges(true, false));
        assertEquals(Decision.ALIAS_LIVE_MAP, policy.onNotifyChanges(true, false));
        assertTrue(policy.releaseLiveMap(), "an aliased storage needs a real copy");
    }

    @Test
    void firstPassAfterCaptureOffRepublishesOnceThenRunsVanilla() {
        ClientLightSnapshotPolicy policy = clientEngine();
        policy.onNotifyChanges(true, false);
        assertEquals(Decision.REPUBLISH_THEN_VANILLA, policy.onNotifyChanges(false, false));
        assertEquals(Decision.VANILLA, policy.onNotifyChanges(false, false));
        assertFalse(policy.releaseLiveMap(), "the republish already released the alias");
    }

    @Test
    void toggleOffHookReleasesAliasSoTheNextPassIsPlainVanilla() {
        ClientLightSnapshotPolicy policy = clientEngine();
        policy.onNotifyChanges(true, false);
        assertTrue(policy.releaseLiveMap(), "aliased snapshot needs a real copy");
        assertFalse(policy.releaseLiveMap(), "a second release has nothing to copy");
        assertEquals(Decision.VANILLA, policy.onNotifyChanges(false, false));
    }

    @Test
    void neverAliasedStorageNeedsNoRepublish() {
        ClientLightSnapshotPolicy policy = clientEngine();
        assertFalse(policy.releaseLiveMap());
        assertEquals(Decision.VANILLA, policy.onNotifyChanges(false, false));
    }

    @Test
    void captureOnAgainAliasesAgain() {
        ClientLightSnapshotPolicy policy = clientEngine();
        policy.onNotifyChanges(true, false);
        assertEquals(Decision.REPUBLISH_THEN_VANILLA, policy.onNotifyChanges(false, false));
        assertEquals(Decision.ALIAS_LIVE_MAP, policy.onNotifyChanges(true, false));
        assertEquals(Decision.REPUBLISH_THEN_VANILLA, policy.onNotifyChanges(false, false),
                "the second alias needs its own real copy");
    }

    @Test
    void vanillaBuildInFlightKeepsRealCopiesUntilItFinishes() {
        ClientLightSnapshotPolicy policy = clientEngine();
        assertEquals(Decision.VANILLA, policy.onNotifyChanges(true, true));
        assertEquals(Decision.VANILLA, policy.onNotifyChanges(true, true),
                "a deferred alias leaves nothing to republish");
        assertEquals(Decision.ALIAS_LIVE_MAP, policy.onNotifyChanges(true, false));
    }

    @Test
    void killSwitchKeepsVanillaSnapshots() {
        ClientLightSnapshotPolicy policy = new ClientLightSnapshotPolicy(true, false);
        assertEquals(Decision.VANILLA, policy.onNotifyChanges(true, false));
        assertFalse(policy.releaseLiveMap());
        assertEquals(Decision.VANILLA, policy.onNotifyChanges(false, false));
    }

    @Test
    void serverEngineNeverAliases() {
        ClientLightSnapshotPolicy policy = new ClientLightSnapshotPolicy(false, true);
        assertEquals(Decision.VANILLA, policy.onNotifyChanges(true, false));
        assertEquals(Decision.VANILLA, policy.onNotifyChanges(false, false));
        assertFalse(policy.releaseLiveMap());
    }

    @Test
    void killSwitchPropertyOnlyDisablesWhenTrue() {
        assertTrue(ClientLightSnapshotPolicy.aliasAllowedFrom(null));
        assertTrue(ClientLightSnapshotPolicy.aliasAllowedFrom("false"));
        assertTrue(ClientLightSnapshotPolicy.aliasAllowedFrom(""));
        assertFalse(ClientLightSnapshotPolicy.aliasAllowedFrom("true"));
        assertFalse(ClientLightSnapshotPolicy.aliasAllowedFrom("TRUE"));
    }
}
