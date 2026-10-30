/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.block;

import com.nvidia.rtxremix.minecraft.RtxRemixCommonMod;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;

/** Server-authoritative inventory and settings channel for one Copper Beacon. */
public final class CopperBeaconScreenHandler extends AbstractContainerMenu {

    public static final int WIDTH = 260;
    public static final int DYE_SLOT_X = 32;
    public static final int DYE_SLOT_Y = 17;
    public static final int PLAYER_INVENTORY_X = 49;
    public static final int PLAYER_INVENTORY_Y = 153;

    public static final MenuType<CopperBeaconScreenHandler> TYPE =
            new ExtendedMenuType<>(
                    CopperBeaconScreenHandler::new,
                    CopperBeaconScreenData.PACKET_CODEC);

    private static final int DYE_SLOT = 0;
    private static final int PLAYER_START = 1;
    private static final int PLAYER_END = PLAYER_START + 27;
    private static final int HOTBAR_END = PLAYER_END + 9;

    private final Container dyeInventory;
    private final BlockPos pos;
    private final CopperBeaconBlockEntity beacon;

    public static void register() {
        Registry.register(
                BuiltInRegistries.MENU,
                Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, CopperBeaconBlock.ID),
                TYPE);
    }

    private CopperBeaconScreenHandler(
            int syncId, Inventory playerInventory, CopperBeaconScreenData data) {
        this(
                syncId,
                playerInventory,
                clientBeacon(playerInventory, data.pos()),
                data.pos());
    }

    public CopperBeaconScreenHandler(
            int syncId,
            Inventory playerInventory,
            CopperBeaconBlockEntity beacon) {
        this(syncId, playerInventory, beacon, beacon.getBlockPos());
    }

    private CopperBeaconScreenHandler(
            int syncId,
            Inventory playerInventory,
            CopperBeaconBlockEntity beacon,
            BlockPos pos) {
        super(TYPE, syncId);
        this.beacon = beacon;
        this.pos = pos.immutable();
        this.dyeInventory = beacon != null ? beacon : new SimpleContainer(1);

        addSlot(new Slot(dyeInventory, DYE_SLOT, DYE_SLOT_X, DYE_SLOT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.getItem() instanceof DyeItem;
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        });

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(
                        playerInventory,
                        column + row * 9 + 9,
                        PLAYER_INVENTORY_X + column * 18,
                        PLAYER_INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(
                    playerInventory,
                    column,
                    PLAYER_INVENTORY_X + column * 18,
                    PLAYER_INVENTORY_Y + 58));
        }
    }

    public BlockPos getPos() {
        return pos;
    }

    public CopperBeaconBlockEntity getBeacon() {
        return beacon;
    }

    public void applyUpdate(CopperBeaconUpdatePayload.Field field, float value) {
        if (beacon == null || beacon.isRemoved()) {
            return;
        }
        switch (field) {
            case INTENSITY -> beacon.setIntensityMultiplier(value);
            case CONE_ANGLE -> beacon.setConeAngle(value);
            case SATURATION -> beacon.setSaturation(value);
            case REDSTONE_CONTROLLED -> beacon.setRedstoneControlled(value >= 0.5f);
            case YAW_POSITION -> beacon.setYaw(withAxisValue(
                    beacon.getYaw(), field, value));
            case YAW_MODE -> beacon.setYaw(withAxisValue(
                    beacon.getYaw(), field, value));
            case YAW_ROTATION_SPEED -> beacon.setYaw(withAxisValue(
                    beacon.getYaw(), field, value));
            case YAW_MINIMUM_OFFSET -> beacon.setYaw(withAxisValue(
                    beacon.getYaw(), field, value));
            case YAW_MAXIMUM_OFFSET -> beacon.setYaw(withAxisValue(
                    beacon.getYaw(), field, value));
            case YAW_CYCLE_SECONDS -> beacon.setYaw(withAxisValue(
                    beacon.getYaw(), field, value));
            case YAW_PATROL_SECONDS -> beacon.setYaw(withAxisValue(
                    beacon.getYaw(), field, value));
            case PITCH_POSITION -> beacon.setPitch(withAxisValue(
                    beacon.getPitch(), field, value));
            case PITCH_MODE -> beacon.setPitch(withAxisValue(
                    beacon.getPitch(), field, value));
            case PITCH_ROTATION_SPEED -> beacon.setPitch(withAxisValue(
                    beacon.getPitch(), field, value));
            case PITCH_MINIMUM_OFFSET -> beacon.setPitch(withAxisValue(
                    beacon.getPitch(), field, value));
            case PITCH_MAXIMUM_OFFSET -> beacon.setPitch(withAxisValue(
                    beacon.getPitch(), field, value));
            case PITCH_CYCLE_SECONDS -> beacon.setPitch(withAxisValue(
                    beacon.getPitch(), field, value));
            case PITCH_PATROL_SECONDS -> beacon.setPitch(withAxisValue(
                    beacon.getPitch(), field, value));
        }
    }

    @Override
    public boolean stillValid(Player player) {
        if (beacon == null || beacon.isRemoved() || beacon.getLevel() != player.level()) {
            return false;
        }
        if (player.level().getBlockEntity(pos) != beacon) {
            return false;
        }
        return player.distanceToSqr(
                pos.getX() + 0.5,
                pos.getY() + 0.5,
                pos.getZ() + 0.5) <= 64.0;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        Slot slot = slots.get(slotIndex);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();

        if (slotIndex == DYE_SLOT) {
            if (!moveItemStackTo(stack, PLAYER_START, HOTBAR_END, true)) {
                return ItemStack.EMPTY;
            }
        } else if (stack.getItem() instanceof DyeItem) {
            if (!moveItemStackTo(stack, DYE_SLOT, DYE_SLOT + 1, false)) {
                return ItemStack.EMPTY;
            }
        } else if (slotIndex < PLAYER_END) {
            if (!moveItemStackTo(stack, PLAYER_END, HOTBAR_END, false)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, PLAYER_START, PLAYER_END, false)) {
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == original.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stack);
        return original;
    }

    private static CopperBeaconBlockEntity clientBeacon(
            Inventory inventory, BlockPos pos) {
        return inventory.player.level().getBlockEntity(pos)
                        instanceof CopperBeaconBlockEntity beacon
                ? beacon
                : null;
    }

    private CopperBeaconAxisState withAxisValue(
            CopperBeaconAxisState state,
            CopperBeaconUpdatePayload.Field field,
            float value) {
        float position = state.position();
        CopperBeaconAnimationMode mode = state.mode();
        float rotationSpeed = state.rotationSpeed();
        float minimumOffset = state.minimumOffset();
        float maximumOffset = state.maximumOffset();
        float cycleSeconds = state.cycleSeconds();
        float patrolSeconds = state.patrolSeconds();
        switch (field) {
            case YAW_POSITION, PITCH_POSITION -> position = value;
            case YAW_MODE, PITCH_MODE ->
                    mode = CopperBeaconAnimationMode.byId(Math.round(value));
            case YAW_ROTATION_SPEED, PITCH_ROTATION_SPEED -> rotationSpeed = value;
            case YAW_MINIMUM_OFFSET, PITCH_MINIMUM_OFFSET -> minimumOffset = value;
            case YAW_MAXIMUM_OFFSET, PITCH_MAXIMUM_OFFSET -> maximumOffset = value;
            case YAW_CYCLE_SECONDS, PITCH_CYCLE_SECONDS -> cycleSeconds = value;
            case YAW_PATROL_SECONDS, PITCH_PATROL_SECONDS -> patrolSeconds = value;
            default -> {
                return state;
            }
        }
        long anchorTick = beacon.getLevel() == null ? state.anchorTick() : beacon.getLevel().getGameTime();
        return new CopperBeaconAxisState(
                position,
                mode,
                rotationSpeed,
                minimumOffset,
                maximumOffset,
                cycleSeconds,
                patrolSeconds,
                anchorTick);
    }
}
