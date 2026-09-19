package com.schedulercore.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.schedulercore.scheduler.SchedulingPolicy.Outcome;
import com.schedulercore.scheduler.SchedulingPolicy.Refusal;

/**
 * L1 assertions: the pure time-slice scheduling semantics, with no Minecraft and no AE2 involved.
 *
 * <p>Each test names the guarantee it pins down (see the README's "Guarantees" table). These
 * are deliberately written against <b>externally observable behaviour</b> - "exactly one job per tick",
 * "slices rotate evenly", "the served job gets the whole budget" - and never against an implementation
 * detail such as a token counter. That distinction matters: invariants written in terms of a mechanism
 * (per-tick token sharing, say) cannot detect that the mechanism itself is wrong, however green the suite.
 */
class RoundRobinPolicyTest {

    // ------------------------------------------------------------------ G1: one job per tick

    @Test
    @DisplayName("G1: exactly one job is served per tick, and never two")
    void exactlyOneJobPerTick() {
        var source = new TestJobSource(3);
        var policy = new RoundRobinPolicy();

        int servedTicks = 0;
        for (long t = 0; t < 30; t++) {
            var result = policy.tick(t, source);
            if (result.decision().served()) {
                servedTicks++;
            }
            policy.onPushResult(result.decision().jobId(), 1, Refusal.NONE);
        }
        assertEquals(30, servedTicks, "every tick must serve a job while all three are READY");
    }

    @Test
    @DisplayName("G1: a single job is served on every tick (nothing splits it)")
    void singleJobIsServedEveryTick() {
        var source = new TestJobSource(1);
        var policy = new RoundRobinPolicy();

        for (long t = 0; t < 10; t++) {
            var result = policy.tick(t, source);
            assertEquals(0L, result.decision().jobId(), "the only job must be served on tick " + t);
            policy.onPushResult(0L, 4, Refusal.NONE);
        }
    }

    // ------------------------------------------------------------------ I5: fair rotation

    @Test
    @DisplayName("G4: three jobs rotate in order over nine ticks, three slices each")
    void roundRobinRotatesInOrder() {
        var source = new TestJobSource(3);
        var policy = new RoundRobinPolicy();

        var served = new ArrayList<Long>();
        for (long t = 0; t < 9; t++) {
            var result = policy.tick(t, source);
            served.add(result.decision().jobId());
            policy.onPushResult(result.decision().jobId(), 1, Refusal.NONE);
        }

        // The regression this test exists for: an earlier cursor bug made the same job win every time.
        assertEquals(List.of(0L, 1L, 2L, 0L, 1L, 2L, 0L, 1L, 2L), served,
                "the turn must pass to the next job after every slice");
    }

