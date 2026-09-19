package com.schedulercore.scheduler;

import appeng.crafting.execution.ExecutingCraftingJob;

/**
 * Where "is this order suspended" lives, kept pluggable because not every AE2 generation has such a flag.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Suspending a crafting job - the button on the crafting-status screen, and this mod's "freeze the whole
 * CPU" - is an AE2 feature, not this mod's: the flag is {@code ExecutingCraftingJob.suspended}, and the
 * screen's button writes it. AE2 only gained it in <b>19.2.16</b> (PR #8635, "Suspend and resume crafting
 * jobs"), which is a 1.21.1-era release: the whole 1.20.1 line (15.x) has neither the field nor the button.
 *
 * <p>The scheduler needs the flag in two places - to skip a suspended order in the rotation, and to answer
 * the screen - so those places cannot name the field directly. They ask here, and a target that has the
 * feature installs an implementation. A target that does not installs nothing, and every order is then
 * simply runnable: exactly the behaviour of an AE2 without the feature, with no dead branches to maintain.
 */
public final class SuspendSupport {

    /** The version-specific half: reading and writing one job's suspended flag. */
    public interface Flag {
        boolean suspended(ExecutingCraftingJob job);

        void setSuspended(ExecutingCraftingJob job, boolean suspended);
    }

    /**
     * The default: nothing is ever suspended, and asking to suspend is a no-op.
     *
     * <p>Deliberately silent rather than throwing. Serialisation support failing loudly is right, because a
     * save that silently loses orders is worse than an error; a missing suspend flag is not, because "this
     * generation has no suspend button" is a normal state and the scheduler works correctly in it.
     */
    private static final Flag UNSUPPORTED = new Flag() {
        @Override
        public boolean suspended(ExecutingCraftingJob job) {
            return false;
        }

        @Override
        public void setSuspended(ExecutingCraftingJob job, boolean suspended) {
        }
    };

    private static volatile Flag flag = UNSUPPORTED;

    private SuspendSupport() {
    }

    /** Called once by a target whose AE2 has the feature. */
    public static void install(Flag implementation) {
        flag = implementation;
    }

    /** Whether this target's AE2 can suspend at all - used to keep the feature's UI honest. */
    public static boolean supported() {
        return flag != UNSUPPORTED;
    }

    public static boolean suspended(ExecutingCraftingJob job) {
        return flag.suspended(job);
    }

    public static void setSuspended(ExecutingCraftingJob job, boolean suspended) {
        flag.setSuspended(job, suspended);
    }
}
