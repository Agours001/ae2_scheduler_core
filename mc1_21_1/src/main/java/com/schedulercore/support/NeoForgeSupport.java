package com.schedulercore.support;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.me.cluster.implementations.CraftingCPUCluster;

import com.schedulercore.SchedulerCore;
import com.schedulercore.mixin.AccessorExecutingCraftingJobPersistence;
import com.schedulercore.mixin.AccessorExecutingCraftingJobSuspend;
import com.schedulercore.mixin.SuspendApiProbe;
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
 *   <li><b>Crafting-job suspend.</b> AE2 gained it in 19.2.16, so it is installed only where that AE2 is
 *       present - see {@link #installSuspend()}.</li>
 * </ul>
 *
 * <p>Called once from {@code SchedulerCore}'s constructor. Nothing else in the mod installs these, and
 * serialisation failing is fatal by design while a missing suspend flag is not: a save that quietly loses
 * orders is worse than an error, whereas "this AE2 has no suspend button" is a normal state.
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
                return ((AccessorExecutingCraftingJobPersistence) (Object) job)
                        .schedulercore$writeToNBT((HolderLookup.Provider) context);
            }
        });

        installSuspend();
    }

    /**
     * Installs the reader and writer for one job's suspend flag, unless this AE2 has no such flag.
     *
     * <p><b>Why the check is the probe and not the accessor interface.</b> That interface is a mixin, and
     * ordinary code may not refer to one: Mixin answers with {@code IllegalClassLoadError}, and it does so
     * precisely on the AE2 builds where the mixin was withdrawn - the case this method exists to handle
     * quietly. (Found by running this target against AE2 19.2.15, where the withdrawal worked and the mod then
     * died on the check that was supposed to confirm it.) So the same question is asked of the same probe the
     * plugin used, which also means the two decisions cannot disagree: no flag here means no mixin there, and
     * vice versa.
     *
     * <p>A player on such an AE2 gets no freeze button (AE2's screen has none either on those versions) and
     * everything else - scheduling, per-order rows, cancel, save/restore - behaves identically.
     */
    private static void installSuspend() {
        if (!SuspendApiProbe.available()) {
            SchedulerCore.LOG.warn("[schedulercore] this AE2 has no crafting-job suspend (added in 19.2.16) - "
                    + "the freeze feature stays off; everything else is unaffected.");
            return;
        }

        SuspendSupport.install(new SuspendSupport.Flag() {
            @Override
            public boolean suspended(ExecutingCraftingJob job) {
                return ((AccessorExecutingCraftingJobSuspend) (Object) job).schedulercore$suspended();
            }

            @Override
            public void setSuspended(ExecutingCraftingJob job, boolean suspended) {
                ((AccessorExecutingCraftingJobSuspend) (Object) job).schedulercore$setSuspended(suspended);
            }
        });
    }
}
