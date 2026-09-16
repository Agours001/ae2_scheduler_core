package com.schedulercore.mixin;

import com.google.common.collect.ImmutableSet;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;

import com.schedulercore.scheduler.SchedulerJobCpu;

/**
 * Adds one list entry per scheduled order to the set of crafting CPUs AE2 hands out.
 *
 * <p><b>Why this is the only place that works.</b> The crafting-status screen builds its rows by iterating
 * this set ({@code CraftingStatusMenu} -> {@code ICraftingService.getCpus()}), assigns row serials by the
 * objects' identity, and routes a row click back to the very same object. A server cannot add a row any other
 * way, so per-order rows mean per-order objects - which is what {@link SchedulerJobCpu} supplies.
 *
 * <h2>What this deliberately does not do</h2>
 *
 * <p>It does <b>not</b> add a "remaining capacity" pseudo-CPU. Such an entry has to be filtered back out of
 * the "which CPU should craft this?" dialog, because an idle entry that reports the CPU's free storage
 * satisfies that dialog's filter
 * ({@code availableStorage >= plan.bytes() && !isBusy()}) and turns into a phantom choice. A per-order entry
 * cannot cause that: it reports {@code isBusy() == true} for as long as the order exists, so the dialog skips
 * it on its own.
 *
 * <p>It also does not re-group or re-sort the list. AE2 sorts by name, and these entries are named after what
 * they produce, so they interleave with the CPU rows rather than forming a block under them - acceptable for
 * the minimal version, and a separate (cosmetic) piece of work if it turns out to matter.
 */
@Mixin(CraftingService.class)
public class MixinCraftingService {

    @Inject(method = "getCpus", at = @At("RETURN"), cancellable = true)
    private void schedulercore$addScheduledOrders(CallbackInfoReturnable<ImmutableSet<ICraftingCPU>> cir) {
        try {
            var cpus = cir.getReturnValue();
            if (cpus == null || cpus.isEmpty()) {
                return;
            }
            ImmutableSet.Builder<ICraftingCPU> builder = null;
            for (var cpu : cpus) {
                if (!(cpu instanceof CraftingCPUCluster cluster)) {
                    continue;
                }
                var orders = SchedulerJobCpu.forCluster(cluster);
                if (orders.isEmpty()) {
                    continue;
                }
                if (builder == null) {
                    builder = ImmutableSet.builder();
                    builder.addAll(cpus);
                }
                builder.addAll(orders);
            }
            if (builder != null) {
                cir.setReturnValue(builder.build());
            }
        } catch (Throwable t) {
            // A failure here must not remove the real CPUs from the list.
            com.schedulercore.SchedulerCore.LOG.error(
                    "[schedulercore] could not add per-order CPU entries", t);
        }
    }
}
