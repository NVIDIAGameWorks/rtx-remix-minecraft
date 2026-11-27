/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.block;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopperBeaconBlockEntityTest {

    @Test
    void anglesAreNormalizedClampedAndPersisted() {
        CompoundTag nbt = new CompoundTag();
        CopperBeaconState.writeAngles(
                nbt,
                CopperBeaconState.normalizeAzimuth(540.0f),
                CopperBeaconState.clampZenith(120.0f));
        CopperBeaconState.Angles restored = CopperBeaconState.readAngles(nbt);

        assertEquals(-180.0f, restored.azimuth());
        assertEquals(90.0f, restored.zenith());
        assertThrows(
                IllegalArgumentException.class,
                () -> CopperBeaconState.normalizeAzimuth(Float.NaN));
        assertThrows(
                IllegalArgumentException.class,
                () -> CopperBeaconState.clampZenith(Float.POSITIVE_INFINITY));
    }

    @Test
    void everyMountAndPlayerPointingDirectionChoosesTheExpectedForwardAxis() {
        for (Direction mount : Direction.values()) {
            for (Direction playerLook : Direction.values()) {
                for (Direction horizontalFacing : Direction.Plane.HORIZONTAL) {
                    Direction preferred = playerLook.getOpposite();
                    Direction horizontalFallback = horizontalFacing.getOpposite();
                    Direction actual = CopperBeaconState.orthogonalForward(
                            preferred, horizontalFallback, mount);
                    String placement = "mount=" + mount
                            + ", playerLook=" + playerLook
                            + ", horizontalFacing=" + horizontalFacing;

                    assertNotSame(mount.getAxis(), actual.getAxis(), placement);
                }
            }
        }

        assertEquals(Direction.WEST, CopperBeaconState.orthogonalForward(
                Direction.WEST, Direction.SOUTH, Direction.UP));
        assertEquals(Direction.NORTH, CopperBeaconState.orthogonalForward(
                Direction.DOWN, Direction.NORTH, Direction.UP));
        assertEquals(Direction.NORTH, CopperBeaconState.orthogonalForward(
                Direction.WEST, Direction.EAST, Direction.EAST));
        assertEquals(Direction.EAST, CopperBeaconState.orthogonalForward(
                Direction.NORTH, Direction.SOUTH, Direction.SOUTH));
    }

    @Test
    void redstoneControlRequiresPowerOnlyWhenEnabled() {
        assertTrue(CopperBeaconState.isActive(false, false));
        assertTrue(CopperBeaconState.isActive(false, true));
        assertFalse(CopperBeaconState.isActive(true, false));
        assertTrue(CopperBeaconState.isActive(true, true));
    }

    @Test
    void saturationPreservesAlphaAndInterpolatesFromGrayToTheDyeColor() {
        int dye = 0x7FFF0000;

        assertEquals(0x7F363636, CopperBeaconState.applySaturation(dye, 0.0f));
        assertEquals(0x7F9B1B1B, CopperBeaconState.applySaturation(dye, 0.5f));
        assertEquals(dye, CopperBeaconState.applySaturation(dye, 1.0f));
        assertEquals(0x7F363636, CopperBeaconState.applySaturation(dye, -1.0f));
        assertEquals(
                0x7F9C5C1C,
                CopperBeaconState.applySaturation(0x7F806040, 2.0f));
        assertEquals(
                0x7F9C5C1C,
                CopperBeaconState.applySaturation(0x7F806040, 3.0f));
    }

    @Test
    void unpoweredRedstoneControlFreezesBothAnimationAxes() {
        assertEquals(
                123.0,
                CopperBeaconState.animationTick(true, false, 500L, 0.75f, 123L));
        assertEquals(
                500.75,
                CopperBeaconState.animationTick(true, true, 500L, 0.75f, 123L));
        assertEquals(
                500.75,
                CopperBeaconState.animationTick(false, false, 500L, 0.75f, 123L));
    }

    @Test
    void everyBeaconModelResourceIsPresentAndValidJson() {
        for (String path : new String[]{
                "assets/rtx-remix-renderer/models/block/copper_beacon.json",
                "assets/rtx-remix-renderer/models/block/copper_beacon_wall.json",
                "assets/rtx-remix-renderer/models/block/copper_beacon_complete.json",
                "assets/rtx-remix-renderer/models/item/copper_beacon.json",
                "assets/rtx-remix-renderer/blockstates/copper_beacon.json"}) {
            var stream = CopperBeaconBlockEntityTest.class.getClassLoader()
                    .getResourceAsStream(path);
            assertNotNull(stream, path);
            JsonObject json = JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            assertNotNull(json, path);
        }
    }

    @Test
    void lensTexturesMatchTheSixPixelGeometry() throws IOException {
        for (String path : new String[]{
                "assets/rtx-remix-renderer/textures/block/copper_beacon_lens.png",
                "assets/rtx-remix-renderer/textures/block/copper_beacon_lens_s.png"}) {
            try (var stream = CopperBeaconBlockEntityTest.class.getClassLoader()
                    .getResourceAsStream(path)) {
                assertNotNull(stream, path);
                var image = ImageIO.read(stream);
                assertNotNull(image, path);
                assertEquals(6, image.getWidth(), path);
                assertEquals(6, image.getHeight(), path);
            }
        }
    }
}
