package com.schedulercore.scheduler;

import net.minecraft.nbt.CompoundTag;

import appeng.crafting.execution.ExecutingCraftingJob;

/**
 * The one place job serialisation touches a version-specific API, kept pluggable so {@code common} stays
 * compilable against both supported generations.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Writing a job to NBT is the same idea everywhere ({@code ExecutingCraftingJob.writeToNBT}), but its
 * signature is not: since 1.20.5 it takes a {@code HolderLookup.Provider} to resolve registry entries, and
 * before that it takes nothing. The scheduler's own save/load hooks are shared code, so they cannot name
 * either signature - they ask here instead.
 *
 * <p>The registry lookup is not passed around as a parameter either: it is a property of the level the CPU
 * lives in, so {@link #contextFor(Object)} asks the level. That keeps a caller from having to thread a
 * context through hooks whose target signatures differ for exactly that reason.
 *
 * <p>An uninstalled support object is not an error state to be papered over: a target that never writes jobs
 * (or a test harness) gets a clear exception rather than a silently empty tag.
 */
public final class NbtSupport {

    /** The version-specific half: what a job's serialiser needs, and how to serialise one job. */
    public interface Impl {
        /** The registry context this CPU's level serialises with, or null where the generation has none. */
        Object contextFor(Object cluster);

        /** Serialises one job, using the context from {@link #contextFor(Object)}. */
        CompoundTag write(ExecutingCraftingJob job, Object context);
    }

    private static final Impl MISSING = new Impl() {
        @Override
        public Object contextFor(Object cluster) {
            return null;
        }

        @Override
        public CompoundTag write(ExecutingCraftingJob job, Object context) {
            throw new IllegalStateException(
                    "schedulercore: no NBT support was installed for this target; jobs cannot be saved");
        }
    };

    private static volatile Impl impl = MISSING;

    private NbtSupport() {
    }

    /** Called once by the target, at mod construction. */
    public static void install(Impl implementation) {
        impl = implementation;
    }

    public static Object contextFor(Object cluster) {
        return impl.contextFor(cluster);
    }

    public static CompoundTag write(ExecutingCraftingJob job, Object context) {
        return impl.write(job, context);
    }
}
