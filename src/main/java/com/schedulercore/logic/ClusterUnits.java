package com.schedulercore.logic;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import appeng.blockentity.crafting.CraftingBlockEntity;

import com.schedulercore.block.SchedulerCoreBlock;

import java.util.Iterator;

/**
 * Cast-free, teardown-safe inspection of the blocks of a crafting CPU multiblock.
 *
 * <p><b>Why this class exists (crash 2026-09-11 22:08:35).</b> AE2's
 * {@code CraftingBlockEntity.getUnitBlock()} is:
 *
 * <pre>
 * if (this.level == null || this.notLoaded() || this.isRemoved()) return AEBlocks.CRAFTING_UNIT.block();
 * return (AbstractCraftingUnitBlock&lt;?&gt;) this.level.getBlockState(this.worldPosition).getBlock();
 * </pre>
 *
 * <p>That cast is <i>not</i> guarded. During
 * {@code AbstractCraftingUnitBlock.onRemove() -> CraftingBlockEntity.breakCluster()} the broken position
 * is <b>already air</b> while its block entity is still alive and <b>not</b> removed, and the cluster's
 * block-entity list has not been rebuilt yet. Any code that walks {@code cluster.getBlockEntities()} and
 * calls {@code getUnitBlock()} inside that window gets
 * {@code ClassCastException: AirBlock cannot be cast to AbstractCraftingUnitBlock}, which is thrown
 * straight out of {@code onRemove()} and crashes the server with "Ticking GridNode".
 *
 * <p>So scheduler code must <b>never</b> call {@code getUnitBlock()}. We only ask the level what block is
 * actually there, which needs no cast and cannot throw that exception.
 */
public final class ClusterUnits {

    private ClusterUnits() {
    }

    /** True if any block entity of the multiblock currently sits on a scheduler core block. */
    public static boolean hasSchedulerCore(Iterator<CraftingBlockEntity> blockEntities) {
        try {
            while (blockEntities.hasNext()) {
                if (isSchedulerCore(blockEntities.next())) {
                    return true;
                }
            }
        } catch (Throwable t) {
            // A multiblock that is being dismantled must never take the server down because of us.
            // Not finding the core here is always safe: jobs that are still queued keep the CPU busy
            // on their own.
            return false;
        }
        return false;
    }

    /** True if the block currently occupying this block entity's position is a scheduler core block. */
    public static boolean isSchedulerCore(CraftingBlockEntity blockEntity) {
        try {
            if (blockEntity == null || blockEntity.isRemoved()) {
                return false;
            }
            Level level = blockEntity.getLevel();
            if (level == null) {
                return false;
            }
            BlockPos pos = blockEntity.getBlockPos();
            if (!level.isLoaded(pos)) {
                return false;
            }
            BlockState state = level.getBlockState(pos);
            return state.getBlock() instanceof SchedulerCoreBlock;
        } catch (Throwable t) {
            return false;
        }
    }
}
