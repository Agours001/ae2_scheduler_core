package com.schedulercore.mixin;

import com.google.common.collect.ImmutableSet;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

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
 * <h2>Why the rows are added to AE2's own builder</h2>
 *
 * <p>Not a style choice - this is the difference between rows that survive on a modpack and rows that vanish
 * silently. {@code getCpus()} ends in {@code ImmutableSet.builder()...build()}, and on a real pack more than
 * one addon contributes entries:
 *
 * <pre>
 * // AE2
 * var builder = ImmutableSet.builder();
 * for (var cluster : craftingCPUClusters) if (cluster.isActive() &amp;&amp; !cluster.isDestroyed()) builder.add(cluster);
 * return builder.build();
 *
 * // AdvancedAE, on the same RETURN point
 * for (var cluster : advancedAE$advCraftingCPUClusters) { ...builder.add(cpu); }
 * cir.setReturnValue(builder.build());
 * </pre>
 *
 * <p>A hook that reads {@code cir.getReturnValue()} and returns a <i>new</i> set containing its own entries
 * therefore does not compose: whoever runs last wins, and the other mod's rows are dropped with no error on
 * either side. That is exactly what happened. AdvancedAE injects at the same RETURN and rebuilds from AE2's
 * own builder, so it overwrote this mod's rows - the crafting-status screen listed the CPU and nothing else,
 * and every per-order action (suspend, resume, cancel, the details pane) silently fell back to "whatever
 * order the CPU is serving right now". Reported from a real machine as three separate bugs - "only one CPU
 * row", "resume only affects the last order", "cancel cancels everything" - that were one bug.
 *
 * <p>So the rows are added <b>into AE2's builder</b> instead of into a set of our own. Anything that later
 * re-builds that builder - AdvancedAE, or any other addon that uses the same idiom - picks them up, and the
 * behaviour no longer depends on which mixin happens to run last.
 *
 * <p>{@code @WrapOperation} rather than {@code @Redirect} for the same reason: two mods that wrap the same
 * call both run, while two redirects on one call site are a hard mixin failure at startup.
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

    @WrapOperation(method = "getCpus", at = @At(value = "INVOKE",
            target = "Lcom/google/common/collect/ImmutableSet$Builder;build()Lcom/google/common/collect/ImmutableSet;"))
    private ImmutableSet<ICraftingCPU> schedulercore$addScheduledOrders(
            ImmutableSet.Builder<ICraftingCPU> builder, Operation<ImmutableSet<ICraftingCPU>> original) {
        try {
            // Whatever the builder holds at this point is what AE2 itself put there - i.e. the real CPU
            // clusters. Reading it back first keeps this hook from having to repeat AE2's own filter
            // (isActive() && !isDestroyed()) and stay correct if that filter ever changes.
            for (var cpu : builder.build()) {
                if (cpu instanceof CraftingCPUCluster cluster) {
                    builder.addAll(SchedulerJobCpu.forCluster(cluster));
                }
            }
        } catch (Throwable t) {
            // A failure here must not remove the real CPUs from the list.
            com.schedulercore.SchedulerCore.LOG.error(
                    "[schedulercore] could not add per-order CPU entries", t);
        }
        return original.call(builder);
    }
}
