package com.schedulercore.scheduler;

/**
 * Equal-length round-robin: every job gets one whole tick, then the turn passes to the next job.
 *
 * <p>This is the only policy implemented for now, on purpose. The extension point
 * ({@link SchedulingPolicy}) exists so that a smarter policy can be added later without touching the
 * AE2 integration. Nothing beyond equal-length rotation is built now, on purpose: a multi-tier policy with
 * aging and reservation quotas is exactly the kind of complexity that becomes unauditable.
 *
 * <h2>Behaviour, in full</h2>
 *
 * <ol>
 *   <li>If the current owner is no longer in the {@link JobSource} (finished, cancelled, released), the
 *       slice ends and a new owner is chosen.</li>
 *   <li>If the current owner's slice has expired, the slice ends and the turn passes to the next
 *       present, unblocked job.</li>
 *   <li>Otherwise the current owner keeps the CPU for this tick.</li>
 *   <li>If the current owner could not push anything ({@code pushed == 0}), what happens next depends on
 *       <b>why</b> the host says it failed ({@link Refusal}):
 *       <ul>
 *         <li>{@link Refusal#FUTILE} - nothing can accept the job's pattern, so waiting on the CPU cannot
 *             help. The slice ends at once and the turn passes.</li>
 *         <li>{@link Refusal#TRANSIENT} (or {@link Refusal#NONE} with nothing pushed, i.e. the host could
 *             not classify it) - the machine is still working for this job. The owner is retried on the
 *             next tick, up to {@link #holdCapTicks()} consecutive attempts, exactly as a vanilla CPU
 *             retries {@code executeCrafting} every tick and never abandons its job.</li>
 *       </ul></li>
 *   <li>If the host reports that the owner has nothing left to push at all ({@link #onNoWork(long)}), the
 *       slice ends immediately - holding the CPU could not produce anything, and the other jobs might.</li>
 *   <li>After a <b>successful</b> push the owner does not keep the CPU: the slice expires at the start of
 *       the next tick, so the turn passes. This is what keeps a job from monopolising a machine that frees
 *       up every few ticks - it waits for its own machine only while that machine is busy.</li>
 *   <li>Choosing the next owner walks forward from the job served in the previous slice and takes the
 *       first job that is {@code READY}; {@code BLOCKED} jobs are skipped. If every job is blocked,
 *       nothing is served and no slice is consumed.</li>
 * </ol>
 *
 * <p>The rotation is over <i>slices</i>, never over tokens: this class has no concept of a budget split
 * and grants the whole per-tick budget to whoever it selects.
 *
 * <h2>Why the failure reason matters, and why a fixed window could not work</h2>
 *
 * <p>The first version of this rule was "a failed attempt is retried for {@code graceTicks = 20}
 * consecutive attempts, then the turn passes". It fixed the phase lock it was written for, but a pure-logic
 * simulation of the whole loop later showed it was wrong in both directions at once:
 *
 * <ul>
 *   <li><b>Too short for a slow machine.</b> A job whose provider frees up less often than 20 ticks never
 *       owns the CPU at the moment its window is open: it accumulates 20 failures, is made to yield, and
 *       the window falls on someone else's turn - every time. Measured in the simulation as
 *       {@code periods=[1,11,11,22] -> [69,68,68,0]}: a job with 22-tick machines was starved outright.
 *       "Slow" here means one second per pattern, which a real assembler-backed order can easily be.</li>
 *   <li><b>Too long for a dead machine.</b> A job whose machine can never take work holds 20 ticks out of
 *       every 21, costing the healthy jobs about 20x their throughput (measured: 91 pushes against a
 *       vanilla baseline of 500).</li>
 * </ul>
 *
 * <p>No fixed number can be both, which is the whole argument for asking the host <i>why</i> the attempt
 * failed instead of counting ticks. The cap below is therefore not a machine model: it is the bound that
 * stops a permanently stuck machine (one that answers {@code isBusy()} for ever, which also stalls a
 * vanilla CPU) from freezing every other order on the CPU.
 *
 * <h2>Why the rotation is driven by the previously served job id</h2>
 *
 * <p>An earlier draft kept a mutable "search from index" cursor and advanced it only when a slice
 * <i>started</i>. That is wrong in a way that matters: when a slice expires, the cursor still pointed at
 * the job that had just been served, so that same job won the next search and <b>the CPU never rotated
 * at all</b> - the rotation simply never happened. Deriving the search start from {@code lastServedId}
 * instead makes rotation the default and keeps it correct when jobs are inserted or removed, because a
 * stale id simply resolves to "not found" and the search restarts from 0.
 */
