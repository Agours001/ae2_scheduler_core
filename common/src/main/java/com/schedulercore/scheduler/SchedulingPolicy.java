package com.schedulercore.scheduler;

/**
 * Decides, once per server tick, <b>which single job owns the CPU for this time slice</b>.
 *
 * <h2>The semantics this interface exists to enforce</h2>
 *
 * The guarantees this interface exists to enforce (see the README) say:
 *
 * <ul>
 *   <li><b>G1</b> - in any tick, exactly <b>one</b> job is served, and it is served without any quota
 *       splitting.</li>
 *   <li><b>G2</b> - while it is that job's turn, it behaves exactly like a vanilla single-job CPU,
 *       because it receives vanilla's whole per-tick budget {@code c + 1}.</li>
 * </ul>
 *
 * That is why this interface is shaped around <i>selection</i> rather than around work distribution:
 * a scheduler implementation is asked "whose turn is it?" and never "how many tokens does each job
 * get?". There is deliberately no {@code quantum}, {@code deficit} or {@code budgetWeights} anywhere
 * here: splitting the per-tick budget between jobs as tokens is exactly what makes a served job slower
 * than vanilla, and a served job must run at the vanilla rate instead.
 *
 * <h2>Contract</h2>
 *
 * {@link #tick(long, JobSource)} is called exactly once per server tick, before any work is pushed:
 *
 * <ul>
 *   <li><b>At most one</b> {@link Decision#jobId()} is non-negative per call. Implementations must
 *       never select two jobs in one tick.</li>
 *   <li>The returned budget is the <b>whole</b> per-tick budget the host should offer to that job
 *       (the host computes it as {@code cluster.getCoProcessors() + 1}).</li>
 *   <li>If the job that was selected fails to push anything, the host must report that with
 *       {@link #onPushResult(long, int, Refusal)} <b>and say why</b>. The reason decides whether the
 *       owner keeps the CPU: a machine that is still working for this job is waited for exactly as a
 *       vanilla CPU waits, while a refusal no amount of waiting can fix hands the CPU on at once. The
 *       host has to classify because only it can see the providers; the policy must not guess, because
 *       a fixed tick bound cannot be right for both a slow machine and a dead one.</li>
 *   <li>If the host can tell that the job has <b>nothing left to push</b> (no patterns remaining, so it is
 *       only waiting for returns), it must report that with {@link #onNoWork(long)}: such a job yields the
 *       CPU immediately rather than holding it while another job could work.</li>
 *   <li>Jobs that disappear must be removed from the {@link JobSource}; the scheduler detects this via
 *       {@link JobSource#indexOf(long)} and ends the slice.</li>
 * </ul>
 */
public interface SchedulingPolicy {

    /** Result of one scheduling decision. */
    record Decision(
            /** The job selected for this tick, or -1 if none could be served. */
            long jobId,
            /** Why nothing (or that something) was served - for the status command and logging. */
            Outcome outcome) {

        public static final long NONE = -1L;

        public boolean served() {
            return jobId != NONE;
        }
    }

    /** Why a tick was served or skipped. Part of the observable contract, so it is logged and asserted. */
    enum Outcome {
        /** A brand new slice started (the owner changed, or there was no owner). */
        NEW_SLICE,
        /** The current owner keeps the CPU for another tick. */
        CONTINUED,
        /** No job could be served: the source was empty, or every job was blocked. */
        NO_JOB
    }

    /**
     * Why a served job pushed nothing - the host's answer to the only question the policy needs answered:
     * <b>would retrying this job on a later tick help?</b>
     *
     * <p>This is deliberately not "what went wrong inside AE2". The policy has no business knowing about
     * pattern providers, and the host has no business deciding how long a job may hold the CPU. The host
     * observes the machine and answers one of three things; the policy turns that answer into a slice
     * decision.
     */
    enum Refusal {
        /** Not a refusal: the job pushed at least one pattern this tick. */
        NONE,

