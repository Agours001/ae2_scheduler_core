package com.schedulercore.support;

import net.minecraft.nbt.CompoundTag;

import appeng.crafting.execution.ExecutingCraftingJob;

import com.schedulercore.mixin.AccessorExecutingCraftingJobPersistence;
import com.schedulercore.scheduler.NbtSupport;

/**
 * Wires this generation's version-specific capability into the shared scheduler.
 *
 * <p>There is exactly one of them here, and the difference from the 1.21.1 target is the point of the seam:
 *
 * <ul>
 *   <li><b>Job serialisation.</b> AE2 15.4.10's {@code ExecutingCraftingJob.writeToNBT()} takes no argument,
 *       so nothing is needed from the level and {@link NbtSupport.Impl#contextFor(Object)} answers null. On
 *       1.21.1 the same seam carries the level's registry lookup.</li>
 *   <li><b>Crafting-job suspend is not installed, deliberately.</b> AE2 gained it in 19.2.16, a 1.21.1-era
 *       release; this generation has neither the {@code suspended} field nor the screen button (checked
 *       against the real jar - the string does not occur in {@code ExecutingCraftingJob.class} at all). The
 *       shared scheduler therefore keeps {@code SuspendSupport}'s default, in which nothing is ever suspended
 *       and asking to suspend is a no-op, and every order is simply runnable. That is exactly what this
 *       generation means, and it is why no suspend mixin exists in this target's mixin config.</li>
 * </ul>
 *
 * <p>Called once from {@code SchedulerCore}'s constructor. Serialisation failing loudly is by design: a save
 * that silently loses orders is worse than an error, which is why this installation is not optional.
 */
public final class ForgeSupport {

    private ForgeSupport() {
    }

    public static void install() {
        NbtSupport.install(new NbtSupport.Impl() {
            @Override
            public Object contextFor(Object cluster) {
                // This generation's serialiser needs no registry lookup, so there is no context to find.
                return null;
            }

            @Override
            public CompoundTag write(ExecutingCraftingJob job, Object context) {
                return ((AccessorExecutingCraftingJobPersistence) (Object) job).schedulercore$writeToNBT();
            }
        });
    }
}