public final class RoundRobinPolicy implements SchedulingPolicy {

    /** Length of one time slice, in ticks. One tick is the shipped default (requirements/D4). */
    private final int sliceTicks;

    /**
     * Safety cap on consecutive {@link Refusal#TRANSIENT} attempts by one owner, in ticks.
     *
     * <p>Set far above any plausible machine cycle rather than near one: a molecular assembler completes a
     * pattern in about 10 ticks, and even a heavily throttled one is well under ten seconds, so 200 ticks
     * is "ten times any machine seen" - the point being that a job which holds that long without producing
     * anything has demonstrated it is not waiting on a machine that works, and must not be allowed to
     * freeze the other orders for ever.
     */
    public static final int DEFAULT_HOLD_CAP_TICKS = 200;

    private final int holdCapTicks;

    private long owner = Decision.NONE;
    private long lastServedId = Decision.NONE;
    private int sliceRemaining;
    /** Whether the current slice has been served at least once; prevents ending a slice on the tick it starts. */
    private boolean sliceServed;
    private long slicesGranted;
    /** Consecutive {@link Refusal#TRANSIENT} attempts by the current owner; reset on a push and on a new slice. */
    private int heldAttempts;

    public RoundRobinPolicy() {
        this(1);
    }

    public RoundRobinPolicy(int sliceTicks) {
        this(sliceTicks, DEFAULT_HOLD_CAP_TICKS);
    }

    public RoundRobinPolicy(int sliceTicks, int holdCapTicks) {
        if (sliceTicks < 1) {
            throw new IllegalArgumentException("sliceTicks must be >= 1, got " + sliceTicks);
        }
        if (holdCapTicks < 0) {
            throw new IllegalArgumentException("holdCapTicks must be >= 0, got " + holdCapTicks);
        }
        this.sliceTicks = sliceTicks;
        this.holdCapTicks = holdCapTicks;
    }

    @Override
    public TickResult tick(long now, JobSource source) {
        boolean sliceEnded = false;

        // (1) The owner vanished from the source: finished, cancelled or released.
        if (owner != Decision.NONE && source.indexOf(owner) < 0) {
            endSlice();
            sliceEnded = true;
        }

        // (2) The owner is present but its slice is used up. Note the sliceServed guard: a slice created
        // on this very tick still has sliceRemaining == sliceTicks but must not be ended before it is
        // served, otherwise sliceTicks=1 would hand the CPU over without ever serving anyone.
        if (owner != Decision.NONE && sliceServed && sliceRemaining <= 0) {
            endSlice();
            sliceEnded = true;
        }

        // (3) Nothing to do at all.
        if (source.size() == 0) {
            if (owner != Decision.NONE) {
                endSlice();
                sliceEnded = true;
            }
            return new TickResult(new Decision(Decision.NONE, Outcome.NO_JOB), sliceEnded);
        }

        // (4) Start a new slice if we have no owner.
        if (owner == Decision.NONE) {
            int chosen = chooseNext(source, searchStart(source));
            if (chosen < 0) {
                // Every job is blocked. Consume no slice: a BLOCKED job may become READY next tick.
                return new TickResult(new Decision(Decision.NONE, Outcome.NO_JOB), sliceEnded);
            }
            owner = source.jobIdAt(chosen);
            lastServedId = owner;
            sliceRemaining = sliceTicks;
            sliceServed = false;
            slicesGranted++;
            return new TickResult(new Decision(owner, Outcome.NEW_SLICE), sliceEnded);
        }

        return new TickResult(new Decision(owner, Outcome.CONTINUED), sliceEnded);
    }

