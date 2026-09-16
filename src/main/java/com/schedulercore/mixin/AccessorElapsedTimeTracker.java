package com.schedulercore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import appeng.api.stacks.AEKeyType;
import appeng.crafting.execution.ElapsedTimeTracker;

/**
 * Opens {@link ElapsedTimeTracker}'s package-private accounting to the scheduler.
 *
 * <p><b>Why this is needed at all.</b> Vanilla's {@code CraftingCpuLogic.insert} does two things when it
 * accepts an item: it consumes the job's {@code waitingFor} ledger, and it tells the time tracker
 * ({@code job.timeTracker.decrementItems(amount, type)}). The scheduler's own insert path reuses the
 * accounting for the first half but used to skip the second, which leaves "items still to come" frozen at
 * its initial value for ever. Nothing visible depended on it while {@code getJobStatus()} returned null, but
 * the moment the crafting-status screen or {@code /schedulercore measure} reads progress or an ETA, the
 * number is wrong - so this is fixed together with the status reporting rather than after it.
 *
 * <p>{@code decrementItems} is package-private, hence an {@code @Invoker} rather than a plain call: it is
 * the same technique (and the same package-private-access problem) as {@code AccessorExecutingCraftingJob}.
 */
@Mixin(ElapsedTimeTracker.class)
public interface AccessorElapsedTimeTracker {

    /** Mirrors {@code ElapsedTimeTracker.decrementItems(long, AEKeyType)}. */
    @Invoker("decrementItems")
    void schedulercore$decrementItems(long amount, AEKeyType type);
}
