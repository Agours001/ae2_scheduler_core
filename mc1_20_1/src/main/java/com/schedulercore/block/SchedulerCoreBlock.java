package com.schedulercore.block;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import appeng.block.crafting.AbstractCraftingUnitBlock;
import appeng.blockentity.crafting.CraftingBlockEntity;

import com.schedulercore.SchedulerUnitType;
import com.schedulercore.logic.ClusterUnits;

/**
 * The scheduler core CPU part block.
 *
 * <p>As far as AE2's multiblock logic is concerned this is an ordinary crafting unit: it extends
 * {@link AbstractCraftingUnitBlock} (which is what {@code CraftingBlockEntity.isConnected} tests for, and which
 * supplies the {@code FORMED}/{@code POWERED} properties that {@code updateSubType} writes), and its
 * {@link SchedulerUnitType} reports zero storage and zero threads. Vanilla's {@code CraftingCPUCalculator}
 * therefore accepts it with no changes: all it requires is that every position holds a
 * {@code CraftingBlockEntity} and that at least one of them has storage.
 *
 * <p>Two rules are enforced around this block:
 * <ul>
 *   <li><b>at most one core per CPU</b> - the hard refusal lives in {@code MixinCraftingCPUCalculator} (a CPU
 *       holding two cores does not form); the chat message in {@link #setPlacedBy} is the immediate hint.</li>
 *   <li><b>losing the core cancels the CPU's orders</b>. <b>No code is needed for it here, and that is a
 *       measured conclusion rather than an assumption.</b> AE2's own {@code AbstractCraftingUnitBlock.onRemove}
 *       calls {@code breakCluster()}, which cancels the CPU's crafting logic <i>before</i> it tears the cluster
 *       down - and the scheduler already hooks {@code CraftingCpuLogic.cancel()} to cancel every scheduled
 *       order rather than only vanilla's single one. Overriding {@code onRemove} to look up the neighbouring
 *       cluster and cancel it there would be redundant, so it is deliberately not done.</li>
 * </ul>
 *
 * <p><b>Known defect (documented in the README's limitations).</b> Removing any block of a CPU that contains a
 * scheduler core makes AE2's own {@code CraftingCPUCluster.destroy()} throw
 * {@code IllegalStateException: The node has already been initialized}, which aborts the block removal. A CPU
 * without a core does not do this, so the core block's presence is what triggers it; it happens inside AE2's
 * teardown, before any scheduler code, and the cancellation above still completes first. It is not fixed.
 *
 * <p><b>1.20.1 note.</b> The tooltip hook is the one signature that differs between the generations: this
 * generation passes a {@link BlockGetter} where 1.21.1 passes an {@code Item.TooltipContext} (checked against
 * the real jar). Everything else here is identical on both.
 */
public class SchedulerCoreBlock extends AbstractCraftingUnitBlock<CraftingBlockEntity> {

    public SchedulerCoreBlock() {
        super(metalProps(), SchedulerUnitType.INSTANCE);
    }

    /**
     * AE2's {@code AEBaseBlockItem#appendHoverText} is final and forwards to the block, so this is the
     * supported place for the block item's tooltip.
     */
    @Override
    public void appendHoverText(ItemStack stack, BlockGetter level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.schedulercore.scheduler_core_block.line1")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.schedulercore.scheduler_core_block.line2")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);

        // Feedback only - the hard refusal is MixinCraftingCPUCalculator.enforceSingleSchedulerCore, which runs
        // when the multiblock is next validated. This message is the immediate hint so the player does not have
        // to wonder why the CPU stopped forming. We never call getUnitBlock() here.
        if (level.isClientSide() || !(placer instanceof Player player)) {
            return;
        }
        if (hasSchedulerCoreInNeighbourCluster(level, pos)) {
            player.displayClientMessage(Component.translatable("message.schedulercore.duplicate_core"), false);
        }
    }

    private static boolean hasSchedulerCoreInNeighbourCluster(Level level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (level.getBlockEntity(pos.relative(dir)) instanceof CraftingBlockEntity neighbour) {
                var cluster = neighbour.getCluster();
                if (cluster == null) {
                    continue;
                }
                if (ClusterUnits.hasSchedulerCore(cluster.getBlockEntities())) {
                    return true;
                }
            }
        }
        return false;
    }
}
