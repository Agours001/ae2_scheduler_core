package com.schedulercore.support;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.me.cluster.implementations.CraftingCPUCluster;

import com.schedulercore.SchedulerCore;
import com.schedulercore.mixin.AccessorExecutingCraftingJobTarget;
import com.schedulercore.scheduler.NbtSupport;
import com.schedulercore.scheduler.SuspendSupport;

/**
 * Wires this target's two generation-specific capabilities into the shared scheduler.
 *
 * <p>Everything the shared code cannot name lives here, and there are exactly two things:
 *
 * <ul>
 *   <li><b>Job serialisation.</b> 1.21.1's {@code ExecutingCraftingJob.writeToNBT} takes a
 *       {@code HolderLookup.Provider}. That lookup is a property of the level the CPU is in, so it is fetched
 *       from the cluster - which is how the shared save/load hooks can stay signature-agnostic.</li>
 *   <li><b>Crafting-job suspend.</b> AE2 gained it in 19.2.16. This target declares that version as its floor,
 *       so the flag is always there to read and write through the accessor - see {@link #installSuspend()}.</li>
 * </ul>
 *
 * <p>Called once from {@code SchedulerCore}'s constructor. Nothing else in the mod installs these, and
 * serialisation failing is fatal by design: a save that quietly loses orders is worse than an error.
 */
public final class NeoForgeSupport {

    private NeoForgeSupport() {
    }

    public static void install() {
        NbtSupport.install(new NbtSupport.Impl() {
            @Override
            public Object contextFor(Object cluster) {
                if (!(cluster instanceof CraftingCPUCluster cpu)) {
                    return null;
                }
                Level level = cpu.getLevel();
                return level == null ? null : level.registryAccess();
            }

            @Override
            public CompoundTag write(ExecutingCraftingJob job, Object context) {
                return ((AccessorExecutingCraftingJobTarget) (Object) job)
                        .schedulercore$writeToNBT((HolderLookup.Provider) context);
            }
        });

        installSuspend();
    }

    /**
     * Installs the reader and writer for one job's suspend flag.
     *
     * <p>Unconditional, and that is a consequence of the declared floor: the mod requires {@code ae2 [19.2.16,)},
     * the release that added {@code ExecutingCraftingJob.suspended} and the two methods that read it, so on this
     * target {@link AccessorExecutingCraftingJobTarget} is always applied and there is nothing to check. Until
     * 1.0.5 the declaration was {@code [19.2.0,)} and this had to be conditional, which cost a probe, a second
     * accessor and a mixin gate - all of them gone now that the requirement is enforced by the loader instead.
     */
    private static void installSuspend() {
        SuspendSupport.install(new SuspendSupport.Flag() {
            @Override
            public boolean suspended(ExecutingCraftingJob job) {
                return ((AccessorExecutingCraftingJobTarget) (Object) job).schedulercore$suspended();
            }

            @Override
            public void setSuspended(ExecutingCraftingJob job, boolean suspended) {
                ((AccessorExecutingCraftingJobTarget) (Object) job).schedulercore$setSuspended(suspended);
            }
        });
    }
}
