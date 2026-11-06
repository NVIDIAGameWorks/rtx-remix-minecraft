/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.block;

import com.nvidia.rtxremix.minecraft.RtxRemixCommonMod;
import com.nvidia.rtxremix.minecraft.light.BeaconBeam;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Persistent optics, dye inventory, and articulated motion for one Copper Beacon. */
public final class CopperBeaconBlockEntity extends BlockEntity
        implements Container, ExtendedScreenHandlerFactory<CopperBeaconScreenData> {

    private static final Set<CopperBeaconBlockEntity> LOADED =
            ConcurrentHashMap.newKeySet();

    public static final float MIN_INTENSITY_MULTIPLIER = 0.0f;
    public static final float MAX_INTENSITY_MULTIPLIER = 1000.0f;
    public static final float MIN_CONE_ANGLE = 0.0f;
    public static final float MAX_CONE_ANGLE = 90.0f;
    public static final float MIN_SATURATION = 0.0f;
    public static final float MAX_SATURATION = 2.0f;
    public static final float DEFAULT_INTENSITY_MULTIPLIER = 1.0f;
    public static final float DEFAULT_CONE_ANGLE = BeaconBeam.CONE_ANGLE_DEGREES;
    public static final float DEFAULT_SATURATION = 1.0f;

    private static final String INTENSITY_KEY = "intensity_multiplier";
    private static final String CONE_ANGLE_KEY = "cone_angle";
    private static final String SATURATION_KEY = "saturation";
    private static final String REDSTONE_CONTROLLED_KEY = "redstone_controlled";
    private static final String FROZEN_TICK_KEY = "frozen_tick";
    private static final String YAW_KEY = "yaw";
    private static final String PITCH_KEY = "pitch";
    private static final String DYE_KEY = "dye";

    public static final BlockEntityType<CopperBeaconBlockEntity> TYPE =
            BlockEntityType.Builder.of(
                    CopperBeaconBlockEntity::new, CopperBeaconBlock.INSTANCE).build();

    private float intensityMultiplier = DEFAULT_INTENSITY_MULTIPLIER;
    private float coneAngle = DEFAULT_CONE_ANGLE;
    private float saturation = DEFAULT_SATURATION;
    private boolean redstoneControlled;
    private long frozenTick;
    private CopperBeaconAxisState yaw = CopperBeaconAxisState.defaults();
    private CopperBeaconAxisState pitch = CopperBeaconAxisState.defaults();
    private ItemStack dye = ItemStack.EMPTY;

    public CopperBeaconBlockEntity(BlockPos pos, BlockState state) {
        super(TYPE, pos, state);
    }

    public static Iterable<CopperBeaconBlockEntity> loaded() {
        return LOADED;
    }

    @Override
    public void setLevel(Level world) {
        super.setLevel(world);
        LOADED.add(this);
    }

    @Override
    public void setRemoved() {
        LOADED.remove(this);
        super.setRemoved();
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        if (level != null) {
            LOADED.add(this);
        }
    }

    static void register() {
        Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE,
                ResourceLocation.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, CopperBeaconBlock.ID),
                TYPE);
    }

    public float getAzimuth() {
        return yaw.position();
    }

    public void setAzimuth(float azimuth) {
        setYaw(new CopperBeaconAxisState(
                azimuth,
                yaw.mode(),
                yaw.rotationSpeed(),
                yaw.minimumOffset(),
                yaw.maximumOffset(),
                yaw.cycleSeconds(),
                yaw.patrolSeconds(),
                currentTick()));
    }

    public float getZenith() {
        return pitch.position();
    }

    public void setZenith(float zenith) {
        setPitch(new CopperBeaconAxisState(
                zenith,
                pitch.mode(),
                pitch.rotationSpeed(),
                pitch.minimumOffset(),
                pitch.maximumOffset(),
                pitch.cycleSeconds(),
                pitch.patrolSeconds(),
                currentTick()));
    }

    public void setAngles(float azimuth, float zenith) {
        long tick = currentTick();
        CopperBeaconAxisState newYaw = new CopperBeaconAxisState(
                azimuth,
                yaw.mode(),
                yaw.rotationSpeed(),
                yaw.minimumOffset(),
                yaw.maximumOffset(),
                yaw.cycleSeconds(),
                yaw.patrolSeconds(),
                tick);
        CopperBeaconAxisState newPitch = new CopperBeaconAxisState(
                zenith,
                pitch.mode(),
                pitch.rotationSpeed(),
                pitch.minimumOffset(),
                pitch.maximumOffset(),
                pitch.cycleSeconds(),
                pitch.patrolSeconds(),
                tick);
        if (yaw.equals(newYaw) && pitch.equals(newPitch)) {
            return;
        }
        yaw = newYaw;
        pitch = newPitch;
        markDirtyAndSync();
    }

    public float getIntensityMultiplier() {
        return intensityMultiplier;
    }

    public void setIntensityMultiplier(float value) {
        float sanitized = finiteClamped(
                value, MIN_INTENSITY_MULTIPLIER, MAX_INTENSITY_MULTIPLIER,
                DEFAULT_INTENSITY_MULTIPLIER);
        if (Float.compare(intensityMultiplier, sanitized) != 0) {
            intensityMultiplier = sanitized;
            markDirtyAndSync();
        }
    }

    public float getConeAngle() {
        return coneAngle;
    }

    public void setConeAngle(float value) {
        float sanitized = finiteClamped(
                value, MIN_CONE_ANGLE, MAX_CONE_ANGLE, DEFAULT_CONE_ANGLE);
        if (Float.compare(coneAngle, sanitized) != 0) {
            coneAngle = sanitized;
            markDirtyAndSync();
        }
    }

    public float getSaturation() {
        return saturation;
    }

    public void setSaturation(float value) {
        float sanitized = finiteClamped(
                value, MIN_SATURATION, MAX_SATURATION, DEFAULT_SATURATION);
        if (Float.compare(saturation, sanitized) != 0) {
            saturation = sanitized;
            markDirtyAndSync();
        }
    }

    public int getLensTint() {
        return dye.getItem() instanceof DyeItem dyeItem
                ? CopperBeaconState.applySaturation(
                        dyeItem.getDyeColor().getTextureDiffuseColor(), saturation)
                : 0xFFFFFFFF;
    }

    public boolean isRedstoneControlled() {
        return redstoneControlled;
    }

    public void setRedstoneControlled(boolean controlled) {
        if (redstoneControlled == controlled) {
            return;
        }
        redstoneControlled = controlled;
        if (controlled && !getBlockState().getValue(CopperBeaconBlock.POWERED)) {
            frozenTick = currentTick();
        }
        markDirtyAndSync();
    }

    public CopperBeaconAxisState getYaw() {
        return yaw;
    }

    public void setYaw(CopperBeaconAxisState yaw) {
        if (!this.yaw.equals(yaw)) {
            this.yaw = yaw;
            markDirtyAndSync();
        }
    }

    public CopperBeaconAxisState getPitch() {
        return pitch;
    }

    public void setPitch(CopperBeaconAxisState pitch) {
        if (!this.pitch.equals(pitch)) {
            this.pitch = pitch;
            markDirtyAndSync();
        }
    }

    public ItemStack getDye() {
        return dye;
    }

    public void setDye(ItemStack stack) {
        ItemStack replacement = stack != null
                        && !stack.isEmpty()
                        && stack.getItem() instanceof DyeItem
                ? stack.copyWithCount(1)
                : ItemStack.EMPTY;
        if (!ItemStack.matches(dye, replacement)) {
            dye = replacement;
            markDirtyAndSync();
        }
    }

    public boolean isActive() {
        return CopperBeaconState.isActive(
                redstoneControlled, getBlockState().getValue(CopperBeaconBlock.POWERED));
    }

    public double animationTick(float tickDelta) {
        return CopperBeaconState.animationTick(
                redstoneControlled,
                getBlockState().getValue(CopperBeaconBlock.POWERED),
                currentTick(),
                tickDelta,
                frozenTick);
    }

    public float yawAngle(float tickDelta) {
        return yawAngleAt(animationTick(tickDelta));
    }

    public float pitchAngle(float tickDelta) {
        return pitchAngleAt(animationTick(tickDelta));
    }

    public float yawAngleAt(double presentedWorldTick) {
        return yaw.angle(animationTick(presentedWorldTick), axisSeed(0x594157L));
    }

    public float pitchAngleAt(double presentedWorldTick) {
        return pitch.angle(animationTick(presentedWorldTick), axisSeed(0x5049544348L));
    }

    private double animationTick(double presentedWorldTick) {
        return redstoneControlled && !getBlockState().getValue(CopperBeaconBlock.POWERED)
                ? frozenTick
                : presentedWorldTick;
    }

    void onPoweredChanged(boolean powered, long worldTick) {
        if (redstoneControlled && !powered) {
            frozenTick = worldTick;
            markDirtyAndSync();
        }
    }

    @Override
    public CopperBeaconScreenData getScreenOpeningData(ServerPlayer player) {
        return new CopperBeaconScreenData(worldPosition);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.rtx-remix-renderer.copper_beacon");
    }

    @Override
    public AbstractContainerMenu createMenu(
            int syncId, Inventory playerInventory, Player player) {
        return new CopperBeaconScreenHandler(syncId, playerInventory, this);
    }

    @Override
    public int getContainerSize() {
        return 1;
    }

    @Override
    public boolean isEmpty() {
        return dye.isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot == 0 ? dye : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (slot != 0 || amount <= 0 || dye.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack removed = dye;
        dye = ItemStack.EMPTY;
        markDirtyAndSync();
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (slot != 0 || dye.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack removed = dye;
        dye = ItemStack.EMPTY;
        markDirtyAndSync();
        return removed;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot == 0) {
            setDye(stack);
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return !isRemoved()
                && level == player.level()
                && level.getBlockEntity(worldPosition) == this
                && player.distanceToSqr(
                        worldPosition.getX() + 0.5,
                        worldPosition.getY() + 0.5,
                        worldPosition.getZ() + 0.5) <= 64.0;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return slot == 0 && stack.getItem() instanceof DyeItem;
    }

    @Override
    public void clearContent() {
        if (!dye.isEmpty()) {
            dye = ItemStack.EMPTY;
            markDirtyAndSync();
        }
    }

    @Override
    protected void saveAdditional(CompoundTag nbt, HolderLookup.Provider registryLookup) {
        super.saveAdditional(nbt, registryLookup);
        nbt.putFloat(INTENSITY_KEY, intensityMultiplier);
        nbt.putFloat(CONE_ANGLE_KEY, coneAngle);
        nbt.putFloat(SATURATION_KEY, saturation);
        nbt.putBoolean(REDSTONE_CONTROLLED_KEY, redstoneControlled);
        nbt.putLong(FROZEN_TICK_KEY, frozenTick);
        CompoundTag yawNbt = new CompoundTag();
        yaw.writeNbt(yawNbt);
        nbt.put(YAW_KEY, yawNbt);
        CompoundTag pitchNbt = new CompoundTag();
        pitch.writeNbt(pitchNbt);
        nbt.put(PITCH_KEY, pitchNbt);
        if (!dye.isEmpty()) {
            nbt.put(DYE_KEY, dye.save(registryLookup));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag nbt, HolderLookup.Provider registryLookup) {
        super.loadAdditional(nbt, registryLookup);
        intensityMultiplier = finiteClamped(
                nbt.contains(INTENSITY_KEY)
                        ? nbt.getFloat(INTENSITY_KEY)
                        : DEFAULT_INTENSITY_MULTIPLIER,
                MIN_INTENSITY_MULTIPLIER,
                MAX_INTENSITY_MULTIPLIER,
                DEFAULT_INTENSITY_MULTIPLIER);
        coneAngle = finiteClamped(
                nbt.contains(CONE_ANGLE_KEY) ? nbt.getFloat(CONE_ANGLE_KEY) : DEFAULT_CONE_ANGLE,
                MIN_CONE_ANGLE,
                MAX_CONE_ANGLE,
                DEFAULT_CONE_ANGLE);
        saturation = finiteClamped(
                nbt.contains(SATURATION_KEY) ? nbt.getFloat(SATURATION_KEY) : DEFAULT_SATURATION,
                MIN_SATURATION,
                MAX_SATURATION,
                DEFAULT_SATURATION);
        redstoneControlled = nbt.getBoolean(REDSTONE_CONTROLLED_KEY);
        frozenTick = nbt.getLong(FROZEN_TICK_KEY);
        if (nbt.contains(YAW_KEY)) {
            yaw = CopperBeaconAxisState.readNbt(nbt.getCompound(YAW_KEY));
        } else {
            CopperBeaconState.Angles angles = CopperBeaconState.readAngles(nbt);
            yaw = new CopperBeaconAxisState(
                    angles.azimuth(), CopperBeaconAnimationMode.STATIC, 0.0f,
                    CopperBeaconAxisState.DEFAULT_MINIMUM_OFFSET,
                    CopperBeaconAxisState.DEFAULT_MAXIMUM_OFFSET,
                    CopperBeaconAxisState.DEFAULT_CYCLE_SECONDS,
                    CopperBeaconAxisState.DEFAULT_PATROL_SECONDS,
                    0L);
        }
        if (nbt.contains(PITCH_KEY)) {
            pitch = CopperBeaconAxisState.readNbt(nbt.getCompound(PITCH_KEY));
        } else {
            CopperBeaconState.Angles angles = CopperBeaconState.readAngles(nbt);
            pitch = new CopperBeaconAxisState(
                    angles.zenith(), CopperBeaconAnimationMode.STATIC, 0.0f,
                    CopperBeaconAxisState.DEFAULT_MINIMUM_OFFSET,
                    CopperBeaconAxisState.DEFAULT_MAXIMUM_OFFSET,
                    CopperBeaconAxisState.DEFAULT_CYCLE_SECONDS,
                    CopperBeaconAxisState.DEFAULT_PATROL_SECONDS,
                    0L);
        }
        dye = nbt.get(DYE_KEY) != null
                ? ItemStack.parse(registryLookup, nbt.getCompound(DYE_KEY)).orElse(ItemStack.EMPTY)
                : ItemStack.EMPTY;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registryLookup) {
        return saveWithoutMetadata(registryLookup);
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private long currentTick() {
        return level == null ? 0L : level.getGameTime();
    }

    private long axisSeed(long salt) {
        return worldPosition.asLong() ^ salt;
    }

    private void markDirtyAndSync() {
        setChanged();
        if (level instanceof ServerLevel serverWorld) {
            serverWorld.getChunkSource().blockChanged(worldPosition);
        }
    }

    private static float finiteClamped(float value, float minimum, float maximum, float fallback) {
        return Float.isFinite(value) ? Math.clamp(value, minimum, maximum) : fallback;
    }
}
