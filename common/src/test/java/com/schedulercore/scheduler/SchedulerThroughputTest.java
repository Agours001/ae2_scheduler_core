package com.schedulercore.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.schedulercore.scheduler.SchedulingPolicy.Refusal;

/**
 * L1 simulation of the whole scheduling loop - policy <b>and</b> the host around it - with several jobs.
 *
 * <h2>What this covers that {@link RoundRobinPolicyTest} cannot</h2>
 *
 * <p>{@code RoundRobinPolicyTest} treats "the job could push" as given: it calls
 * {@code onPushResult(id, 1)} unconditionally. The real host does not. Two things in the host make progress
 * conditional, and both are shared between jobs:
 *
 * <ol>
 *   <li>the <b>per-tick budget</b>, which vanilla computes from a rolling window of the last three ticks'
 *       pushes ({@code c + 1 - (usedOps[0] + usedOps[1] + usedOps[2])}). It is one window for the whole CPU,
 *       so one job's push consumes budget that every other job would otherwise have had;</li>
 *   <li>each job's <b>machine</b>, which accepts a pattern only on some ticks.</li>
 * </ol>
 *
 * <p>That combination is the dangerous one: when the rotation's period and the machine's period divide each
 * other, one job wins every window for ever and the others starve. The point of this class is to search for
 * that class of failure <i>here</i>, in milliseconds, across job counts, machine periods, machine phases and
 * co-processor counts - and to state the throughput claim as an assertion instead of a belief.
 *
 * <h2>The requirement this pins down</h2>
 *
 * <p>The throughput claim: "with N jobs each job takes about xN as long, and <b>total throughput is
 * unchanged</b>". Modelled as:
 * running N jobs for T ticks must deliver the same number of pushes a single vanilla job would have delivered
 * in T ticks (never more - that is guarantee G3 - and not meaningfully fewer), split evenly between them.
 */
class SchedulerThroughputTest {

    /** A host model: the policy, the shared budget window, and one machine per job. */
    private static final class Host {

        final TestJobSource source;
        final RoundRobinPolicy policy = new RoundRobinPolicy();
        final int coProcessors;
        /** Rolling window of the last three ticks' pushes; index 0 is the most recent. */
        final int[] usedOps = new int[3];
        final long[] pushes;
        /** Machine cadence per job: a push is possible only when {@code tick % period == phase}. */
        final int[] period;
        final int[] phase;
        /** Jobs whose machine never opens - the "blocked for its own reasons" case. */
        final boolean[] dead;
        /**
         * Jobs whose machine answers "busy" for ever - the case the policy's safety cap exists for: a
         * provider whose push never comes back (its assembler was destroyed mid-craft) also stalls a
         * vanilla CPU, but it must not freeze the other orders.
         */
        final boolean[] stuck;
        long totalPushes;
        long servedTicks;
        long noOwnerTicks;

        Host(int jobs, int coProcessors, int[] period, int[] phase, boolean[] dead) {
            this.source = new TestJobSource(jobs);
            this.coProcessors = coProcessors;
            this.period = period;
            this.phase = phase;
            this.dead = dead;
            this.stuck = new boolean[jobs];
            this.pushes = new long[jobs];
        }

        static Host of(int jobs, int coProcessors) {
            var period = new int[jobs];
            var phase = new int[jobs];
            return new Host(jobs, coProcessors, period, phase, new boolean[jobs]);
        }

        /** Marks a job whose machine is busy for ever. */
        Host withStuckMachine(int job) {
            stuck[job] = true;
            return this;
        }

        /** One tick, in the order the mixin does it: decide, compute the budget, push, report, shift. */
        void tick(long now) {
            var result = policy.tick(now, source);
            long id = result.decision().jobId();

            int budget = (int) Math.max(0, coProcessors + 1 - (usedOps[0] + usedOps[1] + usedOps[2]));
            int pushed = 0;
            if (id >= 0) {
                servedTicks++;
                if (budget > 0 && canPush((int) id, now)) {
                    pushed = 1;
                }
                // The host's answer to "would retrying help?", from the same two probes the mixin makes.
                var refusal = pushed > 0 ? Refusal.NONE : classify((int) id, budget);
                policy.onPushResult(id, pushed, refusal);
            } else {
                noOwnerTicks++;
            }
            usedOps[2] = usedOps[1];
            usedOps[1] = usedOps[0];
            usedOps[0] = pushed;
            if (pushed > 0) {
                pushes[(int) id]++;
                totalPushes++;
            }
        }

