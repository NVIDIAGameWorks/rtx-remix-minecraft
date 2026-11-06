/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.block;

import com.nvidia.rtxremix.minecraft.RtxRemixCommonMod;
import com.nvidia.rtxremix.minecraft.light.LightEntry;
import com.nvidia.rtxremix.minecraft.light.OverridesItemLight;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/**
 * Base for aimable light sources, the lamp room of a lighthouse.
 *
 * <p>Emits a lens-sized Remix disk light with cone shaping, i.e. a
 * <em>spot</em> light.
 *
 * <p>Concrete subclasses differ only in registry id, lens texture, and the
 * colour of the Remix spot light they emit, mirroring how vanilla's
 * {@code BulbBlock} holds the shared behaviour while each copper-oxidation
 * block supplies its own settings.
 *
 * <p>Carries the vanilla {@link net.minecraft.world.level.block.state.properties.BlockStateProperties#FACING}
 * property (all six directions), set to the opposite of the placing player's
 * look direction so the lens faces them, matching vanilla dispensers and
 * observers.
 *
 * <p>Using {@code Properties.FACING} rather than a custom property is
 * deliberate: {@code BlockLightRuleManager}'s {@code StateVectorSpec} reads that
 * exact property for its {@code "facing"}, {@code "facing_opposite"}, and
 * {@code "facing_axis"} vector sources, so a block light rule can aim a Remix
 * light along this block's facing with no additional code.
 *
 * <p>Implements {@link OverridesItemLight}, which suppresses the <em>generic</em>
 * item light {@link LightManager} would otherwise attach to any light-emitting
 * {@code BlockItem}: a beam at world radiance, held at arm's length, would white
 * the whole view out. A held beacon is not dark, though: it gets a dedicated
 * beam from {@link LightManager}, sharing this block's base cone shape but scaled
 * by {@link BeaconBeam#HELD_INTENSITY_SCALE}. The problem was the radiance, not
 * the beam, so it is solved by scaling rather than exclusion.
 * A <em>dropped</em> beacon does stay dark, an item entity having no aim worth
 * pointing a beam along.
 */
public abstract class SpotLightBlock extends DirectionalBlock implements OverridesItemLight {

    /** Full block light level, matching a lantern. */
    protected static final int LUMINANCE = 15;

    /**
     * Vanilla's "Functional Blocks" creative tab, where torches and lanterns live.
     *
     * <p>Built from the registry id rather than {@code ItemGroups.FUNCTIONAL},
     * which is private in 1.21.1 and would otherwise need an access-widener entry.
     */
    private static final ResourceKey<CreativeModeTab> FUNCTIONAL_BLOCKS =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB, ResourceLocation.withDefaultNamespace("functional_blocks"));

    private final LightEntry.ColorOverride beamColor;

    protected SpotLightBlock(BlockBehaviour.Properties settings, LightEntry.ColorOverride beamColor) {
        super(settings);
        this.beamColor = beamColor;
        registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH));
    }

    /** Shared settings for every beacon variant. */
    protected static BlockBehaviour.Properties beaconSettings() {
        return BlockBehaviour.Properties.of()
                .strength(3.0f)
                .sound(SoundType.METAL)
                .lightLevel(state -> LUMINANCE);
    }

    /**
     * Colour of the Remix spot light this variant emits. Read by
     * {@code BlockLightDefaults} so the light rule does not have to enumerate
     * variants.
     */
    public LightEntry.ColorOverride beamColor() {
        return beamColor;
    }

    /**
     * Registers every beacon variant. Called from
     * {@link RtxRemixCommonMod#onInitialize()}, the {@code main} entrypoint, not
     * the client one, because blocks live in a synced registry. Everything this
     * method touches therefore has to be loadable on a dedicated server.
     */
    public static void registerAll() {
        register(CopperBeaconBlock.ID, CopperBeaconBlock.INSTANCE);
        CopperBeaconBlockEntity.register();
    }

    /** Registers one variant, its item, and the creative tab entry. */
    private static void register(String path, SpotLightBlock block) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, path);
        Registry.register(BuiltInRegistries.BLOCK, id, block);
        BlockItem item = Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(block, new Item.Properties()));
        ItemGroupEvents.modifyEntriesEvent(FUNCTIONAL_BLOCKS).register(entries -> entries.accept(item));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getNearestLookingDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
    }
}
