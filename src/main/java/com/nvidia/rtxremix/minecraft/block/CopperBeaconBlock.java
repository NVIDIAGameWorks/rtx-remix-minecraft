/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.block;

import com.mojang.serialization.MapCodec;
import com.nvidia.rtxremix.minecraft.light.LightEntry;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.FacingBlock;
import net.minecraft.block.Waterloggable;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;
import net.minecraft.world.block.WireOrientation;
import net.minecraft.world.tick.ScheduledTickView;
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
public class CopperBeaconBlock extends SpotLightBlock implements BlockEntityProvider, Waterloggable {

    /** Registry path, also used for the block item, models, and translation key. */
    public static final String ID = "copper_beacon";
    public static final EnumProperty<Direction> MOUNT = EnumProperty.of("mount", Direction.class);
    public static final BooleanProperty POWERED = Properties.POWERED;
    public static final BooleanProperty WATERLOGGED = Properties.WATERLOGGED;

    public static final LightEntry.ColorOverride BEAM_COLOR =
            LightEntry.ColorOverride.of(1.0f, 0.88f, 0.70f);

    public static final MapCodec<CopperBeaconBlock> CODEC = createCodec(CopperBeaconBlock::new);

    public static final CopperBeaconBlock INSTANCE = new CopperBeaconBlock(beaconSettings(ID));

    public CopperBeaconBlock(AbstractBlock.Settings settings) {
        super(settings, BEAM_COLOR);
        setDefaultState(getDefaultState()
                .with(MOUNT, Direction.UP)
                .with(POWERED, false)
                .with(WATERLOGGED, false));
    }

    @Override
    protected MapCodec<? extends FacingBlock> getCodec() {
        return CODEC;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder);
        builder.add(MOUNT, POWERED, WATERLOGGED);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        Direction mount = context.getSide();
        World world = context.getWorld();
        BlockPos pos = context.getBlockPos();
        return getDefaultState()
                .with(MOUNT, mount)
                .with(FACING, placementForward(context, mount))
                .with(POWERED, world.isReceivingRedstonePower(pos))
                .with(WATERLOGGED, world.getFluidState(pos).getFluid() == Fluids.WATER);
    }

    static Direction placementForward(ItemPlacementContext context, Direction mount) {
        return CopperBeaconState.orthogonalForward(
                context.getPlayerLookDirection().getOpposite(),
                context.getHorizontalPlayerFacing().getOpposite(),
                mount);
    }

    @Override
    protected BlockState rotate(BlockState state, BlockRotation rotation) {
        return super.rotate(state, rotation).with(MOUNT, rotation.rotate(state.get(MOUNT)));
    }

    @Override
    protected BlockState mirror(BlockState state, BlockMirror mirror) {
        return super.mirror(state, mirror).with(MOUNT, mirror.apply(state.get(MOUNT)));
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new CopperBeaconBlockEntity(pos, state);
    }

    @Override
    protected ActionResult onUse(
            BlockState state,
            World world,
            BlockPos pos,
            PlayerEntity player,
            BlockHitResult hit) {
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        if (world.getBlockEntity(pos) instanceof CopperBeaconBlockEntity beacon) {
            player.openHandledScreen(beacon);
            return ActionResult.CONSUME;
        }
        return ActionResult.PASS;
    }

    @Override
    protected void neighborUpdate(
            BlockState state,
            World world,
            BlockPos pos,
            Block sourceBlock,
            @Nullable WireOrientation wireOrientation,
            boolean notify) {
        if (world.isClient) {
            return;
        }
        boolean powered = world.isReceivingRedstonePower(pos);
        if (powered == state.get(POWERED)) {
            return;
        }
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity instanceof CopperBeaconBlockEntity beacon) {
            beacon.onPoweredChanged(powered, world.getTime());
        }
        world.setBlockState(pos, state.with(POWERED, powered), Block.NOTIFY_LISTENERS);
    }

    @Override
    protected void onStateReplaced(
            BlockState state,
            World world,
            BlockPos pos,
            BlockState newState,
            boolean moved) {
        if (!state.isOf(newState.getBlock())) {
            if (world.getBlockEntity(pos) instanceof CopperBeaconBlockEntity beacon) {
                ItemScatterer.spawn(world, pos, beacon);
            }
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }

    @Override
    protected BlockState getStateForNeighborUpdate(
            BlockState state,
            WorldView world,
            ScheduledTickView tickView,
            BlockPos pos,
            Direction direction,
            BlockPos neighborPos,
            BlockState neighborState,
            Random random) {
        if (state.get(WATERLOGGED)) {
            tickView.scheduleFluidTick(pos, Fluids.WATER, Fluids.WATER.getTickRate(world));
        }
        return super.getStateForNeighborUpdate(
                state, world, tickView, pos, direction, neighborPos, neighborState, random);
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.get(WATERLOGGED) ? Fluids.WATER.getStill(false) : super.getFluidState(state);
    }

    @Override
    protected BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Override
    protected VoxelShape getOutlineShape(
            BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return CopperBeaconShapes.forMount(state.get(MOUNT));
    }
}