    @Test
    @DisplayName("G4: slice counts stay within one of each other over many ticks")
    void slicesAreEven() {
        var source = new TestJobSource(5);
        var policy = new RoundRobinPolicy();

        Map<Long, Integer> slices = new LinkedHashMap<>();
        for (long t = 0; t < 500; t++) {
            var result = policy.tick(t, source);
            if (result.decision().served()) {
                slices.merge(result.decision().jobId(), 1, Integer::sum);
            }
            policy.onPushResult(result.decision().jobId(), 1, Refusal.NONE);
        }

        assertEquals(5, slices.size(), "every job must eventually be served");
        int min = slices.values().stream().mapToInt(Integer::intValue).min().orElseThrow();
        int max = slices.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
        assertTrue(max - min <= 1, "slice counts must be even, got " + slices);
        assertEquals(500, (int) slices.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    @DisplayName("G4: no job waits longer than (job count - 1) slices when nothing is blocked")
    void noStarvation() {
        var source = new TestJobSource(4);
        var policy = new RoundRobinPolicy();

        Map<Long, Integer> ticksSinceServed = new LinkedHashMap<>();
        for (long id = 0; id < 4; id++) {
            ticksSinceServed.put(id, 0);
        }

        for (long t = 0; t < 200; t++) {
            var result = policy.tick(t, source);
            ticksSinceServed.replaceAll((id, waited) -> id == result.decision().jobId() ? 0 : waited + 1);
            policy.onPushResult(result.decision().jobId(), 1, Refusal.NONE);
            for (var entry : ticksSinceServed.entrySet()) {
                assertTrue(entry.getValue() <= 4,
                        "job " + entry.getKey() + " waited " + entry.getValue() + " ticks");
            }
        }
    }

    // ------------------------------------------------------------------ slice length

    @Test
    @DisplayName("D4: with sliceTicks > 1 the owner keeps the CPU for that many ticks, then hands over")
    void sliceLengthIsHonoured() {
        var source = new TestJobSource(2);
        var policy = new RoundRobinPolicy(3);

        var served = new ArrayList<Long>();
        for (long t = 0; t < 12; t++) {
            var result = policy.tick(t, source);
            served.add(result.decision().jobId());
            policy.onPushResult(result.decision().jobId(), 1, Refusal.NONE);
        }

        // 3 ticks owned, then the hand-over tick serves the next job.
        assertEquals(List.of(0L, 0L, 0L, 1L, 1L, 1L, 0L, 0L, 0L, 1L, 1L, 1L), served,
                "one slice must be a whole run of ticks owned by the same job");
    }

    // ------------------------------------------------------------------ work conservation

    @Test
    @DisplayName("G2: a transient failure is retried on the next tick before the turn passes")
    void failedAttemptIsRetried() {
        var source = new TestJobSource(2);
        var policy = new RoundRobinPolicy();

        var first = policy.tick(0, source);
        assertEquals(0L, first.decision().jobId());
        policy.onPushResult(0L, 0, Refusal.TRANSIENT); // the machine is still working for this job
        assertEquals(0L, policy.currentOwner(),
                "a job the machine refused must be retried, not punished: a vanilla CPU tries every tick");

        var second = policy.tick(1, source);
        assertEquals(0L, second.decision().jobId(), "the retry must go to the same job");
        policy.onPushResult(0L, 1, Refusal.NONE);

        var third = policy.tick(2, source);
        assertEquals(1L, third.decision().jobId(),
                "once it has pushed, the turn must pass to the next job");
    }

    /**
     * The other half of the failure classification: a refusal that waiting cannot fix must not cost the
     * other orders a single tick.
     *
     * <p>The "dead machine" half of the failure-classification problem: a fixed grace window made a job whose
     * machine can never take work hold 20 ticks out of every 21, cutting the healthy jobs to about a
     * twentieth of their throughput. The host answers {@code FUTILE} for that case (no provider registered,
     * or a provider is free and still refused), and the turn passes at once.
     */
    @Test
    @DisplayName("G4: a refusal that waiting cannot fix ends the slice at once")
    void futileRefusalYieldsImmediately() {
        var source = new TestJobSource(2);
        var policy = new RoundRobinPolicy();

        var first = policy.tick(0, source);
        assertEquals(0L, first.decision().jobId());
        policy.onPushResult(0L, 0, Refusal.FUTILE);
        assertEquals(SchedulingPolicy.Decision.NONE, policy.currentOwner(),
                "a job whose machine can never take work must not hold the CPU");

        var next = policy.tick(1, source);
        assertEquals(1L, next.decision().jobId(), "the other job must get the CPU on the very next tick");
    }

    @Test
    @DisplayName("G4: an owner that never pushes still yields after the hold cap")
    void holdCapIsBounded() {
        var source = new TestJobSource(2);
        var policy = new RoundRobinPolicy(1, 3);

        policy.tick(0, source);
        policy.onPushResult(0L, 0, Refusal.TRANSIENT);
        policy.onPushResult(0L, 0, Refusal.TRANSIENT);
        policy.onPushResult(0L, 0, Refusal.TRANSIENT);
        assertEquals(0L, policy.currentOwner(), "within the hold cap the owner keeps the CPU");
        policy.onPushResult(0L, 0, Refusal.TRANSIENT); // one attempt past the cap
        assertEquals(SchedulingPolicy.Decision.NONE, policy.currentOwner(),
                "a machine that never frees up must not sit on the CPU for ever");

        var next = policy.tick(1, source);
        assertEquals(1L, next.decision().jobId(), "the next job must get its turn");
    }

    @Test
    @DisplayName("G2: a machine slower than any fixed window is still waited for")
    void slowMachineIsWaitedFor() {
        var source = new TestJobSource(2);
        var policy = new RoundRobinPolicy();
        var slowMachinePeriod = 60; // three times the old grace window of 20

        var pushes = new long[2];
        for (long t = 0; t < 4 * slowMachinePeriod; t++) {
            var result = policy.tick(t, source);
            long id = result.decision().jobId();
            // One machine for both jobs, opening once every `slowMachinePeriod` ticks: the owner of that
            // tick is the only job that can use it. Waiting for the window is the whole point.
            boolean open = t % slowMachinePeriod == 0;
            if (open) {
                pushes[(int) id]++;
                policy.onPushResult(id, 1, Refusal.NONE);
            } else {
                policy.onPushResult(id, 0, Refusal.TRANSIENT);
            }
        }

        assertTrue(pushes[0] > 0 && pushes[1] > 0,
                "a slow machine must serve both jobs, got [" + pushes[0] + ", " + pushes[1] + "]");
        assertEquals(pushes[0], pushes[1], "the windows must be shared evenly");
    }

    @Test
    @DisplayName("G3: a job with nothing left to push yields the CPU at once")
    void noWorkYieldsImmediately() {
        var source = new TestJobSource(2);
        var policy = new RoundRobinPolicy();

        policy.tick(0, source);
        policy.onPushResult(0L, 0, Refusal.TRANSIENT);
        policy.onNoWork(0L); // pushing nothing because there is nothing to push
        assertEquals(SchedulingPolicy.Decision.NONE, policy.currentOwner(),
                "a job that is only waiting for returns must not hold the CPU");

        var next = policy.tick(1, source);
        assertEquals(1L, next.decision().jobId());
    }

    /**
     * The regression this whole rule exists for: a machine that accepts one push per {@code period} ticks.
     *
     * <p>Both jobs always have work and full budget; the <i>machine</i> is the only scarce thing, and it is
     * shared. Under the previous rule (a failed attempt ends the slice) this locked permanently: with two
     * jobs the rotation has period two, the machine's window has period {@code period}, and the job served
     * right after each successful push was always the same one, so it won every window for ever. Measured on
     * the rig as one job racing 1000 -> 0 while the other sat frozen at 948 for four minutes.
     */
    @Test
    @DisplayName("G4: two jobs sharing a periodic machine window both make progress")
    void sharedMachineWindowDoesNotLock() {
        var period = 10;
        var source = new TestJobSource(2);
        var policy = new RoundRobinPolicy();

        Map<Long, Integer> pushes = new LinkedHashMap<>();
        pushes.put(0L, 0);
        pushes.put(1L, 0);

        for (long t = 0; t < 400; t++) {
            var result = policy.tick(t, source);
            long id = result.decision().jobId();
            // The machine accepts exactly one pattern every `period` ticks; on every other tick nothing can
            // be pushed through it, by either job. That is what makes this a shared-resource race rather
            // than a question about budget: the only meaningful tick is the one where the window is open.
            int pushed = t % period == 0 ? 1 : 0;
            if (pushed > 0) {
                pushes.merge(id, 1, Integer::sum);
            }
            policy.onPushResult(id, pushed, pushed > 0 ? Refusal.NONE : Refusal.TRANSIENT);
        }

        int a = pushes.get(0L);
        int b = pushes.get(1L);
        assertTrue(a > 0 && b > 0, "both jobs must use the machine, got " + pushes);
        assertTrue(Math.abs(a - b) <= 1,
                "the machine windows must be shared evenly, got " + pushes);
    }

    /**
     * The same machine model with the hold cap turned off, i.e. the rule that shipped before this fix.
     *
     * <p>This is what makes the test above worth having: it is not a restatement of the implementation, it
     * distinguishes the two rules. With {@code holdCapTicks = 0} a failed attempt ends the slice, the rotation
     * locks on to the machine's period, and one job takes <b>every</b> window while the other gets none -
     * the "new order is scheduled first and the old one only resumes when it finishes" report, reproduced
     * with no game running.
     */
    @Test
    @DisplayName("G4: without a hold cap the rotation locks on to the machine's period (the old bug)")
    void noGraceWindowLocks() {
        var period = 10;
        var source = new TestJobSource(2);
        var policy = new RoundRobinPolicy(1, 0);

        Map<Long, Integer> pushes = new LinkedHashMap<>();
        pushes.put(0L, 0);
        pushes.put(1L, 0);
        for (long t = 0; t < 400; t++) {
            var result = policy.tick(t, source);
            long id = result.decision().jobId();
            int pushed = t % period == 0 ? 1 : 0;
            if (pushed > 0) {
                pushes.merge(id, 1, Integer::sum);
            }
            policy.onPushResult(id, pushed, pushed > 0 ? Refusal.NONE : Refusal.TRANSIENT);
        }

        assertTrue(pushes.containsValue(0),
                "with no hold cap one job must lose every window, got " + pushes);
    }

    @Test
    @DisplayName("G4: when every job is blocked nothing is served, and no slice is consumed")
    void allBlockedServesNothing() {
        var source = new TestJobSource(3);
        source.setAllBlocked(true);
        var policy = new RoundRobinPolicy();

        for (long t = 0; t < 5; t++) {
            var result = policy.tick(t, source);
            assertFalse(result.decision().served(), "a fully blocked CPU must serve nothing");
            assertEquals(Outcome.NO_JOB, result.decision().outcome());
        }
        assertEquals(0, policy.slicesGranted(), "blocked ticks must not count as granted slices");
    }

    @Test
    @DisplayName("G4: blocked jobs are skipped, present jobs still rotate")
    void blockedJobsAreSkipped() {
        var source = new TestJobSource(3);
        source.setBlocked(1, true);
        var policy = new RoundRobinPolicy();

        var served = new ArrayList<Long>();
        for (long t = 0; t < 6; t++) {
            var result = policy.tick(t, source);
            served.add(result.decision().jobId());
            policy.onPushResult(result.decision().jobId(), 1, Refusal.NONE);
        }
        assertFalse(served.contains(1L), "a blocked job must never be served");
        assertEquals(List.of(0L, 2L, 0L, 2L, 0L, 2L), served);
    }

    // ------------------------------------------------------------------ job lifecycle

    @Test
    @DisplayName("G1: when the owner leaves the source (finished/cancelled) the slice ends")
    void ownerGoneEndsSlice() {
        var source = new TestJobSource(2);
        var policy = new RoundRobinPolicy();

        var first = policy.tick(0, source);
        assertEquals(0L, first.decision().jobId());
        policy.onPushResult(0L, 1, Refusal.NONE);

        source.removeJob(0);

        var second = policy.tick(1, source);
        assertEquals(1L, second.decision().jobId(), "the surviving job must take over");
        assertTrue(second.sliceEnded(), "losing the owner must be reported as a slice end");
    }

    @Test
    @DisplayName("G1: a push that throws must not leave the job holding the CPU")
    void pushErrorEndsSlice() {
        var source = new TestJobSource(2);
        var policy = new RoundRobinPolicy();

        policy.tick(0, source);
        policy.onPushError(0L);
        assertEquals(SchedulingPolicy.Decision.NONE, policy.currentOwner());

        var next = policy.tick(1, source);
        assertNotEquals(0L, next.decision().jobId(), "the following job must get the CPU");
    }

    @Test
    @DisplayName("G3: an empty CPU serves nothing and stays idle")
    void emptySourceIsIdle() {
        var source = new TestJobSource(0);
        var policy = new RoundRobinPolicy();

        for (long t = 0; t < 4; t++) {
            var result = policy.tick(t, source);
            assertFalse(result.decision().served());
            assertEquals(Outcome.NO_JOB, result.decision().outcome());
        }
        assertEquals(0, policy.slicesGranted());
    }

    // ------------------------------------------------------------------ helpers
}