        /**
         * Why the served job pushed nothing, in the terms the policy understands.
         *
         * <ul>
         *   <li>no budget: the machine was never asked, so nothing can be concluded - {@code TRANSIENT};</li>
         *   <li>the machine is still working for this job (its window is not open yet, or it is stuck with
         *       the job's pattern in flight) - {@code TRANSIENT};</li>
         *   <li>the machine will never take this job's pattern, and no amount of waiting on the CPU changes
         *       that - {@code FUTILE}. In the rig that is the "provider is free and still refused" case: a
         *       provider only refuses while it has a push in flight, so a free provider that still refuses
         *       means the refusal came from the job's own inputs, not from the machine.</li>
         * </ul>
         */
        private Refusal classify(int job, int budget) {
            if (budget <= 0) {
                return Refusal.TRANSIENT;
            }
            return dead[job] && !stuck[job] ? Refusal.FUTILE : Refusal.TRANSIENT;
        }

        private boolean canPush(int job, long now) {
            if (dead[job] || stuck[job]) {
                return false;
            }
            if (period[job] <= 1) {
                return true; // a machine that is always ready
            }
            return now % period[job] == phase[job];
        }
    }

    /** How many pushes a single vanilla job makes in the same number of ticks with the same window. */
    private static long vanillaPushes(long ticks, int coProcessors) {
        var host = Host.of(1, coProcessors);
        for (long t = 0; t < ticks; t++) {
            host.tick(t);
        }
        return host.totalPushes;
    }

    // ------------------------------------------------------------------ D2: total throughput is unchanged

    @Test
    @DisplayName("D2/G3: N jobs deliver what one vanilla job would, and split it evenly")
    void throughputIsUnchangedAndSharedEvenly() {
        final long ticks = 4000;
        for (int coProcessors = 0; coProcessors <= 3; coProcessors++) {
            long baseline = vanillaPushes(ticks, coProcessors);
            assertTrue(baseline > 0, "the vanilla baseline must move");
            for (int jobs = 1; jobs <= 6; jobs++) {
                var host = Host.of(jobs, coProcessors);
                for (long t = 0; t < ticks; t++) {
                    host.tick(t);
                }
                String where = "c=" + coProcessors + " jobs=" + jobs;
                assertTrue(host.totalPushes <= baseline + 1,
                        where + ": the scheduler must never beat vanilla (I3): "
                                + host.totalPushes + " > " + baseline);
                assertTrue(host.totalPushes >= baseline - 1,
                        where + ": total throughput must not be lost (D2): "
                                + host.totalPushes + " < " + baseline);

                long min = Long.MAX_VALUE;
                long max = 0;
                for (long p : host.pushes) {
                    min = Math.min(min, p);
                    max = Math.max(max, p);
                }
                assertTrue(max - min <= 1,
                        where + ": the pushes must be split evenly, got " + describe(host.pushes));
            }
        }
    }

    // ------------------------------------------------------------------ the lock hunt

    @Test
    @DisplayName("G4: no job starves, across job counts, machine periods, phases and budgets")
    void noJobStarvesAcrossMachineCadences() {
        final long ticks = 2000;
        for (int jobs = 2; jobs <= 5; jobs++) {
            for (int period = 1; period <= 12; period++) {
                for (int phase = 0; phase < Math.max(1, period); phase++) {
                    for (int coProcessors = 0; coProcessors <= 2; coProcessors++) {
                        var periods = new int[jobs];
                        var phases = new int[jobs];
                        java.util.Arrays.fill(periods, period);
                        java.util.Arrays.fill(phases, phase);
                        var host = new Host(jobs, coProcessors, periods, phases, new boolean[jobs]);
                        for (long t = 0; t < ticks; t++) {
                            host.tick(t);
                        }
                        String where = "jobs=" + jobs + " period=" + period + " phase=" + phase
                                + " c=" + coProcessors;
                        for (int job = 0; job < jobs; job++) {
                            assertTrue(host.pushes[job] > 0,
                                    where + ": job " + job + " never pushed (" + describe(host.pushes) + ")");
                        }
                        long min = Long.MAX_VALUE;
                        long max = 0;
                        for (long p : host.pushes) {
                            min = Math.min(min, p);
                            max = Math.max(max, p);
                        }
                        assertTrue(max - min <= Math.max(2, ticks / 200),
                                where + ": unfair split " + describe(host.pushes));
                    }
                }
            }
        }
    }

