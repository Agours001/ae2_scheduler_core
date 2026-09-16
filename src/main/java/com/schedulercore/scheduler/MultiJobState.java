package com.schedulercore.scheduler;

import java.util.ArrayList;
import java.util.List;

import appeng.crafting.execution.ExecutingCraftingJob;

/**
 * The set of jobs one crafting CPU is holding, plus the time-slice policy that decides which of them owns
 * the current tick.
 *
 * <p>This is deliberately a plain class with no Minecraft or AE2 dependencies beyond the job type, so the
 * admission/rotation logic can be unit-tested without a game running. The mixin that hooks AE2's crafting
 * CPU only routes calls into here.
 *
 * <h2>The invariant this class exists to hold</h2>
 *
 * <p>{@link #tick} returns <b>at most one</b> job per call, and that job receives the caller's entire
 * per-tick budget. There is no budget splitting anywhere in this class - no quantum, no deficit, no
 * weights. Each job, while it owns the CPU, behaves like a vanilla single-job CPU; the sharing happens
 * between ticks, not inside one.
 */
public final class MultiJobState {

    /** One admitted job plus the storage it has reserved. */
    public static final class Slot {
        final long id;
        final ExecutingCraftingJob job;
        /** {@code plan.bytes()}, reserved against the CPU's capacity for as long as this job lives. */
        final long reservedBytes;
        /**
         * How much the order asked for ({@code plan.finalOutput().amount()}).
         *
         * <p>Kept because nothing else remembers it: the job object only holds what is <i>still</i> owed
         * ({@code remainingAmount}), so without this a status line could say "140 left" but never
         * "140 of 200". The tracker cannot supply it either - see {@link #progressPercent}.
         */
        final long totalAmount;

        Slot(long id, ExecutingCraftingJob job, long reservedBytes, long totalAmount) {
            this.id = id;
            this.job = job;
            this.reservedBytes = reservedBytes;
            this.totalAmount = totalAmount;
        }

        public long id() {
            return id;
        }

        public ExecutingCraftingJob job() {
            return job;
        }

        public long reservedBytes() {
            return reservedBytes;
        }

        public long totalAmount() {
            return totalAmount;
        }

        /** How much of the order has already been delivered. */
        public long delivered(long remaining) {
            return Math.max(0, totalAmount - remaining);
        }
    }

    /**
     * Reads one job's internals.
     *
     * <p>{@code ExecutingCraftingJob}'s fields are package-private, so the scheduler cannot touch them
     * directly. Rather than reflecting here, the mixin package provides accessors and hands one in as a
     * plain functional interface - that keeps this class free of both Minecraft and AE2-internal types, so
     * it stays unit-testable.
     */
    public interface JobView {
        /** The job's crafting link. */
        appeng.crafting.CraftingLink link(ExecutingCraftingJob job);

        /** The job's final output, or null before it is known. */
        appeng.api.stacks.GenericStack finalOutput(ExecutingCraftingJob job);

        /** How much of the final output is still outstanding. */
        long remainingAmount(ExecutingCraftingJob job);

        void setRemainingAmount(ExecutingCraftingJob job, long remaining);

        boolean suspended(ExecutingCraftingJob job);

        /** Suspends or resumes one job; a suspended job is skipped by the rotation. */
        void setSuspended(ExecutingCraftingJob job, boolean suspended);

        /** How much of this key the job is waiting for. */
        long waitingFor(ExecutingCraftingJob job, appeng.api.stacks.AEKey key);

        /** Consumes up to {@code amount} from the job's waiting-for ledger. */
        long takeWaitingFor(ExecutingCraftingJob job, appeng.api.stacks.AEKey key, long amount,
                appeng.api.config.Actionable mode);

        /**
         * The job's elapsed-time tracker - the source of progress, elapsed time and ETA.
         *
         * <p>Needed so status reporting can answer for a specific job rather than for whichever job
         * vanilla's single {@code job} field happens to name.
         */
        appeng.crafting.execution.ElapsedTimeTracker timeTracker(ExecutingCraftingJob job);

