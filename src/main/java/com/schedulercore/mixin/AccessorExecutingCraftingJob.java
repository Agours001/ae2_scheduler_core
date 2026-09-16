package com.schedulercore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

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

    @Accessor("suspended")
    boolean schedulercore$suspended();

    @Accessor("suspended")
    void schedulercore$setSuspended(boolean suspended);

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

    /**
     * The serialisation method is package-private; the scheduler needs it to persist every job rather than
     * only the one vanilla knows about.
     */
    @Invoker("writeToNBT")
    CompoundTag schedulercore$writeToNBT(HolderLookup.Provider registries);
}