    /**
     * Machines on <i>different</i> periods, which is the shape a real base has (one assembler per recipe
     * gets its own cycle). The uniform-cadence sweep above cannot produce a resonance between two different
     * periods; this one can.
     *
     * <p><b>This test guards a starvation case:</b>
     *
     * <pre>
     * periods=[1, 11, 11, 22]: job 3 never pushed ([69, 68, 68, 0])
     * </pre>
     *
     * <p>A job whose machine opens less often than a fixed grace window (20 ticks) could never be
     * the owner at the moment its window was open: it made 20 consecutive failed attempts, was made to
     * yield, and the other jobs took the CPU - so the window it was waiting for fell on someone else's turn,
     * every time. A slow machine starved its job, and "slow" here means slower than one second per pattern.
     *
     * <p>The fix is that the decision no longer depends on a tick count but on <i>why</i> the attempt failed:
     * the host reports {@code TRANSIENT} while the machine is still working for this job, and the owner keeps
     * the CPU until its own window opens.
     */
    @Test
    @DisplayName("G4: no job starves when the jobs' machines run on different periods")
    void noJobStarvesWithMixedMachinePeriods() {
        final long ticks = 3000;
        var jobs = 4;
        for (int a = 2; a <= 11; a++) {
            for (int b = 2; b <= 11; b++) {
                var periods = new int[] { 1, a, b, a + b };
                var phases = new int[] { 0, 0, 0, 0 };
                var host = new Host(jobs, 0, periods, phases, new boolean[jobs]);
                for (long t = 0; t < ticks; t++) {
                    host.tick(t);
                }
                String where = "periods=" + java.util.Arrays.toString(periods);
                for (int job = 0; job < jobs; job++) {
                    assertTrue(host.pushes[job] > 0,
                            where + ": job " + job + " never pushed (" + describe(host.pushes) + ")");
                }
            }
        }
    }

    // ------------------------------------------------------------------ a job that can never push

    /**
     * <b>This test guards the opposite case:</b>
     *
     * <pre>
     * the healthy job lost too much to the blocked one: 91 vs baseline 500
     * </pre>
     *
     * <p>The mirror image of the other formerly-disabled test, and the reason a fixed tick bound could not
     * be the answer: when a job's machine can <b>never</b> take work, the old grace window made every other
     * job wait 20 ticks out of every 21, costing them about 20x their throughput. The bound was therefore too
     * short for slow machines and too long for dead ones - it had to depend on why the attempt failed.
     *
     * <p>Here the machine never opens and never holds a push of this job's, which in the rig is the
     * "a provider is free and still refused" case (no valid target machine behind the provider), so the host
     * answers {@code FUTILE} and the healthy job gets the CPU back on the very next tick.
     */
    @Test
    @DisplayName("G3: a job whose machine never opens cannot hold the CPU for ever")
    void aDeadJobDoesNotBlockTheOthers() {
        var host = new Host(2, 0, new int[] { 1, 1 }, new int[] { 0, 0 }, new boolean[] { false, true });
        for (long t = 0; t < 2000; t++) {
            host.tick(t);
        }
        assertTrue(host.pushes[0] > 0, "the healthy job must keep producing through the blocked one");
        assertEquals(0, host.pushes[1], "the blocked job cannot push");
        long baseline = vanillaPushes(2000, 0);
        assertTrue(host.pushes[0] > baseline / 2,
                "the healthy job lost too much to the blocked one: " + host.pushes[0] + " vs baseline "
                        + baseline);
    }

    /**
     * The residual case the safety cap exists for: a machine that answers "busy" for ever <i>while holding
     * this job's pattern</i> - an assembler destroyed mid-craft leaves the provider's send list occupied for
     * good, which stalls a vanilla CPU just as thoroughly.
     *
     * <p>The guarantee is deliberately stated as <b>bounded</b>, not fair: the stuck job holds the CPU for
     * {@link RoundRobinPolicy#DEFAULT_HOLD_CAP_TICKS} ticks per turn, so the healthy job keeps only a small
     * share. Making that fair is impossible without giving up on slow machines (that is exactly the
     * too-short/too-long trade-off that no fixed window can satisfy), and the rig that produces it is
     * already broken. What matters is that the other orders still make progress instead of stopping dead.
     */
    @Test
    @DisplayName("G4: a machine that is busy for ever costs the others at most the hold cap per turn")
    void aStuckMachineIsBoundedByTheHoldCap() {
        var host = Host.of(2, 0).withStuckMachine(1);
        for (long t = 0; t < 4000; t++) {
            host.tick(t);
        }
        assertEquals(0, host.pushes[1], "a stuck machine never delivers anything");
        long baseline = vanillaPushes(4000, 0);
        long worstCase = baseline / (RoundRobinPolicy.DEFAULT_HOLD_CAP_TICKS + 1L);
        assertTrue(host.pushes[0] >= worstCase,
                "the healthy job must keep making progress, got " + host.pushes[0] + " (floor " + worstCase
                        + ") against baseline " + baseline);
    }

    // ------------------------------------------------------------------ helpers

    private static String describe(long[] pushes) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < pushes.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(pushes[i]);
        }
        return sb.append(']').toString();
    }
}