        /** Accounts {@code amount} of {@code key} against the job's tracker, as vanilla's insert does. */
        void decrementTracker(ExecutingCraftingJob job, appeng.api.stacks.AEKey key, long amount);

        /** Serialises one job. */
        net.minecraft.nbt.CompoundTag writeToNBT(ExecutingCraftingJob job,
                net.minecraft.core.HolderLookup.Provider registries);

        /**
         * How many incoming items the CPU's dump guard has refused since it was created.
         *
         * <p>Exposed only so the state is inspectable from the game: a non-zero value means the CPU was
         * asked to take an item while it was pushing its own inventory into the network, which is exactly
         * the case vanilla's {@code insert} refuses with its {@code job == null} check. It is reported, not
         * acted on.
         */
        int guardRefusals();
    }

    private final JobView view;

    private final List<Slot> slots = new ArrayList<>();
    private final RoundRobinPolicy policy = new RoundRobinPolicy();
    private long nextId = 1;
    /**
     * The job the CPU last actually worked on.
     *
     * <p>Used only by {@link #currentSlot()} as the fallback when no slice is active - which is precisely the
     * situation a suspended job creates. See that method for why it matters.
     */
    private long lastServed = SchedulingPolicy.Decision.NONE;

    /** The order the details pane is focused on, or NONE. See {@link #focus(long)}. */
    private long focusedSlot = SchedulingPolicy.Decision.NONE;
    /** The CPU cluster this state belongs to, used only so tools can find the state again. */
    private final Object cluster;

    /**
     * Every live scheduler state, keyed by its crafting CPU cluster.
     *
     * <p>Deliberately a weak map: the state lives as long as the mixin's field does, and a dismantled CPU
     * must not be kept alive by this registry. It exists so the {@code /schedulercore} commands can report
     * on a specific CPU without reaching into the mixin.
     */
    private static final java.util.Map<Object, MultiJobState> REGISTRY =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public MultiJobState(JobView view) {
        this(view, null);
    }

    public MultiJobState(JobView view, Object cluster) {
        this.view = view;
        this.cluster = cluster;
        if (cluster != null) {
            REGISTRY.put(cluster, this);
        }
    }

    /** The scheduler state for a crafting CPU cluster, or null when it holds no scheduler jobs. */
    public static MultiJobState forCluster(Object cluster) {
        return cluster == null ? null : REGISTRY.get(cluster);
    }

    /** The view used to read job internals. */
    public JobView view() {
        return view;
    }

    /** Jobs in admission order (the order the rotation considers them in). */
    public List<Slot> slots() {
        return slots;
    }

    public int size() {
        return slots.size();
    }

    public boolean isEmpty() {
        return slots.isEmpty();
    }

    public long totalReservedBytes() {
        long total = 0;
        for (Slot slot : slots) {
            total += slot.reservedBytes;
        }
        return total;
    }

    /** Adds a job and returns its slot. */
    public Slot add(ExecutingCraftingJob job, long reservedBytes, long totalAmount) {
        var slot = new Slot(nextId++, job, reservedBytes, totalAmount);
        slots.add(slot);
        return slot;
    }

    /**
     * Adds a job that came back from a save, keeping the id it was saved with.
     *
     * <p>{@code nextId} is advanced past it, which is the one thing a restored id can break: ids are what
     * the rotation, the status output and the suspend/cancel paths address jobs by, so a fresh job reusing
     * a restored id would silently take over the old one's turn.
     */
    public Slot addRestored(long id, ExecutingCraftingJob job, long reservedBytes, long totalAmount) {
        var slot = new Slot(id, job, reservedBytes, totalAmount);
        slots.add(slot);
        nextId = Math.max(nextId, id + 1);
        return slot;
    }
    public Slot byId(long id) {
        for (Slot slot : slots) {
            if (slot.id == id) {
                return slot;
            }
        }
        return null;
    }

