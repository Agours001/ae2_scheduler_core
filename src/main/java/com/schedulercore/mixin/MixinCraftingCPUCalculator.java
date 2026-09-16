package com.schedulercore.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.me.cluster.implementations.CraftingCPUCalculator;

import com.schedulercore.block.SchedulerCoreBlock;

/**
 * Enforces "at most one scheduler core per crafting CPU multiblock" the hard way: a CPU holding two or
 * more cores simply <b>fails to form</b>.
 *
 * <p><b>Why the hard refusal and not a placement hint.</b> A second core is not a cosmetic problem - the
 * scheduler's takeover criterion is "this CPU contains a scheduler core", so a CPU with two of them has two
 * equally valid claims to the same state object. Refusing to form is the only answer that cannot be
 * misread, and it matches how AE2 itself treats mutually exclusive CPU parts (a second storage block is
 * fine, but AdvancedAE's data entanglers and multi-threaders are singular in exactly this way).
 * {@link SchedulerCoreBlock#setPlacedBy} keeps its chat message: that fires immediately, this fires when the
 * multiblock is next validated, and together the player gets both the hint and the refusal.
 *
 * <p><b>Why this hook and not a placement interaction.</b> {@code verifyInternalStructure} is the same
 * predicate AE2 uses to decide whether the CPU is a CPU at all - including its own "must contain at least one
 * crafting storage block" rule, which this mixin deliberately does not touch (it only ever refines an
 * already-valid structure).
 *
 * <p><b>Why the level's block state and not {@code CraftingBlockEntity.getUnitBlock()}.</b> That method
 * casts the block at its position without a guard, so walking the multiblock with it throws
 * {@code ClassCastException} during the dismantle window (the position is already air while the block entity
 * is still alive). Full explanation in {@code ClusterUnits}. This scan happens while a multiblock is being
 * validated, i.e. exactly when that window is open, so the rule is not optional here.
 */
@Mixin(CraftingCPUCalculator.class)
public class MixinCraftingCPUCalculator {

    @Inject(method = "verifyInternalStructure", at = @At("RETURN"), cancellable = true)
    private void schedulercore$enforceSingleSchedulerCore(ServerLevel level, BlockPos min, BlockPos max,
            CallbackInfoReturnable<Boolean> cir) {
        // Only ever refine an otherwise valid structure: a CPU that AE2 already rejected stays rejected, and
        // the storage requirement keeps whatever answer vanilla gave it.
        if (!cir.getReturnValueZ()) {
            return;
        }

        int cores = 0;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!level.isLoaded(pos)) {
                continue;
            }
            if (level.getBlockState(pos).getBlock() instanceof SchedulerCoreBlock) {
                if (++cores > 1) {
                    cir.setReturnValue(false);
                    return;
                }
            }
        }
    }
}