    @Override
    public void onPushResult(long jobId, int pushed, Refusal refusal) {
        if (jobId == Decision.NONE || jobId != owner) {
            return;
        }

        // The owner has now had this tick. Decrementing before the "could it push?" check is what makes
        // sliceTicks=1 mean exactly one tick of ownership: the tick we just served is the slice.
        sliceRemaining--;
        sliceServed = true;

        if (pushed > 0) {
            heldAttempts = 0;
            // The slice is not ended here even when it is used up: rule (2) of tick() ends it at the start of
            // the next tick, which is what keeps "the owner vanished" and "the slice expired" reported the
            // same way they always were.
            return;
        }

        // Nothing was pushed. Whether that is worth waiting for is the host's answer, not a guess here:
        // FUTILE means waiting cannot help, so the CPU goes to whoever else might be able to work.
        if (refusal == Refusal.FUTILE) {
            endSlice();
            return;
        }

        // TRANSIENT (or unclassified): the machine is still working for this job. Retry it, as a vanilla
        // single-job CPU does, but not for ever - see DEFAULT_HOLD_CAP_TICKS.
        heldAttempts++;
        if (heldAttempts > holdCapTicks) {
            endSlice();
            return;
        }
        if (sliceRemaining <= 0) {
            // Grant the same owner another tick's worth of attempts.
            sliceRemaining = sliceTicks;
            sliceServed = false;
        }
    }

    @Override
    public void onNoWork(long jobId) {
        if (jobId != Decision.NONE && jobId == owner) {
            // Nothing to push, so holding the CPU cannot produce anything. Yield at once.
            endSlice();
        }
    }

    @Override
    public void onPushError(long jobId) {
        if (jobId != Decision.NONE && jobId == owner) {
            endSlice();
        }
    }

    @Override
    public long currentOwner() {
        return owner;
    }

    @Override
    public int sliceTicksRemaining() {
        return sliceRemaining;
    }

    @Override
    public long slicesGranted() {
        return slicesGranted;
    }

    @Override
    public String name() {
        return "FAIR";
    }

    /** Safety cap on consecutive {@code TRANSIENT} attempts by one owner, as configured. */
    @Override
    public int holdCapTicks() {
        return holdCapTicks;
    }

    // ------------------------------------------------------------------ internals
    private void endSlice() {
        owner = Decision.NONE;
        sliceRemaining = 0;
        sliceServed = false;
        heldAttempts = 0;
    }

    /**
     * Where to begin looking for the next owner: just after the job served in the previous slice.
     *
     * <p>If that job is gone (finished/cancelled) we restart from 0. The rotation is therefore still
     * fair - a departed job simply costs one restart, and the walk below is itself wrapped.
     */
    private int searchStart(JobSource source) {
        if (lastServedId == Decision.NONE) {
            return 0;
        }
        int lastIndex = source.indexOf(lastServedId);
        return lastIndex < 0 ? 0 : lastIndex + 1;
    }

    /**
     * Walks forward from {@code from} and returns the index of the first {@code READY} job, or -1.
     *
     * <p>Examines each index at most once, so a source where every job is blocked costs O(n) and selects
     * nothing - the tick is skipped rather than spinning.
     */
    private static int chooseNext(JobSource source, int from) {
        int size = source.size();
        if (size <= 0) {
            return -1;
        }
        int start = Math.floorMod(from, size);
        for (int i = 0; i < size; i++) {
            int idx = (start + i) % size;
            if (source.statusOf(idx) == JobSource.Status.READY) {
                return idx;
            }
        }
        return -1;
    }

    @Override
    public String toString() {
        return "RoundRobinPolicy[sliceTicks=" + sliceTicks + ", holdCapTicks=" + holdCapTicks
                + ", owner=" + owner + ", slicesGranted=" + slicesGranted + "]";
    }
}