    /**
     * Finds the job that is waiting for this key, used to route incoming items to the right job.
     *
     * <p><b>When more than one job waits for the same key, the one with the most work left gets the item.</b>
     * That sounds backwards - surely the nearly-finished job should be pushed over the line - but it is the
     * only rule that lets both finish.
     *
     * <p>The reason is that an item arriving from an assembler carries no record of which job's pattern
     * produced it. Only the jobs' {@code waitingFor} ledgers say who expects what, and a ledger is
     * replenished every time that job pushes a pattern, so "the first job that expects this key" pins every
     * single item onto the first job: its ledger keeps being refilled by its own pushes while the other
     * job's ledger grows without ever being drawn on. Measured with two identical 1000-stick orders on one
     * CPU: the first finished, the second sat at 1000/1000 for ever with the CPU still claiming to be busy,
     * and everything the second job crafted had been counted against the first.
     *
     * <p>Choosing the largest remaining makes the two advance together: the moment a job is credited it
     * falls behind the other, so the next item goes to the other one. The totals stay honest because the
     * number of arrivals equals the sum of all the jobs' expectations - with two 1000-item orders, 2000
     * items arrive and each job ends on exactly 1000 credits.
     *
     * <p>For the ordinary case - two jobs with different outputs - there is exactly one candidate and this
     * rule changes nothing.
     */
    public Slot waitingFor(appeng.api.stacks.AEKey key) {
        Slot best = null;
        long bestRemaining = Long.MIN_VALUE;
        for (Slot slot : slots) {
            if (view.waitingFor(slot.job, key) <= 0) {
                continue; // this job is not expecting this key at all
            }
            long remaining = view.remainingAmount(slot.job);
            if (best == null || remaining > bestRemaining) {
                best = slot;
                bestRemaining = remaining;
            }
        }
        return best;
    }

    /** Finds the job whose final output is this key, used to finish the right job. */
    public Slot producing(appeng.api.stacks.AEKey key) {
        for (Slot slot : slots) {
            var out = view.finalOutput(slot.job);
            if (out != null && key.matches(out)) {
                return slot;
            }
        }
        return null;
    }

    public void remove(long id) {
        slots.removeIf(slot -> slot.id == id);
    }

    /**
     * The job that status reporting and the suspend button should act on.
     *
     * <p> the screen keeps its vanilla meaning - "what is this CPU doing" - which for a CPU
     * running several orders is the job that owns the current time slice.
     *
     * <p><b>Why the last served job is the fallback, and not simply the first one.</b> Suspending a job
     * makes it BLOCKED, so the rotation stops choosing it and there is no owner any more; reporting slot 0
     * at that moment would silently move both the display and the suspend button onto a different order. That
     * is not a display detail - it is what made "suspend" a one-way action on a real machine: suspend the
     * order, and the button would come back describing a different one, so it could never be pressed for the
     * order that was actually paused. Reporting the job the CPU last worked on keeps the display and the
     * button on that job, which is exactly what makes suspend a toggle.
     */
    public Slot currentSlot() {
        var focused = focusedSlot();
        if (focused != null) {
            return focused;
        }
        long owner = policy.currentOwner();
        Slot slot = owner == SchedulingPolicy.Decision.NONE ? null : byId(owner);
        if (slot != null) {
            return slot;
        }
        slot = lastServed == SchedulingPolicy.Decision.NONE ? null : byId(lastServed);
        if (slot != null) {
            return slot;
        }
        return slots.isEmpty() ? null : slots.get(0);
    }

    /**
     * Puts a specific order in focus: the one the screen's details pane describes and controls.
     *
     * <p>Set when a per-order row is selected (see {@code SchedulerJobCpu}) and cleared when the screen
     * closes. While it is set, {@link #currentSlot()} answers with this order rather than with the CPU's
     * current slice owner - which is what makes the suspend button, the cancel button and the item table all
     * speak about the order the player picked.
     */
    public void focus(long slotId) {
        focusedSlot = byId(slotId) == null ? SchedulingPolicy.Decision.NONE : slotId;
    }

    /** Clears the focus, returning the screen to describing whatever the CPU is working on. */
    public void releaseFocus() {
        focusedSlot = SchedulingPolicy.Decision.NONE;
    }

