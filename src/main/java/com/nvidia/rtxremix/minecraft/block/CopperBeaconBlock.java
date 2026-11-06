/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.block;

import com.nvidia.rtxremix.minecraft.light.LightEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Warm-white lighthouse beacon in a brass housing.
 *
 * <p>Waterloggable like a lantern. The housing is too small for vanilla to count the block as
 * solid, so without a fluid state of its own, flowing water would replace it the way it washes
 * away a torch.
 *
 * @see SpotLightBlock
 */
public class CopperBeaconBlock extends SpotLightBlock implements EntityBlock, SimpleWaterloggedBlock {

    /** Registry path, also used for the block item, models, and translation key. */
    public static final String ID = "copper_beacon";
    public static final EnumProperty<Direction> MOUNT = EnumProperty.create("mount", Direction.class);
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

    public static final LightEntry.ColorOverride BEAM_COLOR =
            LightEntry.ColorOverride.of(1.0f, 0.88f, 0.70f);

    public static final CopperBeaconBlock INSTANCE = new CopperBeaconBlock(beaconSettings(ID));

    public CopperBeaconBlock(BlockBehaviour.Properties settings) {
        super(settings, BEAM_COLOR);
        registerDefaultState(defaultBlockState()
                .setValue(MOUNT, Direction.UP)
                .setValue(POWERED, false)
                .setValue(WATERLOGGED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(MOUNT, POWERED, WATERLOGGED);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction mount = context.getClickedFace();
        Level world = context.getLevel();
        BlockPos pos = context.getClickedPos();
        return defaultBlockState()
                .setValue(MOUNT, mount)
                .setValue(FACING, placementForward(context, mount))
                .setValue(POWERED, world.hasNeighborSignal(pos))
                .setValue(WATERLOGGED, world.getFluidState(pos).getType() == Fluids.WATER);
    }

    static Direction placementForward(BlockPlaceContext context, Direction mount) {
        return CopperBeaconState.orthogonalForward(
                context.getNearestLookingDirection().getOpposite(),
                context.getHorizontalDirection().getOpposite(),
                mount);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return super.rotate(state, rotation).setValue(MOUNT, rotation.rotate(state.getValue(MOUNT)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return super.mirror(state, mirror).setValue(MOUNT, mirror.mirror(state.getValue(MOUNT)));
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CopperBeaconBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state,
            Level world,
            BlockPos pos,
            Player player,
            BlockHitResult hit) {
        if (world.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (world.getBlockEntity(pos) instanceof CopperBeaconBlockEntity beacon) {
            player.openMenu(beacon);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    @Override
    protected void neighborChanged(
            BlockState state,
            Level world,
            BlockPos pos,
            Block sourceBlock,
            @Nullable Orientation orientation,
            boolean movedByPiston) {
        if (world.isClientSide()) {
            return;
        }
        boolean powered = world.hasNeighborSignal(pos);
        if (powered == state.getValue(POWERED)) {
            return;
        }
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity instanceof CopperBeaconBlockEntity beacon) {
            beacon.onPoweredChanged(powered, world.getGameTime());
        }
        world.setBlock(pos, state.setValue(POWERED, powered), Block.UPDATE_CLIENTS);
    }

    // The dye slot drops on removal through BlockEntity.preRemoveSideEffects,
    // which empties any block entity that is a Container.

    @Override
    protected BlockState updateShape(
            BlockState state,
            LevelReader world,
            ScheduledTickAccess ticks,
            BlockPos pos,
            Direction direction,
            BlockPos neighborPos,
            BlockState neighborState,
            RandomSource random) {
        if (state.getValue(WATERLOGGED)) {
            ticks.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(world));
        }
        return super.updateShape(state, world, ticks, pos, direction, neighborPos, neighborState, random);
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(
            BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        return CopperBeaconShapes.forMount(state.getValue(MOUNT));
    }
}
