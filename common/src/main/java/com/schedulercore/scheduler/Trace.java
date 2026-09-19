package com.schedulercore.scheduler;

/**
 * A toggle for the scheduler's per-tick trace.
 *
 * <p>Why this exists: "the new order gets served first" is not visible from the outside. The tick log only
 * reports when the turn changes, and with a working rotation that line alternates every tick no matter what
 * the jobs actually do - so a job that is being served and pushing nothing looks exactly like a job that is
 * being served and pushing normally. Three theories (rotation broken / credits mis-routed / the job cannot
 * push) survive that evidence, and they need different fixes.
 *
 * <p>The trace answers it directly, once per tick: who was served, what the budget was, how much they
 * actually pushed, and what each job still holds. It is off by default because a per-tick line is exactly
 * what flooded a real game log with 13874 lines during an earlier round.
 */
public final class Trace {

    private static volatile boolean enabled;

    private Trace() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }
}