    /** The focused order's id, or {@link SchedulingPolicy.Decision#NONE}. */
    public long focusedSlotId() {
        return focusedSlot;
    }

    /** The focused order, or null when there is none (or it has since been retired). */
    public Slot focusedSlot() {
        return focusedSlot == SchedulingPolicy.Decision.NONE ? null : byId(focusedSlot);
    }

    /**
     * The orders the CPU's item table should be built from.
     *
     * <p>With an order in focus: just that one, so the table belongs to the order the player selected.
     * Without a focus: all of them, because the table is a per-CPU view - the inventory it reports on is
     * shared, and reporting a single order made the screen highlight one recipe and show zero for the rest.
     */
    public List<Slot> tableSlots() {
        var focused = focusedSlot();
        return focused != null ? List.of(focused) : List.copyOf(slots);
    }

    public SchedulingPolicy policy() {
        return policy;
    }

    // ------------------------------------------------------------------ scheduling

    /** Reasons a job cannot be served, used both by the rotation and by the status output. */
    private JobSource.Status statusOf(Slot slot) {
        if (view.suspended(slot.job)) {
            return JobSource.Status.BLOCKED;
        }
        return JobSource.Status.READY;
    }

    /**
     * Adapter that exposes the slots to {@link RoundRobinPolicy}.
     *
     * <p>Rebuilt per tick on purpose: it is a thin view, so allocating it costs far less than keeping a
     * second copy of the job list in sync.
     */
    private final class SourceView implements JobSource {

        @Override
        public int size() {
            return slots.size();
        }

        @Override
        public long jobIdAt(int index) {
            return index >= 0 && index < slots.size() ? slots.get(index).id : -1L;
        }

        @Override
        public Status statusOf(int index) {
            if (index < 0 || index >= slots.size()) {
                return Status.BLOCKED;
            }
            return MultiJobState.this.statusOf(slots.get(index));
        }

        @Override
        public int indexOf(long jobId) {
            for (int i = 0; i < slots.size(); i++) {
                if (slots.get(i).id == jobId) {
                    return i;
                }
            }
            return -1;
        }
    }

    /**
     * Decides which job owns this tick, if any.
     *
     * @return the slot to serve, or null when no job can be served this tick
     */
    public Slot tick(long now) {
        var result = policy.tick(now, new SourceView());
        if (!result.decision().served()) {
            return null;
        }
        return byId(result.decision().jobId());
    }
    /**
     * Reports how much the served job pushed and, when it pushed nothing, why (see
     * {@link SchedulingPolicy.Refusal}), so the policy can decide whether the turn passes.
     */
    public void onServed(long jobId, int pushed, SchedulingPolicy.Refusal refusal) {
        lastServed = jobId;
        policy.onPushResult(jobId, pushed, refusal);
    }

    /**
     * Reports that the served job has no patterns left to push.
     *
     * <p>Called after {@link #onServed}: the failed attempt is still recorded (it happened), and this then
     * ends the slice at once, because a job that is only waiting for returns has nothing to lose the CPU
     * over.
     */
    public void onNoWork(long jobId) {
        policy.onNoWork(jobId);
    }

    /** Reports that serving a job threw; the job must not keep the CPU. */
    public void onServeError(long jobId) {
        policy.onPushError(jobId);
    }

    /** A one-line summary for the status command. */
    public String describe() {
        var sb = new StringBuilder("jobs=").append(slots.size())
                .append(" policy=").append(policy.name())
                .append(" holdCap=").append(policy.holdCapTicks())
                .append(" owner=").append(policy.currentOwner())
                .append(" reserved=").append(totalReservedBytes()).append("B")
                .append(" dumpGuard=").append(view.guardRefusals());
        for (Slot slot : slots) {
            var job = slot.job;
            var out = view.finalOutput(job);
            sb.append(" [id=").append(slot.id)
                    .append(" out=").append(out == null ? "?" : out.what().getDisplayName().getString())
                    .append(" left=").append(view.remainingAmount(job))
                    .append(" suspended=").append(view.suspended(job))
                    .append(']');
        }
        return sb.toString();
    }
}
