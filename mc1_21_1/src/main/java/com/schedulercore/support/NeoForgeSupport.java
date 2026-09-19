package com.schedulercore.support;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.me.cluster.implementations.CraftingCPUCluster;

import com.schedulercore.mixin.AccessorExecutingCraftingJobTarget;
import com.schedulercore.scheduler.NbtSupport;
import com.schedulercore.scheduler.SuspendSupport;

/**
 * Wires this target's two version-specific capabilities into the shared scheduler.
 *
 * <p>Everything the shared code cannot name lives here, and there are exactly two things:
 *
 * <ul>
 *   <li><b>Job serialisation.</b> 1.21.1's {@code ExecutingCraftingJob.writeToNBT} takes a
 *       {@code HolderLookup.Provider}. That lookup is a property of the level the CPU is in, so it is fetched
 *       from the cluster - which is how the shared save/load hooks can stay signature-agnostic.</li>
 *   <li><b>Crafting-job suspend.</b> AE2 gained it in 19.2.16; this target's AE2 has the field, so the flag is
 *       read and written through the accessor.</li>
 * </ul>
 *
 * <p>Called once from {@code SchedulerCore}'s constructor. Both installs are idempotent in effect: they simply
 * replace the "not supported" defaults, and nothing else in the mod installs them.
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
