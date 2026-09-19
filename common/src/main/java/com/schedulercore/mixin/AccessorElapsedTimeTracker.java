package com.schedulercore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
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

    /** Nanoseconds this tracker has accumulated so far. */
    @Accessor("elapsedTime")
    long schedulercore$elapsedTime();

    @Accessor("elapsedTime")
    void schedulercore$setElapsedTime(long elapsedTime);

    /**
     * When this tracker last folded the clock into {@code elapsedTime}.
     *
     * <p>Needed because {@code getElapsedTime()} is not a field read: while any key type still has work
     * outstanding it returns {@code elapsedTime + (now - lastTime)}, i.e. it extrapolates to this instant. A
     * synthetic tracker that only sets {@code elapsedTime} therefore reports elapsedTime plus the time since
     * the tracker was constructed - double counting, and growing without bound.
     */
    @Accessor("lastTime")
    void schedulercore$setLastTime(long lastTime);

    /**
     * Work started per key type, in that type's own unit - the denominator of {@code getProgress()}.
     *
     * <p>Handed out as the live map rather than copied, because the CPU page's aggregate row needs a tracker
     * it can keep updating: AE2 keeps progress as a fraction of two amounts, so a synthetic "whole CPU"
     * tracker has to be filled through these two maps (see {@code schedulercore$reportAggregateTracker}).
     */
    @Accessor("startedWorkByType")
    it.unimi.dsi.fastutil.objects.Reference2LongMap<AEKeyType> schedulercore$startedWorkByType();

    /** Work completed per key type - the numerator of {@code getProgress()}. */
    @Accessor("completedWorkByType")
    it.unimi.dsi.fastutil.objects.Reference2LongMap<AEKeyType> schedulercore$completedWorkByType();
}