        /**
         * The refusal is one that waiting can fix: the machine is still working for this job (every provider
         * that could take its pattern is busy), or the CPU had no budget this tick so the machine was never
         * even asked. Retrying is exactly what a vanilla single-job CPU does - it calls
         * {@code executeCrafting} every tick and never abandons the job - so the owner keeps the CPU,
         * bounded by {@link #holdCapTicks()}.
         *
         * <p>Note what is <i>not</i> distinguished here: a network that cannot pay for the pattern is
         * reported as this case too. That is deliberate, because a power shortage stops every job on the
         * CPU equally, so holding through it costs the other orders nothing, and it comes back by itself.
         */
        TRANSIENT,

        /**
         * Nothing can accept this job's pattern and no amount of waiting on the CPU changes that: no
         * provider is registered for it, or a provider is free and still refused (the job's inputs are not
         * in the CPU inventory yet). Holding the CPU could not produce anything, so the slice ends at once
         * and the other orders get their turn.
         *
         * <p>The "provider is free" half of the test is what makes this case detectably hopeless: a
         * provider only refuses while it has a push in flight, so a free provider that still refuses means
         * the refusal came from the job's own inputs, not from the machine.
         */
        FUTILE
    }

    /** Where a tick's outcome came from. Purely descriptive; never used to make decisions. */
    record TickResult(Decision decision, boolean sliceEnded) {
    }

    /**
     * Selects the owner of this tick's time slice.
     *
     * @param now    the current server tick, used only for logging/reporting (never for fairness maths -
     *               fairness is in <i>slices</i>, not in wall-clock time)
     * @param source the jobs currently on the CPU, in consideration order
     */
    TickResult tick(long now, JobSource source);

    /**
     * Reports how many patterns the selected job actually pushed, and why it pushed none when it did not.
     *
     * <p>Called by the host immediately after serving {@link Decision#jobId()} with the host's whole
     * per-tick budget. {@code pushed == 0} means the job could not be pushed at all this tick, and
     * {@code refusal} says whether waiting could change that - see {@link Refusal}. {@code pushed > 0}
     * always comes with {@link Refusal#NONE}.
     *
     * <p>{@link Refusal#NONE} with {@code pushed == 0} means the host could not classify the failure. It is
     * treated as "retry", because that is what a vanilla CPU does and because giving the CPU away on an
     * unexplained failure is the shape of the phase lock described in {@link Refusal#TRANSIENT}.
     */
    void onPushResult(long jobId, int pushed, Refusal refusal);

    /**
     * Reports that the selected job has nothing left to push.
     *
     * <p>Distinct from {@link #onPushResult(long, int, Refusal)} with a zero: that means "the machine would
     * not take it", this means "there is nothing to take". A job whose patterns are all in flight is only
     * waiting for returns, so it must yield the CPU at once - holding it could not produce anything,
     * whereas the other jobs still might.
     */
    void onNoWork(long jobId);

    /** Rolls back a selection whose push threw. The job must not keep the CPU after an error. */
    void onPushError(long jobId);

    /** The job that currently owns the time slice, or -1. */
    long currentOwner();

    /** Ticks remaining in the current owner's slice; 0 when there is no owner. */
    int sliceTicksRemaining();

    /** Monotonic count of slices granted, for the status command and for tests. */
    long slicesGranted();

    /**
     * How many consecutive {@link Refusal#TRANSIENT} attempts an owner may make before the turn passes.
     *
     * <p>This is a <b>safety cap, not a machine model</b>. The rule that makes a slow machine work is
     * "hold while the machine is still working for you"; the cap only guarantees that a machine which is
     * stuck for ever cannot freeze the whole CPU, so it is set far above any plausible machine cycle
     * rather than close to it. Reported so a machine that hits it is diagnosable from the game itself:
     * {@code /schedulercore cpus} prints it next to the job list. A policy that never holds reports 0, i.e.
     * it hands the CPU over on the first failed attempt.
     */
    default int holdCapTicks() {
        return 0;
    }

    /** Human-readable name of this policy, e.g. {@code FAIR}. */
    String name();
}
