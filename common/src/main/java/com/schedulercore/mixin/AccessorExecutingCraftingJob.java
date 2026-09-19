package com.schedulercore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import appeng.api.stacks.GenericStack;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.ExecutingCraftingJob;

/**
 * Opens up {@link ExecutingCraftingJob}'s package-private state to the scheduler.
 *
 * <p><b>Why an accessor mixin and not a helper class in AE2's package.</b> A bridge class placed in
 * {@code appeng.crafting.execution} can reach the package-private constructor and fields, and compiles -
 * but <b>fails at runtime on the module path</b>:
 *
 * <pre>
 * java.lang.module.ResolutionException: Module schedulercore contains package appeng.crafting.execution,
 * module ae2 exports package appeng.crafting.execution to schedulercore
 * </pre>
 *
 * i.e. shipping a second copy of an AE2 package is a JPMS split-package error and the game refuses to
 * start. A mixin is merged into the target class instead, so it adds no package. This is the standard way
 * for an addon to reach package-private internals.
 *
 * <p>Only what the scheduler genuinely needs is exposed, and every member here is read-only except where
 * the scheduler must update job accounting.
 */
@Mixin(ExecutingCraftingJob.class)
public interface AccessorExecutingCraftingJob {

    @Accessor("link")
    CraftingLink schedulercore$link();

    @Accessor("finalOutput")
    GenericStack schedulercore$finalOutput();

    @Accessor("remainingAmount")
    long schedulercore$remainingAmount();

    @Accessor("remainingAmount")
    void schedulercore$setRemainingAmount(long remaining);

    @Accessor("waitingFor")
    appeng.crafting.inv.ListCraftingInventory schedulercore$waitingFor();

    /**
     * The job's elapsed-time tracker, which is where progress and ETA come from.
     *
     * <p>Read for two reasons: the scheduler must decrement it on every accepted item (see
     * {@link AccessorElapsedTimeTracker}), and multi-job status reporting has to answer with <i>this</i>
     * job's tracker rather than with whichever job vanilla's single {@code job} field happens to name.
     */
    @Accessor("timeTracker")
    appeng.crafting.execution.ElapsedTimeTracker schedulercore$timeTracker();

    // Two members deliberately live in a target's own accessor instead of here, because they do not exist in
    // every supported AE2 generation:
    //   * `suspended` - AE2 gained crafting-job suspend only in 19.2.16, so the whole 1.20.1 line lacks the
    //     field. It is reached through `SuspendSupport`, whose implementation a target installs.
    //   * `writeToNBT` - its signature gained a registry lookup in 1.20.5, and naming that type here would
    //     stop this file from compiling against 1.20.1. It is reached through `NbtSupport` instead.
}
