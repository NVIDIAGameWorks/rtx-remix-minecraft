/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.block;

import com.nvidia.rtxremix.minecraft.RtxRemixCommonMod;
import com.nvidia.rtxremix.minecraft.light.LightEntry;
import com.nvidia.rtxremix.minecraft.light.OverridesItemLight;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FacingBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.state.StateManager;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

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
 * <p>Carries the vanilla {@link net.minecraft.state.property.Properties#FACING}
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
public abstract class SpotLightBlock extends FacingBlock implements OverridesItemLight {

    /** Full block light level, matching a lantern. */
    protected static final int LUMINANCE = 15;

    /**
     * Vanilla's "Functional Blocks" creative tab, where torches and lanterns live.
     *
     * <p>Built from the registry id rather than {@code ItemGroups.FUNCTIONAL},
     * which would otherwise need an access-widener entry.
     */
    private static final RegistryKey<ItemGroup> FUNCTIONAL_BLOCKS =
            RegistryKey.of(RegistryKeys.ITEM_GROUP, Identifier.ofVanilla("functional_blocks"));

    private final LightEntry.ColorOverride beamColor;

    protected SpotLightBlock(AbstractBlock.Settings settings, LightEntry.ColorOverride beamColor) {
        super(settings);
        this.beamColor = beamColor;
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
    }

    /** Shared settings for every beacon variant. */
    protected static AbstractBlock.Settings beaconSettings(String path) {
        return AbstractBlock.Settings.create()
                .registryKey(RegistryKey.of(RegistryKeys.BLOCK,
                        Identifier.of(RtxRemixCommonMod.MOD_ID, path)))
                .strength(3.0f)
                .sounds(BlockSoundGroup.METAL)
                .luminance(state -> LUMINANCE);
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
        Identifier id = Identifier.of(RtxRemixCommonMod.MOD_ID, path);
        Registry.register(Registries.BLOCK, id, block);
        BlockItem item = Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings()
                .registryKey(RegistryKey.of(RegistryKeys.ITEM, id))
                .useBlockPrefixedTranslationKey()));
        ItemGroupEvents.modifyEntriesEvent(FUNCTIONAL_BLOCKS).register(entries -> entries.add(item));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        return getDefaultState().with(FACING, ctx.getPlayerLookDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, BlockMirror mirror) {
        return state.with(FACING, mirror.apply(state.get(FACING)));
    }
}
