package com.schedulercore.mixin;

import java.util.ArrayList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.CraftingSubmitResult;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;

import com.schedulercore.SchedulerCore;
import com.schedulercore.scheduler.MultiJobState;
import com.schedulercore.scheduler.SchedulerScreenBridge;
import com.schedulercore.scheduler.SchedulingPolicy;

/**
 * Makes one crafting CPU hold several jobs, serving exactly one of them per tick at the vanilla rate.
 *
 * <h2>Scope of this mixin (deliberately small)</h2>
 *
 * <p>It takes over only the three places where "one job" is baked into AE2's crafting CPU:
 * <ul>
 *   <li>{@code trySubmitJob} - admission, so a CPU that is already busy can still accept another job as
 *       long as the storage ledger has room;</li>
 *   <li>{@code tickCraftingLogic} - the scheduling tick, which serves the current time-slice owner with
 *       the <b>whole</b> per-tick budget;</li>
 *   <li>{@code insert} - routing an incoming item to whichever job is actually waiting for it.</li>
 * </ul>
 *
 * <p>Everything else keeps running vanilla code. An earlier attempt at this idea went the other way and
 * replaced the whole class in one very large mixin with well over a hundred of its own members; that
 * approach is what this design exists to avoid, so keep this hook list short.
 *
 * <h2>The one invariant that matters here</h2>
 *
 * <p>Each tick, at most one job is served, and it is served through <b>vanilla's own execution path</b>
 * with the full budget {@code c + 1}. No budget is ever split between jobs. Sharing happens between ticks,
 * which is what makes a served job indistinguishable from a vanilla single-job CPU (requirements I1/I2).
 */
@Mixin(CraftingCpuLogic.class)
public abstract class MixinCraftingCpuLogic implements SchedulerScreenBridge {

    @Shadow
    CraftingCPUCluster cluster;

    /**
     * Vanilla's "dump the CPU inventory back into the network".
     *
     * <p>Shadowed so the scheduler can reuse the real implementation when a job ends or the CPU is taken
     * apart; re-implementing it would risk losing items.
     */
    @Shadow
    public abstract void storeItems();

    @Unique
    private MultiJobState schedulercore$jobs;

    /** Last job id whose turn was logged, so turn changes can be observed without per-tick spam. */
    @Unique
    private long schedulercore$lastOwnerLogged = -1L;

    @Unique
    private int schedulercore$schedulerTicks;

    /**
     * Non-zero while this CPU is dumping its own inventory into the network.
     *
     * <p>A counter rather than a boolean because the decrement lives in a {@code finally}: if
     * {@code storeItems()} throws, the CPU must not be left permanently unable to take items.
     */
    @Unique
    private int schedulercore$dumping;

    /** How many inserts the dump guard has refused; surfaced so the guard's effect is measurable. */
    @Unique
    private int schedulercore$dumpGuardRefusals;

    /**
     * The synthetic tracker this CPU's details pane and list row report, or null until one is needed.
     *
     * <p>Owned per CPU and refreshed on every read; see {@link #schedulercore$reportTotalsTracker} for why a
     * tracker has to be built at all rather than reused.
     */
    @Unique
    private appeng.crafting.execution.ElapsedTimeTracker schedulercore$totalsTracker;

    /**
     * Products that have been crafted but not yet handed to the network.
     *
     * <p>They cannot be handed over at the moment they arrive, because that moment is inside a network
     * insert and {@code NetworkStorage.insert} refuses to run while it is already running
     * ({@code mountsInUse}), so the nested delivery is a silent no-op. So the product is parked here and
     * delivered on the next tick, which is the first moment it can work.
     *
     * <p>Deliberately <b>not</b> parked in the CPU inventory. The inventory is the pool the job's own
     * {@code extractPatternInputs} draws from, and anything put there can be consumed as an ingredient by
     * any job sharing this CPU. A finished product must not become somebody's input.
     */
    @Unique
    private final appeng.api.stacks.KeyCounter schedulercore$pendingProducts = new appeng.api.stacks.KeyCounter();

    /**
     * Vanilla's single job field, reached reflectively.
     *
     * <p><b>Why this exists.</b> {@code executeCrafting}, {@code insert} and the rest of vanilla's
     * execution path read the private {@code job} field directly. With several jobs in play that field only
     * ever names one of them, so the scheduler has to point it at whichever job owns the current tick
     * <i>for the duration of the call</i>, then restore it. Mirroring those methods instead would mean
     * re-implementing AE2's execution loop; a scoped field swap reuses AE2's own code path verbatim.
     *
     * <p>The field is not final, so this is a plain reflective read/write - no access transformers or
     * unsafe needed.
     */
    @Unique
    private static java.lang.reflect.Field schedulercore$jobField;

    @Unique
    private static java.lang.reflect.Field schedulercore$jobField() {
        if (schedulercore$jobField == null) {
            try {
                var f = CraftingCpuLogic.class.getDeclaredField("job");
                f.setAccessible(true);
                schedulercore$jobField = f;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("schedulercore: CraftingCpuLogic.job not found", e);
            }
        }
        return schedulercore$jobField;
    }

    @Unique
    private ExecutingCraftingJob schedulercore$vanillaJob() {
        try {
            return (ExecutingCraftingJob) schedulercore$jobField().get(this);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("schedulercore: cannot read CraftingCpuLogic.job", e);
        }
    }

    @Unique
    private ExecutingCraftingJob schedulercore$swapVanillaJob(ExecutingCraftingJob replacement) {
        try {
            var previous = schedulercore$vanillaJob();
            schedulercore$jobField().set(this, replacement);
            return previous;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("schedulercore: cannot swap CraftingCpuLogic.job", e);
        }
    }

    /**
     * Builds one job for the scheduler.
     *
     * <p>The link must come from {@code CraftingCpuHelper.generateLinkData} because {@link
     * appeng.crafting.CraftingLink} has no public constructor - it can only be built from NBT link data.
     * The constructor call itself is delegated to a static factory that the mixin merges into the target
     * class; see {@link #schedulercore$newJob}.
     */
    @Unique
    private ExecutingCraftingJob schedulercore$createJob(ICraftingPlan plan, IActionSource src) {
        var craftId = java.util.UUID.randomUUID();
        var link = new appeng.crafting.CraftingLink(
                appeng.crafting.execution.CraftingCpuHelper.generateLinkData(craftId, true, false), cluster);
        // Construction goes through a cached reflective constructor: the constructor's listener parameter
        // is a package-private type, so the call cannot be written in source at all. See CraftingJobFactory.
        return com.schedulercore.scheduler.CraftingJobFactory.create(plan,
                this::schedulercore$postChange, link, (CraftingCpuLogic) (Object) this, src);
    }

    /** The scheduler's view of one job; all access goes through the accessor mixin. */
    @Unique
    private AccessorExecutingCraftingJob schedulercore$access(ExecutingCraftingJob job) {
        return (AccessorExecutingCraftingJob) (Object) job;
    }

    @Unique
    private MultiJobState schedulercore$state() {
        if (schedulercore$jobs == null) {
            // Registered against this CPU's cluster so the /schedulercore commands can find it.
            schedulercore$jobs = new MultiJobState(schedulercore$view, cluster);
        }
        return schedulercore$jobs;
    }

    /** The accessor-backed view of job internals that {@link MultiJobState} reads through. */
    @Unique
    private final MultiJobState.JobView schedulercore$view = new MultiJobState.JobView() {
        @Override
        public appeng.crafting.CraftingLink link(ExecutingCraftingJob job) {
            return schedulercore$access(job).schedulercore$link();
        }

        @Override
        public appeng.api.stacks.GenericStack finalOutput(ExecutingCraftingJob job) {
            return schedulercore$access(job).schedulercore$finalOutput();
        }

        @Override
        public long remainingAmount(ExecutingCraftingJob job) {
            return schedulercore$access(job).schedulercore$remainingAmount();
        }

        @Override
        public void setRemainingAmount(ExecutingCraftingJob job, long remaining) {
            schedulercore$access(job).schedulercore$setRemainingAmount(remaining);
        }

        @Override
        public boolean suspended(ExecutingCraftingJob job) {
            return com.schedulercore.scheduler.SuspendSupport.suspended(job);
        }

        @Override
        public void setSuspended(ExecutingCraftingJob job, boolean suspended) {
            com.schedulercore.scheduler.SuspendSupport.setSuspended(job, suspended);
        }

        @Override
        public long waitingFor(ExecutingCraftingJob job, AEKey key) {
            return schedulercore$access(job).schedulercore$waitingFor()
                    .extract(key, Long.MAX_VALUE, Actionable.SIMULATE);
        }

        @Override
        public long takeWaitingFor(ExecutingCraftingJob job, AEKey key, long amount, Actionable mode) {
            var ledger = schedulercore$access(job).schedulercore$waitingFor();
            long waiting = ledger.extract(key, amount, Actionable.SIMULATE);
            if (waiting <= 0 || mode != Actionable.MODULATE) {
                return waiting;
            }
            return ledger.extract(key, waiting, Actionable.MODULATE);
        }

        @Override
        public CompoundTag writeToNBT(ExecutingCraftingJob job) {
            return com.schedulercore.scheduler.NbtSupport.write(job,
                    com.schedulercore.scheduler.NbtSupport.contextFor(cluster));
        }

        @Override
        public appeng.crafting.execution.ElapsedTimeTracker timeTracker(ExecutingCraftingJob job) {
            return schedulercore$access(job).schedulercore$timeTracker();
        }

        @Override
        public void decrementTracker(ExecutingCraftingJob job, AEKey key, long amount) {
            try {
                ((AccessorElapsedTimeTracker) (Object) schedulercore$access(job).schedulercore$timeTracker())
                        .schedulercore$decrementItems(amount, key.getType());
            } catch (Throwable t) {
                // Accounting must never be able to break item routing: a wrong ETA is a cosmetic problem,
                // a dropped item is not.
                SchedulerCore.LOG.warn("[schedulercore] could not update the job's time tracker", t);
            }
        }

        @Override
        public int guardRefusals() {
            return schedulercore$dumpGuardRefusals;
        }
    };

    // ------------------------------------------------------------------ admission

    /**
     * Accepts additional jobs.
     *
     * <p>Vanilla refuses outright when a job is present ({@code CPU_BUSY}); here the decision is instead
     * made against the storage ledger: a job may be admitted while the bytes it needs, plus everything
     * already reserved by running jobs, still fit in the CPU's capacity.
     *
     * <p><b>The scheduler takes over from the very first job.</b> Intervening only once jobs are already
     * held would be self-defeating: the first job would always go down vanilla's single-job path, so it
     * would never be registered with the scheduler, so the scheduler would never hold anything and never
     * intervene - a closed loop that can never start.
     *
     * <p>The takeover criterion is therefore "does this CPU have a scheduler core installed", which is a
     * property of the machine the player built, not of the scheduler's current bookkeeping. A CPU without a
     * core keeps the untouched vanilla path.
     */
    @Inject(method = "trySubmitJob", at = @At("HEAD"), cancellable = true)
    private void schedulercore$tryAdmitAnotherJob(IGrid grid, ICraftingPlan plan, IActionSource src,
            ICraftingRequester requester, CallbackInfoReturnable<ICraftingSubmitResult> cir) {
        try {
            // The takeover criterion. Read from the block states rather than the scheduler's own state, so
            // the very first job is already handled here.
            boolean owns = schedulercore$hasSchedulerCore();
            var state = schedulercore$state();
            // Logged once per admission only - a per-tick line here flooded a real game log with 13874 lines.
            if (state.isEmpty()) {
                SchedulerCore.LOG.info("[schedulercore] admission: hasCore={} active={} bytes={}",
                        owns, cluster.isActive(), plan.bytes());
            }

            if (!owns) {
                SchedulerCore.LOG.info("[schedulercore] admission: no scheduler core on this CPU (hasCore=false)"
                        + " - deferred to vanilla");
                return; // ordinary crafting CPU: leave admission entirely to vanilla
            }

            if (!cluster.isActive()) {
                SchedulerCore.LOG.info("[schedulercore] admission refused: CPU_OFFLINE");
                cir.setReturnValue(CraftingSubmitResult.CPU_OFFLINE);
                return;
            }

            // Report what the vanilla single-job field is doing. A job left there - typically restored from
            // an older save by vanilla's readFromNBT, which this mod does not yet handle - makes the CPU
            // permanently busy, and the crafting screen's button flashes once and then greys out again.
            var vanilla = schedulercore$vanillaJob();
            if (vanilla != null) {
                var vanillaOut = schedulercore$view.finalOutput(vanilla);
                SchedulerCore.LOG.warn("[schedulercore] refusing admission: an untracked job is present "
                        + "(left={}, output={}). This is the stuck-job condition; it can be cleared with "
                        + "/schedulercore clearstuck.",
                        schedulercore$view.remainingAmount(vanilla),
                        vanillaOut == null ? "?" : vanillaOut.what().getDisplayName().getString());
                cir.setReturnValue(CraftingSubmitResult.CPU_BUSY);
                return;
            }

            long capacity = cluster.getAvailableStorage();
            long remaining = capacity - state.totalReservedBytes();
            if (remaining < plan.bytes()) {
                SchedulerCore.LOG.info("[schedulercore] admission refused: CPU_TOO_SMALL"
                        + " (capacity={} reserved={} needs={})",
                        capacity, state.totalReservedBytes(), plan.bytes());
                cir.setReturnValue(CraftingSubmitResult.CPU_TOO_SMALL);
                return;
            }

            // Ingredient extraction is vanilla's: it fills this logic's shared inventory.
            var inventory = this.schedulercore$inventory();
            var missing = appeng.crafting.execution.CraftingCpuHelper.tryExtractInitialItems(plan, grid, inventory, src);
            if (missing != null) {
                SchedulerCore.LOG.info("[schedulercore] admission refused: MISSING_INGREDIENT ({})",
                        missing.what().getDisplayName().getString());
                cir.setReturnValue(CraftingSubmitResult.missingIngredient(missing));
                return;
            }

            var job = schedulercore$createJob(plan, src);
            long totalAmount = plan.finalOutput() == null ? 0 : plan.finalOutput().amount();
            state.add(job, plan.bytes(), totalAmount);
            cluster.updateOutput(plan.finalOutput());
            cluster.markDirty();

            var craftingService = (CraftingService) grid.getCraftingService();
            craftingService.addLink(schedulercore$access(job).schedulercore$link());
            SchedulerCore.LOG.info("[schedulercore] admitted job #{} ({} bytes); CPU now holds {}",
                    schedulercore$access(job).schedulercore$link().getCraftingID(), plan.bytes(),
                    state.describe());

            cir.setReturnValue(CraftingSubmitResult.successful(null));
        } catch (Throwable t) {
            // Never let the scheduler take the server down: fall back to vanilla admission.
            SchedulerCore.LOG.error("[schedulercore] admission failed, deferring to vanilla", t);
        }
    }

    // ------------------------------------------------------------------ scheduling tick

    /**
     * Serves exactly one job this tick, with the full vanilla budget.
     *
     * <p>Cancels vanilla's single-job tick only when the scheduler actually holds jobs, so a CPU with no
     * scheduler involvement is untouched.
     */
    @Inject(method = "tickCraftingLogic", at = @At("HEAD"), cancellable = true)
    private void schedulercore$tickTimeSlices(IEnergyService energyService, CraftingService craftingService,
            CallbackInfo ci) {
        try {
            // Deliver crafted products first, and regardless of whether any job is left.
            //
            // This is the only place delivery can happen: the tick is not inside a network insert, so
            // NetworkStorage.insert is not already running and will actually look at the storages.
            schedulercore$drainPendingProducts();

            // <b>Order matters for cost.</b> The "does this CPU have a core" question needs a walk over the
            // multiblock's block entities and a block-state lookup each (see ClusterUnits). Asking it before
            // the job list would mean every crafting CPU in the world pays for it on every tick, including
            // the ones the player never gave a scheduler job to. The job list is already in memory, so it is
            // consulted first: a CPU with nothing queued returns without touching a single block.
            var state = schedulercore$state();
            if (state.isEmpty()) {
                // A core is installed but nothing is queued: still vanilla's business (it drains leftovers).
                return;
            }

            // Same criterion as admission: a CPU with a scheduler core is handled here from its very first
            // job, otherwise the scheduler could never acquire one in the first place. Cached, because this
            // runs while jobs are queued: the answer cannot change faster than a player can break a block,
            // and cancels the orders immediately when it does.
            if (!schedulercore$hasSchedulerCoreCached()) {
                return; // ordinary crafting CPU: leave the tick to vanilla
            }
            if (!cluster.isActive()) {
                ci.cancel();
                return;
            }
            schedulercore$schedulerTicks++;
            schedulercore$retireFinishedJobs(state);
            if (state.isEmpty()) {
                // Vanilla dumps the inventory itself on the next tick (its job field is null and stays null
                // for the scheduler), so nothing to do here but keep the monitor honest.
                ci.cancel();
                return;
            }

            var chosen = state.tick(schedulercore$schedulerTicks);
            if (chosen == null) {
                ci.cancel();
                return;
            }

            // Vanilla's tickCraftingLogic does this before it pushes anything:
            //     if (job.suspended) return;
            // The rotation only skips suspended jobs when it *chooses* one, so a job suspended while it owns
            // the CPU used to get pushed anyway. That was survivable while a failed attempt ended the slice
            // immediately; now that a failed attempt is retried, a suspended owner that cannot push
            // could sit on the CPU for the whole hold window. Yielding here restores vanilla's rule exactly.
            if (schedulercore$view.suspended(chosen.job())) {
                state.onNoWork(chosen.id());
                ci.cancel();
                return;
            }

            // Log only when the turn actually changes, so a busy CPU cannot flood the log (an earlier
            // per-tick line produced 13874 lines in one session). Needed to tell "the rotation is not
            // switching" apart from "it switches but the other job makes no progress".
            if (chosen.id() != schedulercore$lastOwnerLogged) {
                schedulercore$lastOwnerLogged = chosen.id();
                SchedulerCore.LOG.info("[schedulercore] turn -> job #{} ({})",
                        chosen.id(), state.describe());
            }

            // The budget must be vanilla's, including its rolling-window throttle. See
            // schedulercore$tickBudget for why it is not simply c + 1 per tick.
            int budget = schedulercore$tickBudget();

            int pushed = 0;
            if (budget > 0) {
                try {
                    pushed = this.schedulercore$pushFor(chosen.job(), budget, craftingService, energyService);
                } catch (Throwable t) {
                    state.onServeError(chosen.id());
                    SchedulerCore.LOG.error("[schedulercore] serving job #{} threw", chosen.id(), t);
                    ci.cancel();
                    return;
                }
            }
            schedulercore$shiftUsedOps(pushed);
            // A failed attempt is not automatically worth waiting for: the host has to say *why* it failed,
            // because the answer decides whether the owner keeps the CPU. A fixed tick window cannot be
            // right for both a slow machine and a dead one, and the two are only
            // distinguishable by looking at the providers.
            var refusal = pushed > 0 ? SchedulingPolicy.Refusal.NONE
                    : schedulercore$refusalOf(chosen.job(), craftingService, budget);
            state.onServed(chosen.id(), pushed, refusal);
            if (pushed <= 0 && schedulercore$tasksLeft(chosen.job()) <= 0) {
                // The job has no patterns left to push: every one it had is already in a machine, so it is
                // only waiting for the returns. Holding the CPU cannot produce anything, so it yields to
                // whatever else is queued. This is the one "pushed 0" case the host can prove is *not* the
                // machine refusing work - the other case, where the provider is busy, must be retried (see
                // SchedulingPolicy.onPushResult) or the same job wins every machine window for ever.
                state.onNoWork(chosen.id());
            }

            schedulercore$traceIfEnabled(chosen.id(), budget, pushed, refusal);

            var finalOutput = schedulercore$view.finalOutput(chosen.job());
            if (finalOutput != null) {
                cluster.updateOutput(new appeng.api.stacks.GenericStack(
                        finalOutput.what(), schedulercore$view.remainingAmount(chosen.job())));
            }
            cluster.markDirty();
            ci.cancel();
        } catch (Throwable t) {
            SchedulerCore.LOG.error("[schedulercore] scheduling tick failed; leaving the CPU to vanilla", t);
        }
    }

    /**
     * The per-tick budget, computed exactly as vanilla computes it.
     *
     * <p>Vanilla's formula is {@code c + 1 - (usedOps[0] + usedOps[1] + usedOps[2])}, where {@code usedOps}
     * keeps the operations spent in the last three ticks. That window is what limits a CPU to about
     * {@code c + 1} operations per <b>three</b> ticks; it is not {@code c + 1} per tick. Handing out
     * {@code c + 1} every tick triples throughput, makes the crafting-status parallelism jump around, and
     * violates the guarantee that a scheduler is never faster than vanilla.
     */
    @Unique
    private int schedulercore$tickBudget() {
        int c = cluster.getCoProcessors();
        long usedRecently = schedulercore$usedOps(0) + schedulercore$usedOps(1) + schedulercore$usedOps(2);
        return (int) Math.max(0, c + 1 - usedRecently);
    }

    /**
     * Retires every job that has ended - cancelled from the outside, or fully delivered - in one place,
     * after which the caller dumps the inventory once if nothing is left.
     *
     * <p>Each slot leaves the state <b>before</b> anything is dumped, so a dump can never be credited back
     * to the job that is ending: routing looks at the live slots, and the ending job is no longer one of
     * them. That removal-first order is why these two passes cannot be merged into one walk.
     *
     * <p>The two passes are separate for the same reason: "cancelled" and "delivered everything" are
     * different questions, and the second is only asked of the jobs the first left behind.
     *
     * <p><b>No inventory dump happens here while any job is left.</b> This is the rule vanilla lives by: its
     * {@code storeItems()} asserts that no job is present, and it is only ever reached with no job. Dumping
     * mid-flight was measured to break the surviving job outright - the CPU inventory is the pool that job's
     * own {@code extractPatternInputs} draws from, so emptying it into the network leaves the job with
     * nothing to push and it stalls with its work unfinished. What is left in the inventory belongs to the
     * jobs that are still running, so the leftovers of a retiring job are simply left where they are: the
     * last job to end triggers vanilla's own dump, which is when the CPU's inventory is genuinely nobody's.
     */
    @Unique
    private void schedulercore$retireFinishedJobs(MultiJobState state) {
        var retired = new ArrayList<MultiJobState.Slot>();
        for (var slot : new ArrayList<>(state.slots())) {
            if (schedulercore$view.link(slot.job()).isCanceled()) {
                // The player pressed cancel, or the link was cancelled from the outside. Same bookkeeping
                // as a completed job; only the link is closed differently.
                schedulercore$retire(slot, retired, false, "cancelled");
            }
        }
        // Jobs that have delivered everything they were asked for. Without this a finished job stays in the
        // list for ever, so the CPU keeps reporting itself busy and the admission ledger stays consumed -
        // the reason a long-running test used to wedge the machine.
        for (var slot : new ArrayList<>(state.slots())) {
            if (schedulercore$view.remainingAmount(slot.job()) <= 0) {
                schedulercore$retire(slot, retired, true, "finished");
            }
        }
        if (retired.isEmpty()) {
            return;
        }
        schedulercore$refreshMonitor();
        // An order that has just left the state is in no live list any more, so its rows would keep their
        // last amounts (and their highlight) on screen unless they are reported explicitly.
        schedulercore$refreshStatusRowsWith(retired);
        SchedulerCore.LOG.info("[schedulercore] {} job(s) retired, {} left on this CPU",
                retired.size(), state.size());
    }

    /** Takes one ended job out of the state, closes its link and records it for the status refresh. */
    @Unique
    private void schedulercore$retire(MultiJobState.Slot slot, java.util.List<MultiJobState.Slot> retired,
            boolean success, String why) {
        schedulercore$closeLink(slot.job(), success);
        schedulercore$state().remove(slot.id());
        retired.add(slot);
        SchedulerCore.LOG.info("[schedulercore] job #{} {}", slot.id(), why);
    }

    /**
     * Returns the part of the shared inventory that <b>no remaining order could still need</b>.
     *
     * <h2>The gap this closes</h2>
     *
     * <p>An order's ingredients are extracted out of the network into the CPU's <b>shared</b> inventory when
     * it is submitted ({@code CraftingCpuHelper.tryExtractInitialItems}), not drawn as it goes. Vanilla hands
     * that pool back at exactly one moment - when the CPU has no job at all, because {@code storeItems()}
     * asserts there is no job and only ever runs for an idle CPU. With several orders on one CPU that moment
     * stops arriving when it should: cancelling one order leaves its already-extracted ingredients in the
     * pool, belonging to nobody, until every other order on the machine has finished.
     *
     * <h2>Why the whole pool cannot simply be emptied</h2>
     *
     * <p>{@code extractPatternInputs} draws from this pool and nothing else does, and a pattern needs its
     * inputs <b>in full</b> before it can be pushed. Emptying the pool while another order still has patterns
     * to push is the measured failure recorded at {@link #schedulercore$insertFor}. So this releases only
     * keys that no remaining order can reach: a key is released only when it is absent from every live
     * order's expectation ledger and from every input of every pattern that order has left to push.
     *
     * <p>What that deliberately does not attempt: a key wanted by both a cancelled order and a live one.
     * Those are left alone, because the pool cannot say how much of it belonged to which order - so per-order
     * ownership is traded for never taking an ingredient a live order could still need. The failure direction
     * is therefore always "kept too much", never "took something that was still wanted".
     */
    @Unique
    private void schedulercore$releaseKeysNotRequired(MultiJobState state) {
        try {
            var inventory = schedulercore$inventory();
            if (inventory.list.isEmpty()) {
                return; // nothing pooled: the common case
            }
            var required = schedulercore$liveRequiredKeys(state);
            var grid = cluster.getGrid();
            if (grid == null) {
                return; // no network to hand anything back to; nothing is lost by waiting
            }
            var target = grid.getStorageService().getInventory();
            var src = cluster.getSrc();

            var alone = new appeng.api.stacks.KeyCounter();
            for (var entry : inventory.list) {
                if (!required.contains(entry.getKey())) {
                    long free = inventory.extract(entry.getKey(), Long.MAX_VALUE, Actionable.SIMULATE);
                    if (free > 0) {
                        alone.add(entry.getKey(), free);
                    }
                }
            }
            if (alone.isEmpty()) {
                return; // every pooled key is still wanted by a live order
            }

            long released = 0;
            schedulercore$dumping++;
            try {
                for (var entry : alone) {
                    long took = inventory.extract(entry.getKey(), entry.getLongValue(), Actionable.MODULATE);
                    if (took <= 0) {
                        continue;
                    }
                    long inserted = target.insert(entry.getKey(), took, Actionable.MODULATE, src);
                    long refused = took - inserted;
                    if (refused > 0) {
                        // The network would not take it all: put the remainder back rather than void it.
                        inventory.insert(entry.getKey(), refused, Actionable.MODULATE);
                    }
                    if (inserted > 0) {
                        released += inserted;
                        schedulercore$postChange(entry.getKey());
                    }
                }
            } finally {
                schedulercore$dumping--;
            }
            if (released > 0) {
                SchedulerCore.LOG.info("[schedulercore] released {} item(s) no remaining order can use, in {}"
                        + " key(s); {} key(s) still held for the orders that remain",
                        released, alone.size(), inventory.list.size());
            }
        } catch (Throwable t) {
            // Housekeeping must never take the server down, and getting this wrong must never lose items:
            // whatever was not released simply stays in the pool.
            SchedulerCore.LOG.error("[schedulercore] could not release a cancelled order's ingredients", t);
        }
    }

    /**
     * Every key the state's remaining orders could still reach in the shared inventory.
     *
     * <p>Three sources, all of them necessary: what each order expects back (its {@code waitingFor} ledger,
     * which an arriving item is matched against), what each order's own final output is, and the inputs of
     * every pattern it has left to push. The ledger also carries any crafted-but-undelivered product the
     * order is still owed, which is why it is included rather than left as an optimisation.
     *
     * <p>Reads no state and changes nothing: it is a conservative over-approximation, so a key it lists is
     * never required to actually be needed - over-listing only makes the release smaller.
     */
    @Unique
    private java.util.Set<AEKey> schedulercore$liveRequiredKeys(MultiJobState state) {
        var keys = new java.util.HashSet<AEKey>();
        for (var slot : state.slots()) {
            var job = slot.job();
            var output = schedulercore$view.finalOutput(job);
            if (output != null) {
                keys.add(output.what());
            }
            for (var entry : schedulercore$access(job).schedulercore$waitingFor().list) {
                keys.add(entry.getKey());
            }
            schedulercore$collectTaskInputKeys(job, keys);
        }
        return keys;
    }

    /** Adds the inputs of every pattern this order has left to push, mirroring vanilla's own template walk. */
    @Unique
    private void schedulercore$collectTaskInputKeys(ExecutingCraftingJob job, java.util.Set<AEKey> into) {
        try {
            for (var entry : schedulercore$tasksOf(job).entrySet()) {
                if (schedulercore$taskValue(entry.getValue()) <= 0) {
                    continue; // already fully pushed; vanilla drops these from its task table
                }
                var details = (IPatternDetails) entry.getKey();
                for (var input : details.getInputs()) {
                    for (var template : appeng.crafting.execution.CraftingCpuHelper.getValidItemTemplates(
                            schedulercore$inventory(), input, cluster.getLevel())) {
                        into.add(template.key());
                    }
                }
            }
        } catch (Throwable t) {
            // A requirement that cannot be computed must never be treated as "not required": hand the failure
            // up so schedulercore$releaseKeysNotRequired keeps the whole pool instead of guessing.
            throw new IllegalStateException("could not enumerate a live order's required keys", t);
        }
    }

    /** Emits the one-line-per-tick trace when it is switched on. See {@link com.schedulercore.scheduler.Trace}. */
    @Unique
    private void schedulercore$traceIfEnabled(long servedId, int budget, int pushed,
            SchedulingPolicy.Refusal refusal) {
        if (!com.schedulercore.scheduler.Trace.enabled()) {
            return;
        }
        // The refusal is part of the trace because it is the one decision this diagnosis hangs on:
        // TRANSIENT means "the machine is still working for this order, so it keeps the CPU",
        // FUTILE means "nothing can take it, so the CPU goes to the next order".
        SchedulerCore.LOG.info("[schedulercore] trace t={} served={} budget={} pushed={} refusal={} c={} {}",
                schedulercore$schedulerTicks, servedId, budget, pushed, refusal,
                cluster.getCoProcessors(), schedulercore$traceJobs());
    }

    // ------------------------------------------------------------------ item routing

    /**
     * Routes an incoming item to the job that is waiting for it.
     *
     * <p>With several jobs sharing one inventory and one "waiting for" ledger, vanilla's single-job lookup
     * would credit the wrong job. This finds the right one and leaves the item accounting to vanilla's own
     * insert path by temporarily making that job the logic's current one.
     */
    @Inject(method = "insert", at = @At("HEAD"), cancellable = true)
    private void schedulercore$routeToWaitingJob(AEKey what, long amount, Actionable mode,
            CallbackInfoReturnable<Long> cir) {
        try {
            // ---------------------------------------------------------------- the dump guard
            //
            // This is the single most important line in the class, and it exists because vanilla has the
            // same guard for the same reason.
            //
            // storeItems() pushes the CPU's inventory into the network with
            //     storage.insert(key, amount, MODULATE, cluster.getSrc())
            // and the crafting service mounts itself at Integer.MAX_VALUE priority as an MEStorage whose
            // insert() forwards straight back into CraftingCpuLogic.insert() for every CPU
            // (CraftingServiceStorage.insert -> CraftingService.insertIntoCpus). So a dump re-enters this
            // very hook.
            //
            // Vanilla survives that only because finishJob() sets `job = null` *before* calling storeItems()
            // and insert() starts with `if (what == null || job == null) return 0;` - the precondition in
            // storeItems() (`job == null`) is not politeness, it is what stops the CPU from eating its own
            // dump. The scheduler keeps that field empty between ticks, so it cannot use the field as the
            // guard and has to say it explicitly. Without this guard a single delivered item is re-ingested
            // by its own dump, which consumes the job's waitingFor ledger again and again: remainingAmount
            // falls without anything being crafted, the same stack is inserted into the CPU inventory once
            // per recursion level, and the nested storeItems() walks an inventory that its own callee is
            // mutating. Measured on a real machine as "one order ends and takes the other with it, with the
            // product still sitting in the CPU".
            if (what == null) {
                return; // vanilla answers 0 for a null key; nothing for the scheduler to route
            }
            if (schedulercore$dumping > 0) {
                schedulercore$dumpGuardRefusals++;
                if (schedulercore$dumpGuardRefusals <= 5) {
                    SchedulerCore.LOG.info(
                            "[schedulercore] dump guard refused an insert of {} x{} (total refusals {})",
                            what.getDisplayName().getString(), amount, schedulercore$dumpGuardRefusals);
                }
                cir.setReturnValue(0L);
                return;
            }
            var state = schedulercore$state();
            if (state.isEmpty()) {
                return;
            }
            var slot = state.waitingFor(what);
            if (slot == null) {
                var producer = state.producing(what);
                if (producer == null) {
                    return;
                }
                slot = producer;
            }
            long moved = this.schedulercore$insertFor(slot.job(), what, amount, mode);
            cir.setReturnValue(moved);
        } catch (Throwable t) {
            SchedulerCore.LOG.error("[schedulercore] item routing failed; deferring to vanilla", t);
        }
    }

    // ------------------------------------------------------------------ persistence

    /**
     * Persists every scheduler job, not just the one vanilla knows about.
     *
     * <p>Stored under a separate key so it can never collide with vanilla's own {@code job} tag. The matching
     * read path is {@link #schedulercore$loadJobs}, which runs at the end of {@code readFromNBT}.
     *
     * <p><b>Why this is a bridge method rather than the injector itself.</b> A Mixin handler has to declare its
     * target's exact signature - a trailing argument cannot be dropped - and the target's signature is exactly
     * what differs between the two supported generations ({@code writeToNBT(CompoundTag)} before 1.20.5,
     * {@code writeToNBT(CompoundTag, HolderLookup.Provider)} after). So each target's mixin declares the hook
     * and forwards here, where the work - which is the same everywhere - actually lives.
     */
    @Override
    public void schedulercore$saveJobs(CompoundTag output) {
        try {
            var state = schedulercore$state();
            if (state.isEmpty()) {
                return;
            }
            final Object nbtContext = com.schedulercore.scheduler.NbtSupport.contextFor(cluster);
            var list = new ListTag();
            // Every order, never the page that happens to be focused: a save that followed the screen would
            // write one job and silently drop the rest of the queue.
            for (var slot : state.slots()) {
                var entry = new CompoundTag();
                entry.putLong("id", slot.id());
                entry.putLong("reserved", slot.reservedBytes());
                // The accessor view wraps the package-private serialiser.
                entry.put("job", schedulercore$view.writeToNBT(slot.job()));
                list.add(entry);
            }
            output.put("schedulercore_jobs", list);
        } catch (Throwable t) {
            SchedulerCore.LOG.error("[schedulercore] could not persist jobs", t);
        }
    }

    /**
     * Rebuilds every scheduler job when a save is loaded, after vanilla has restored the inventory.
     *
     * <p>Runs at {@code TAIL} so vanilla's own {@code readFromNBT} has already read the shared
     * {@code inventory} tag - the jobs' ingredients live in that one shared inventory, so the order matters.
     * Nothing here writes vanilla's {@code job} key: the scheduler deliberately leaves that field empty, and
     * a save written by this version never contains it, so vanilla's read is a no-op that ends with
     * {@code cluster.updateOutput(null)}. The monitor is refreshed below.
     *
     * <p><b>Each job is rebuilt by AE2's own NBT constructor</b> (see {@code CraftingJobFactory.restore}) -
     * that constructor is the only reader guaranteed to stay in step with the writer, and it also registers
     * the job's link with the crafting service, which a hand-rolled reader would forget. A job whose final
     * output cannot be read is skipped rather than restored half-alive: a job with no output could never be
     * finished or displayed, and it would hold its reservation for ever.
     *
     * <p>The job's ordered amount is recovered from the restored job itself
     * ({@code finalOutput.amount()}), so the saved format does not need another field for it.
     *
     * <p>Like {@link #schedulercore$saveJobs}, this is a bridge method rather than the injector: each target's
     * mixin declares the hook with that generation's exact signature and forwards here.
     */
    @Override
    public void schedulercore$loadJobs(CompoundTag data) {
        try {
            if (!data.contains("schedulercore_jobs", Tag.TAG_LIST)) {
                return; // ordinary CPU, or a save written before the scheduler ever ran on it
            }
            var list = data.getList("schedulercore_jobs", Tag.TAG_COMPOUND);
            if (list.isEmpty()) {
                return;
            }
            var state = schedulercore$state();
            int restored = 0;
            for (int i = 0; i < list.size(); i++) {
                var entry = list.getCompound(i);
                if (!entry.contains("job", Tag.TAG_COMPOUND)) {
                    continue;
                }
                var job = com.schedulercore.scheduler.CraftingJobFactory.restore(
                        entry.getCompound("job"),
                        com.schedulercore.scheduler.NbtSupport.contextFor(cluster),
                        this::schedulercore$postChange,
                        (CraftingCpuLogic) (Object) this);
                var out = schedulercore$view.finalOutput(job);
                if (out == null) {
                    SchedulerCore.LOG.warn("[schedulercore] dropping a saved job with no final output");
                    continue;
                }
                // The restore call above has already registered the job's link with the crafting service -
                // that is part of why AE2's own NBT constructor is used rather than a hand-rolled reader.
                state.addRestored(entry.getLong("id"), job, entry.getLong("reserved"), out.amount());
                restored++;
            }
            if (restored > 0) {
                schedulercore$refreshMonitor();
                SchedulerCore.LOG.info("[schedulercore] restored {} scheduled job(s) from the save: {}",
                        restored, state.describe());
            }
        } catch (Throwable t) {
            // Never let a bad save take the server down, and never leave half-restored jobs holding
            // reservations: dropping them is safe (their materials are in the CPU inventory, which vanilla
            // restores and the CPU drains when it goes idle).
            SchedulerCore.LOG.error("[schedulercore] could not restore scheduled jobs", t);
        }
    }

    // ------------------------------------------------------------------ helpers

    @Unique
    private appeng.crafting.inv.ListCraftingInventory schedulercore$inventory() {
        return ((CraftingCpuLogic) (Object) this).getInventory();
    }

    // ------------------------------------------------------------------ trace

    /**
     * One line describing what every job on this CPU currently holds.
     *
     * <p>Written because "the served job pushed nothing" and "the served job pushed its share" are
     * indistinguishable in the turn log, and the three explanations for the first case - the job cannot push
     * (no patterns left), it has no ingredients, or it has nothing to expect back - need different fixes.
     * So: how much it still owes ({@code left}), what it expects to receive ({@code expect},
     * total/kinds), how many patterns it still has to push ({@code tasks}) and what the shared ingredient
     * pool looks like ({@code inv}).
     */
    @Unique
    private String schedulercore$traceJobs() {
        var sb = new StringBuilder("jobs:");
        for (var slot : schedulercore$state().slots()) {
            long expect = 0;
            int kinds = 0;
            for (var entry : schedulercore$access(slot.job()).schedulercore$waitingFor().list) {
                expect += entry.getLongValue();
                kinds++;
            }
            long stored = 0;
            int storedKinds = 0;
            for (var entry : schedulercore$inventory().list) {
                stored += entry.getLongValue();
                storedKinds++;
            }
            sb.append(" [id=").append(slot.id())
                    .append(" left=").append(schedulercore$view.remainingAmount(slot.job()))
                    .append(" expect=").append(expect).append('/').append(kinds)
                    .append(" tasks=").append(schedulercore$tasksLeft(slot.job()))
                    .append(" inv=").append(stored).append('/').append(storedKinds)
                    .append(']');
        }
        return sb.toString();
    }

    @Unique
    private static java.lang.reflect.Field schedulercore$tasksField;

    @Unique
    private static java.lang.reflect.Field schedulercore$taskValueField;

    /**
     * Why the served job pushed nothing, in the only terms the policy needs: can waiting fix it?
     *
     * <p>This mirrors the two probes vanilla's own {@code executeCrafting} makes before it can push -
     * {@code craftingService.getProviders(details)} and {@code provider.isBusy()} - and it is only ever
     * consulted on a tick that pushed nothing, so the extra walk over the provider table costs nothing on
     * the normal path.
     *
     * <ul>
     *   <li><b>Every</b> provider that could take one of this job's remaining patterns is busy → the
     *       machine is working for this job. Report {@link SchedulingPolicy.Refusal#TRANSIENT}: the owner
     *       waits, exactly as a vanilla CPU retries every tick. This is what lets an order on a slow
     *       machine keep its turn until that machine frees up.</li>
     *   <li><b>No</b> provider is registered for any remaining pattern, <b>or</b> a provider is free and
     *       still refused → waiting on the CPU cannot help. Report
     *       {@link SchedulingPolicy.Refusal#FUTILE}: the slice ends at once. A provider only refuses while
     *       it has a push in flight, so a <i>free</i> one that still refuses means the refusal came from
     *       this job's own inputs, not from the machine - and a job whose machine can never take work must
     *       not cost the other orders their throughput.</li>
     *   <li>No budget this tick → the machine was never asked, so nothing can be concluded. Report
     *       {@link SchedulingPolicy.Refusal#TRANSIENT}; the budget window rolls on every tick, so the
     *       owner will be able to push within a few ticks. Reporting FUTILE here would hand the CPU away
     *       for a throttle that affects every job equally.</li>
     * </ul>
     *
     * <p><b>Insufficient power is not probed separately on purpose.</b> It would need the pattern's inputs
     * extracted first ({@code CraftingCpuHelper.calculatePatternPower}), and it is not a reason to yield
     * anyway: a power shortage stops every job on the CPU at the same time, so holding through it costs the
     * other orders nothing, and it clears by itself.
     */
    @Unique
    private SchedulingPolicy.Refusal schedulercore$refusalOf(ExecutingCraftingJob job,
            CraftingService craftingService, int budget) {
        if (budget <= 0) {
            return SchedulingPolicy.Refusal.TRANSIENT;
        }
        try {
            boolean anyProvider = false;
            for (var entry : schedulercore$tasksOf(job).entrySet()) {
                if (schedulercore$taskValue(entry.getValue()) <= 0) {
                    // Already fully pushed; vanilla drops these from its task table.
                    continue;
                }
                var details = (IPatternDetails) entry.getKey();
                for (var provider : craftingService.getProviders(details)) {
                    anyProvider = true;
                    if (!provider.isBusy()) {
                        return SchedulingPolicy.Refusal.FUTILE;
                    }
                }
            }
            return anyProvider ? SchedulingPolicy.Refusal.TRANSIENT : SchedulingPolicy.Refusal.FUTILE;
        } catch (Throwable t) {
            // Never let a probe failure freeze the CPU: if we cannot tell, the other orders get the turn.
            return SchedulingPolicy.Refusal.FUTILE;
        }
    }

    /** Total patterns this job still has to push, or -1 when it cannot be read. */
    @Unique
    private long schedulercore$tasksLeft(ExecutingCraftingJob job) {
        try {
            long total = 0;
            for (var value : schedulercore$tasksOf(job).values()) {
                total += schedulercore$taskValue(value);
            }
            return total;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * How much of {@code template} this job still expects to receive, mirroring vanilla's
     * {@code getPendingOutputs}.
     *
     * <p>Vanilla computes this from its single job's task table; the scheduler has to compute it from the
     * displayed job's table. This is the number the crafting-status screen shows as the item's "pending"
     * column and uses to highlight the rows of an order that is actually in progress - which is why a
     * scheduler CPU used to show its ingredients and nothing else.
     */
    @Unique
    private long schedulercore$pendingOutputs(ExecutingCraftingJob job, AEKey template) {
        try {
            long count = 0;
            for (var entry : schedulercore$tasksOf(job).entrySet()) {
                var details = (appeng.api.crafting.IPatternDetails) entry.getKey();
                long times = schedulercore$taskValue(entry.getValue());
                for (var output : details.getOutputs()) {
                    if (template.matches(output)) {
                        count += output.amount() * times;
                    }
                }
            }
            return count;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** This job's task table ({@code IPatternDetails -> TaskProgress}), reached reflectively once. */
    @Unique
    private java.util.Map<?, ?> schedulercore$tasksOf(ExecutingCraftingJob job) throws ReflectiveOperationException {
        if (schedulercore$tasksField == null) {
            var f = ExecutingCraftingJob.class.getDeclaredField("tasks");
            f.setAccessible(true);
            schedulercore$tasksField = f;
        }
        var tasks = (java.util.Map<?, ?>) schedulercore$tasksField.get(job);
        return tasks == null ? java.util.Map.of() : tasks;
    }

    /** One {@code TaskProgress.value}; the class itself is package-private. */
    @Unique
    private long schedulercore$taskValue(Object taskProgress) throws ReflectiveOperationException {
        if (schedulercore$taskValueField == null) {
            var f = taskProgress.getClass().getDeclaredField("value");
            f.setAccessible(true);
            schedulercore$taskValueField = f;
        }
        return schedulercore$taskValueField.getLong(taskProgress);
    }

    /**
     * Closes one job's crafting link.
     *
     * <p>This mirrors the first half of vanilla's {@code finishJob(boolean)}: {@code markDone()} for a job
     * that delivered its output, {@code cancel()} for one that was cancelled, so the crafting service stops
     * tracking it and the requester (if any) is notified.
     *
     * <p>Deliberately <b>does not</b> touch the CPU inventory or the job list. Those are separate steps
     * because they must happen in a specific order: the slot leaves the state first, then the inventory is
     * dumped once for however many jobs ended in this tick. Putting them back together is what let a dump be
     * credited to the job that was already ending.
     */
    @Unique
    private void schedulercore$closeLink(ExecutingCraftingJob job, boolean success) {
        try {
            var link = schedulercore$view.link(job);
            if (success) {
                link.markDone();
            } else {
                link.cancel();
            }
        } catch (Throwable t) {
            SchedulerCore.LOG.warn("[schedulercore] could not close a crafting link", t);
        }
    }

    /**
     * Hands everything the CPU is holding back to the network, through vanilla's own {@code storeItems()}.
     *
     * <p>Reusing vanilla's implementation matters - it is the code path that decides what happens when the
     * network cannot take the items (the remainder stays in the inventory instead of being voided). Reusing
     * it also means the scheduler inherits vanilla's guard against re-insertion, which is why this method
     * brackets the call with {@link #schedulercore$dumping}: the crafting service forwards network inserts
     * straight back into {@link #schedulercore$routeToWaitingJob}.
     *
     * <p>{@code storeItems()} asserts that the single-job field is empty, so whatever vanilla had parked
     * there is handed back afterwards. The scheduler's own jobs are never in that field between ticks.
     */
    @Unique
    private void schedulercore$dumpInventoryToNetwork() {
        schedulercore$dumping++;
        try {
            var parked = schedulercore$swapVanillaJob(null);
            try {
                storeItems();
            } finally {
                schedulercore$swapVanillaJob(parked);
            }
        } catch (Throwable t) {
            // Never let bookkeeping take the server down; the items stay in the CPU inventory and the
            // vanilla dismantle path will still drop them rather than delete them.
            SchedulerCore.LOG.error("[schedulercore] could not return items to the network", t);
        } finally {
            schedulercore$dumping--;
        }
    }

    /**
     * Hands every crafted-but-undelivered product to the network.
     *
     * <p>Called from the tick and from the cancel path, i.e. from outside any network insert, which is what
     * makes it work at all: a delivery attempted from inside the insert path is swallowed by
     * {@code NetworkStorage.insert}'s own re-entrancy flag before any storage is consulted (measured: zero
     * guard refusals and nothing delivered, versus the same batch going out on the next tick).
     *
     * <p>The delivery itself is wrapped in the dump guard. A network insert is offered to the crafting CPUs
     * <b>first</b> - {@code CraftingServiceStorage} mounts itself at {@code Integer.MAX_VALUE} - so without
     * the guard this CPU would re-accept its own product against a job's {@code waitingFor} ledger that has
     * already been credited for it, i.e. it would be counted twice and never actually delivered.
     *
     * <p>Whatever the network refuses stays pending and is retried on the next opportunity; it is never
     * dropped.
     */
    @Unique
    private void schedulercore$drainPendingProducts() {
        if (schedulercore$pendingProducts.isEmpty()) {
            return;
        }
        var grid = cluster.getGrid();
        if (grid == null) {
            return; // keep them pending; the next tick retries
        }
        try {
            schedulercore$insertPendingInto(grid.getStorageService().getInventory());
        } catch (Throwable t) {
            // The items stay pending; delivery is retried on every tick, so a transient failure costs time
            // rather than items.
            SchedulerCore.LOG.error("[schedulercore] could not deliver crafted items to the network", t);
        }
    }

    /**
     * Offers the pending products to one storage, under the dump guard, leaving whatever it refuses parked.
     *
     * <p>The guard is not optional here: the target is the grid's own storage service, which offers the
     * insert to the crafting CPUs first, and this CPU re-accepting its own product would credit it to a
     * <b>surviving</b> order whose {@code waitingFor} ledger expects the same item - double-counting it and
     * leaving the real product stuck in the CPU. That is exactly the case a cancel creates: the order the
     * product belongs to is already out of the state, so the only ledger left to catch it is somebody else's.
     */
    @Unique
    private void schedulercore$insertPendingInto(appeng.api.storage.MEStorage target) {
        var src = cluster.getSrc();
        schedulercore$dumping++;
        try {
            for (var entry : schedulercore$pendingProducts) {
                long inserted = target.insert(entry.getKey(), entry.getLongValue(), Actionable.MODULATE, src);
                if (inserted > 0) {
                    entry.setValue(entry.getLongValue() - inserted);
                }
            }
            schedulercore$pendingProducts.removeZeros();
        } finally {
            schedulercore$dumping--;
        }
    }

    /**
     * Points the crafting monitor at something it can draw.
     *
     * <p>Vanilla's {@code updateOutput} can only ever describe one job, and a monitor face cannot show a
     * total anyway, so this shows <b>the first order</b> and how much of it is still owed. That choice is
     * deliberately stable: following the current time slice would make the face flip between orders every
     * tick, which on a block face reads as flicker rather than as information.
     *
     * <p>The CPU's own row and the details pane answer for the machine instead (they show the Scheduler Core
     * block and the aggregate progress); this is the one display that still names a single order.
     */
    @Unique
    private void schedulercore$refreshMonitor() {
        var state = schedulercore$state();
        if (state.isEmpty()) {
            cluster.updateOutput(null);
        } else {
            var next = state.slots().get(0).job();
            var out = schedulercore$view.finalOutput(next);
            cluster.updateOutput(out == null ? null : new appeng.api.stacks.GenericStack(
                    out.what(), schedulercore$view.remainingAmount(next)));
        }
        cluster.markDirty();
    }

    /**
     * Ends the given orders, and hands everything they were holding back to the network.
     *
     * <p>Cancelling each order individually is what returns its reserved ingredients to the network. Without
     * it a CPU with two orders would only cancel the one vanilla knows about and the other's materials would
     * be dropped on the ground when the blocks break.
     *
     * <p>Every slot is taken out of the state before the single inventory dump, for the same reason as in the
     * tick: a slot that is still listed would be a candidate for the item the dump is pushing out.
     *
     * <p>Takes a list so that "cancel one order" (the crafting screen's per-order row, see
     * {@link SchedulerScreenBridge}) and "cancel everything" (teardown) share one implementation - a second
     * code path for cancelling would be a second place to get item safety wrong.
     *
     * @param releaseFocus whether the screen may stop describing the order it was describing. The per-order
     *                     cancel passes {@code true}: it removes exactly the order the player is looking at,
     *                     and the focus is what {@code getStored} filters the item table by, so leaving it
     *                     set left the cancelled order's <b>consumable rows</b> on the CPU page. Nothing
     *                     else clears it, because {@link MultiJobState#focusedSlot()} resolves the removed
     *                     order's job object perfectly well - the slot is gone from the list, the job is not
     *                     - so the old "pinned to a slot that no longer exists" guard below never fired.
     *                     Teardown passes {@code false} and keeps relying on that guard.
     */
    @Unique
    private void schedulercore$cancelSlots(java.util.List<MultiJobState.Slot> victims, boolean releaseFocus) {
        var state = schedulercore$state();
        if (victims.isEmpty()) {
            return;
        }
        long focusedBefore = state.focusedSlotId();
        boolean focusedRemoved = false;
        int cancelled = 0;
        var removed = new java.util.ArrayList<MultiJobState.Slot>();
        for (var slot : victims) {
            if (state.byId(slot.id()) == null) {
                continue; // already gone (cancelled twice, or retired between the click and this call)
            }
            if (slot.id() == focusedBefore) {
                focusedRemoved = true;
            }
            schedulercore$closeLink(slot.job(), false);
            state.remove(slot.id());
            removed.add(slot);
            cancelled++;
        }
        // The screen must stop describing an order that no longer exists: with the focus left set, the item
        // table stayed filtered by that order's keys and kept rendering its consumables (amounts pooled from
        // the CPU inventory, expectation now zero) on a CPU page that was supposed to be showing every order.
        // The second half is the old guard, kept for the teardown path, which removes every order at once and
        // therefore does not name a focused victim.
        if ((releaseFocus && focusedRemoved)
                || (focusedBefore != SchedulingPolicy.Decision.NONE && state.focusedSlot() == null)) {
            state.releaseFocus();
        }
        // Crafted-but-undelivered products need opposite treatment depending on whether any orders remain,
        // and the answer is not a detail: the CPU inventory is the pool that every live order's
        // extractPatternInputs draws from, so putting a finished product there while another order is still
        // running makes that product eligible as <b>somebody else's ingredient</b>. That is the exact failure
        // the pending buffer exists to avoid (see the fields' javadoc and schedulercore$insertFor).
        //
        // So: with orders left, deliver to the network now and keep whatever it refuses parked, where no
        // order can consume it and the next tick retries it. With nothing left, put them in the inventory so
        // the dump below - or vanilla's drop-on-dismantle, if the CPU is coming apart right now - still
        // carries them back to the player; that is also the only safe answer for a teardown, because with no
        // scheduler core left in the multiblock nothing ticks this logic again to deliver anything.
        if (!schedulercore$pendingProducts.isEmpty()) {
            if (state.isEmpty()) {
                for (var entry : schedulercore$pendingProducts) {
                    schedulercore$inventory().insert(entry.getKey(), entry.getLongValue(), Actionable.MODULATE);
                }
                schedulercore$pendingProducts.clear();
            } else {
                schedulercore$drainPendingProducts();
            }
        }
        // Only dump when nothing is left: while orders remain, the inventory is their ingredient pool and
        // emptying it into the network would stall them (measured, see the tick's retirement block).
        if (state.isEmpty()) {
            schedulercore$dumpInventoryToNetwork();
        } else {
            // Orders remain, so the pool as a whole must not be touched - but part of what is sitting in it
            // belonged to the orders that just went away, and until now nothing ever handed those back. See
            // schedulercore$releaseKeysNotRequired.
            schedulercore$releaseKeysNotRequired(state);
        }
        // The rows of whatever was just cancelled must be re-sent, with their amounts now zero (or gone),
        // otherwise the screen keeps showing a cancelled order until it is reopened. The removed orders are
        // passed in explicitly: they are already out of the state, so a refresh that only walked the live
        // orders would never mention their items again - which is exactly how the highlight survived.
        schedulercore$refreshStatusRowsWith(removed);
        schedulercore$refreshMonitor();
        SchedulerCore.LOG.info("[schedulercore] cancelled {} scheduled job(s)", cancelled);
    }

    /**
     * Ends every scheduled order: the teardown path, and the cancel button when no single order is selected.
     *
     * <p>Does not release the focus by name: it removes every order at once, so it has no single focused
     * victim to name, and the "focused slot no longer exists" guard inside handles it.
     */
    @Unique
    private void schedulercore$cancelAllJobs() {
        schedulercore$cancelSlots(new ArrayList<>(schedulercore$state().slots()), false);
    }

    /**
     * Ends <b>one</b> scheduled order - the crafting screen's per-order cancel.
     *
     * <p>Merged into {@code CraftingCpuLogic} as a public method implementing {@link SchedulerScreenBridge},
     * so the cluster-side hook (which is where the screen's cancel button lands) can ask for exactly this
     * without duplicating the item-safety bookkeeping.
     *
     * <p>Releases the focus - but only when the order being cancelled <b>is</b> the one the screen is
     * describing. Cancelling some other order must not move the screen off the order the player is looking
     * at: the focus is what the details pane and the suspend button act on, so taking it away there would
     * change the subject of the screen for no reason. When it does go away, the screen returns to describing
     * the CPU, which is what stops a cancelled order's consumables from being rendered by the item table.
     */
    @Override
    public boolean schedulercore$cancelOrder(long slotId) {
        var state = schedulercore$state();
        var slot = state.byId(slotId);
        if (slot == null) {
            return false;
        }
        SchedulerCore.LOG.info("[schedulercore] cancelling only order #{} (per-order cancel)", slotId);
        boolean wasFocused = state.focusedSlotId() == slotId;
        schedulercore$cancelSlots(java.util.List.of(slot), wasFocused);
        return true;
    }

    /**
     * Marks every key the crafting screen's item table can show as changed.
     *
     * <p>AE2's screen is an incremental view: a row is only re-sent when its key is reported through
     * {@code postChange}. Nothing in the scheduler did that when the <i>subject</i> of the screen changed, so
     * the client kept the previous payload - reported from a real machine as "several orders mixed together,
     * every page looks the same" after clicking a per-order row, and as a cancelled order's rows lingering
     * until the screen was reopened.
     *
     * <p>The set is the union of everything the table can contain, <b>including the orders that just ended</b>
     * (see {@link #schedulercore$refreshStatusRowsWith}). That last part is easy to miss and was: a removed
     * order's product is in no live list any more, so reporting only the live orders left its row on screen
     * with its last non-zero amount - the "cancelled order still highlighted" report.
     */
    @Override
    public void schedulercore$refreshStatusRows() {
        schedulercore$refreshStatusRowsWith(java.util.List.of());
    }

    /**
     * @param alsoRemoved orders that ended in this same operation; their keys are reported too, because a
     *                    row that must now read zero has to be re-sent just as much as one that went up
     */
    @Unique
    private void schedulercore$refreshStatusRowsWith(java.util.List<MultiJobState.Slot> alsoRemoved) {
        try {
            var state = schedulercore$state();
            var seen = new java.util.HashSet<AEKey>();
            // Every order's keys, never just the focused page's: this is what tells the client that a row it
            // is still drawing has changed. Marking only the page being shown is what made a page switch look
            // like the previous page never went away - the rows the new page excludes were never re-sent, so
            // the client kept the last amounts it had for them.
            for (var slot : state.slots()) {
                schedulercore$collectAndReportOrderKeys(slot, seen);
            }
            for (var slot : alsoRemoved) {
                schedulercore$collectAndReportOrderKeys(slot, seen);
            }
            for (var entry : schedulercore$inventory().list) {
                if (seen.add(entry.getKey())) {
                    schedulercore$postChange(entry.getKey());
                }
            }
            for (var entry : schedulercore$pendingProducts) {
                if (seen.add(entry.getKey())) {
                    schedulercore$postChange(entry.getKey());
                }
            }
            if (com.schedulercore.scheduler.Trace.enabled()) {
                SchedulerCore.LOG.info("[schedulercore] {}", schedulercore$pageDump());
            }
        } catch (Throwable t) {
            SchedulerCore.LOG.error("[schedulercore] could not refresh the crafting screen", t);
        }
    }

    /**
     * Diagnostic: exactly what the screen is about to be told, key by key, for the page now being shown.
     *
     * <p>Added because "an order's page shows another order's rows" cannot be answered from the selection log
     * alone - that log shows the selection being right, so the question is whether the numbers behind the page
     * are wrong (this dump) or whether the client is failing to receive them. The three amounts per key are
     * the three questions AE2's status snapshot asks, in its own order: stored, waiting-for, pending outputs.
     * The state identity and focus id are printed too, since a focus set on one state and read from another
     * would look exactly like a filter that half works.
     *
     * <p>Gated behind {@code /schedulercore trace on}: one line per key is far too much for normal play.
     */
    @Unique
    private String schedulercore$pageDump() {
        try {
            var state = schedulercore$state();
            var self = (appeng.crafting.execution.CraftingCpuLogic) (Object) this;
            var focused = state.focusedSlot();
            var out = new StringBuilder()
                    .append("page=").append(focused == null ? "the CPU" : "order #" + focused.id())
                    .append(" focusId=").append(state.focusedSlotId())
                    .append(" state@").append(Integer.toHexString(System.identityHashCode(state)))
                    .append(" jobs=").append(state.size());
            var keys = new java.util.LinkedHashSet<AEKey>();
            for (var slot : state.slots()) {
                schedulercore$collectOrderKeys(slot, keys);
            }
            for (var entry : schedulercore$inventory().list) {
                keys.add(entry.getKey());
            }
            for (var key : keys) {
                out.append("  ").append(key.getDisplayName().getString())
                        .append('=').append(self.getStored(key))
                        .append('/').append(self.getWaitingFor(key))
                        .append('/').append(self.getPendingOutputs(key));
            }
            return out.toString();
        } catch (Throwable t) {
            return "page dump failed: " + t;
        }
    }

    @Unique
    private void schedulercore$collectAndReportOrderKeys(MultiJobState.Slot slot, java.util.Set<AEKey> seen) {
        var keys = new java.util.HashSet<AEKey>();
        schedulercore$collectOrderKeys(slot, keys);
        for (var key : keys) {
            if (seen.add(key)) {
                schedulercore$postChange(key);
            }
        }
    }

    /**
     * Every key one order is involved with: what it produces, what it expects, and what its patterns consume.
     *
     * <p>Inputs come from the pattern definitions rather than from the inventory, because that is what makes
     * the set stable: an ingredient whose stock has run out is still this order's ingredient.
     *
     * <p>Used by the screen refresh, which has to re-send a row for every key whose reported amount can have
     * changed - including the keys of an order that has just ended, whose product is in no live list any more.
     */
    @Unique
    private void schedulercore$collectOrderKeys(MultiJobState.Slot slot, java.util.Set<AEKey> into) {
        try {
            var job = slot.job();
            var output = schedulercore$view.finalOutput(job);
            if (output != null) {
                into.add(output.what());
            }
            for (var entry : schedulercore$access(job).schedulercore$waitingFor().list) {
                into.add(entry.getKey());
            }
            for (var task : schedulercore$tasksOf(job).keySet()) {
                var details = (appeng.api.crafting.IPatternDetails) task;
                for (var input : details.getInputs()) {
                    for (var possibility : input.getPossibleInputs()) {
                        if (possibility != null && possibility.what() != null) {
                            into.add(possibility.what());
                        }
                    }
                }
                for (var produced : details.getOutputs()) {
                    if (produced != null && produced.what() != null) {
                        into.add(produced.what());
                    }
                }
            }
        } catch (Throwable t) {
            SchedulerCore.LOG.error("[schedulercore] could not list an order's items", t);
        }
    }

    /**
     * Cancels the scheduler's jobs just before the CPU is taken apart.
     *
     * <p>Vanilla's {@code breakCluster} cancels the single job it knows about and then drops the CPU's
     * inventory on the floor. With the scheduler holding jobs that is wrong twice over: the other jobs keep
     * their reservations and their materials end up as loose drops instead of going back into the network.
     * Running first here means vanilla's own drop logic then finds an empty inventory.
     *
     * <p>Deliberately does not inspect the multiblock structure (it is already coming apart) and never calls
     * {@code getUnitBlock()} - that unguarded cast is exactly what crashes during the dismantle window.
     */
    @Inject(method = "cancel", at = @At("HEAD"))
    private void schedulercore$cancelScheduledJobs(CallbackInfo ci) {
        try {
            schedulercore$cancelAllJobs();
        } catch (Throwable t) {
            SchedulerCore.LOG.error("[schedulercore] cancel-all failed; falling back to vanilla", t);
        }
    }

    // ------------------------------------------------------------------ status reporting

    /**
     * Reports what the page's subject produces: the selected order's output, or - on the machine's page - the
     * Scheduler Core block.
     *
     * <p><b>Why this exists at all.</b> Vanilla builds its answer out of {@code this.job}, the field the
     * scheduler keeps empty between ticks precisely so that vanilla's own execution path cannot run behind
     * the scheduler's back. The result was that a CPU visibly working reported <b>no job at all</b>: the
     * crafting-status screen showed nothing, and {@code /schedulercore measure}'s sampling failed with "job
     * never started" on a clean rig.
     *
     * <p><b>Two subjects, one method.</b> With an order selected the page is that order's, so it reports that
     * order's output. With nothing selected the page describes the machine, and the machine's icon is the
     * Scheduler Core block: an order's output would be a lie there (the CPU is not working on one order), and
     * it would change as orders came and went. Note that the CPU's own row in the list does <b>not</b> come
     * through here - {@code CraftingCPUCluster.getJobStatus()} is answered directly - so that row stays the
     * machine's page even while an order is selected.
     *
     * <p>Falls through to vanilla when the scheduler holds nothing, so an ordinary CPU is untouched.
     */
    @Inject(method = "getFinalJobOutput", at = @At("HEAD"), cancellable = true)
    private void schedulercore$reportPageSubject(CallbackInfoReturnable<appeng.api.stacks.GenericStack> cir) {
        try {
            var state = schedulercore$state();
            var focused = state.focusedSlot();
            if (focused != null) {
                var out = schedulercore$view.finalOutput(focused.job());
                if (out != null) {
                    cir.setReturnValue(out);
                }
                return;
            }
            if (!state.isEmpty()) {
                cir.setReturnValue(schedulercore$coreIcon());
            }
        } catch (Throwable t) {
            SchedulerCore.LOG.error("[schedulercore] could not report the page's output", t);
        }
    }

    /** The Scheduler Core block, as the icon a scheduler-managed CPU draws for itself. */
    @Unique
    private static appeng.api.stacks.GenericStack schedulercore$coreIcon() {
        return new appeng.api.stacks.GenericStack(
                appeng.api.stacks.AEItemKey.of(SchedulerCore.SCHEDULER_CORE_BLOCK_ITEM.get()), 1);
    }

    /**
     * Reports the tracker behind the page's numbers: the selected order's, or - on the machine's page - a
     * synthetic tracker carrying the CPU's totals (see {@link #schedulercore$reportTotalsTracker}).
     *
     * <p><b>Why a synthetic tracker is needed at all.</b> AE2's tracker is fraction-based:
     * {@code getStartItemCount()} is the fixed 2^31-1 scale, {@code getProgress()} is
     * {@code Σ completedWork / Σ startedWork} over the key types in that type's own unit, and
     * {@code getRemainingItemCount()} is derived from the scale and that fraction. So a "whole CPU" tracker is
     * one whose two maps hold the CPU's weighted progress as a single item-type figure - the numbers
     * themselves come from {@code MultiJobState.totals()}, which is also where the weighting is explained.
     *
     * <p>One tracker per CPU, refreshed on every read: it is never handed to the execution path (only to
     * status reporting), so nothing else can mutate it behind our back.
     */
    @Inject(method = "getElapsedTimeTracker", at = @At("HEAD"), cancellable = true)
    private void schedulercore$reportTotalsTracker(
            CallbackInfoReturnable<appeng.crafting.execution.ElapsedTimeTracker> cir) {
        try {
            var state = schedulercore$state();
            var focused = state.focusedSlot();
            if (focused != null) {
                var tracker = schedulercore$view.timeTracker(focused.job());
                if (tracker != null) {
                    cir.setReturnValue(tracker);
                }
                return;
            }
            var totals = schedulercore$totalsTracker(state);
            if (totals != null) {
                cir.setReturnValue(totals);
            }
        } catch (Throwable t) {
            SchedulerCore.LOG.error("[schedulercore] could not report the page's tracker", t);
        }
    }

    /**
     * This CPU's aggregate tracker, refreshed from {@code state.totals()}, or null when it holds no orders.
     *
     * <p>Shared by the machine's page and by the CPU's own row, so both report the same numbers.
     */
    @Unique
    private appeng.crafting.execution.ElapsedTimeTracker schedulercore$totalsTracker(MultiJobState state) {
        var totals = state.isEmpty() ? null : state.totals();
        if (totals == null) {
            return null;
        }
        var tracker = schedulercore$totalsTracker;
        if (tracker == null) {
            tracker = new appeng.crafting.execution.ElapsedTimeTracker();
            schedulercore$totalsTracker = tracker;
        }
        var access = (AccessorElapsedTimeTracker) (Object) tracker;
        access.schedulercore$setElapsedTime(totals.elapsedNanos());
        // Both clock fields, not just the elapsed total: getElapsedTime() extrapolates to this instant while
        // any work is outstanding (completedWorkByType < startedWorkByType), so leaving lastTime at the
        // tracker's construction time would report the aggregate plus its own age.
        access.schedulercore$setLastTime(System.nanoTime());
        // Any positive denominator does: only the ratio between the two maps is readable, and 1e6 keeps the
        // rounding of the numerator below one part in a million of the progress bar.
        final long scale = 1_000_000L;
        access.schedulercore$startedWorkByType().put(appeng.api.stacks.AEKeyType.items(), scale);
        access.schedulercore$completedWorkByType().put(appeng.api.stacks.AEKeyType.items(),
                Math.round(scale * totals.progress()));
        return tracker;
    }

    /**
     * The machine's job status, for the CPU's own row in the list: Scheduler Core icon, aggregate progress,
     * the oldest order's elapsed time.
     *
     * <p>Answered here rather than derived from {@link #schedulercore$reportPageSubject} on purpose: a row is
     * not a page. The pane follows the selected row, so its numbers follow the selection; the CPU's row must
     * not, or selecting an order would make the machine's row claim to be that order.
     */
    @Override
    public appeng.api.networking.crafting.CraftingJobStatus schedulercore$cpuStatus() {
        var state = schedulercore$state();
        var tracker = schedulercore$totalsTracker(state);
        if (tracker == null) {
            return null;
        }
        long start = tracker.getStartItemCount();
        long remaining = tracker.getRemainingItemCount();
        return new appeng.api.networking.crafting.CraftingJobStatus(
                schedulercore$coreIcon(), start, Math.max(0, start - remaining), tracker.getElapsedTime());
    }

    /**
     * Reports how much of {@code template} the CPU is storing <b>for the order being shown</b>.
     *
     * <p>With no order selected the page describes the machine, and the pooled amount is the right answer -
     * that is vanilla's own.
     *
     * <p><b>Why the selected case needs filtering rather than summing.</b> The CPU's inventory is shared by
     * every order, so {@code getStored} answers with the pooled amount, which on a single order's page made
     * it list every order's ingredients. Reported from a real machine as "the plan is per-order now, but the
     * consumables still show all orders" - and reported again, from the other side, when 1.0.4 first dropped
     * this filtering altogether: "the order's page shows the totals". So what this decides is <i>which</i>
     * rows an order's page may show: the items that order actually produces, expects or consumes.
     *
     * <p>The honest limitation, stated rather than hidden: the amount remains the shared pool, not a per-order
     * share of it. Nothing can attribute items to an order without giving each order its own inventory, so
     * when two orders need the same ingredient both pages show the pooled number.
     */
    @Inject(method = "getStored", at = @At("HEAD"), cancellable = true)
    private void schedulercore$reportStoredForFocusedOrder(AEKey template, CallbackInfoReturnable<Long> cir) {
        try {
            var state = schedulercore$state();
            var focused = state.focusedSlot();
            if (focused == null) {
                return; // the machine's page: vanilla's shared-inventory answer is the right one
            }
            if (!schedulercore$orderKeys(focused).contains(template)) {
                cir.setReturnValue(0L); // not this order's business: its page must not show this row
            }
        } catch (Throwable t) {
            SchedulerCore.LOG.error("[schedulercore] could not scope the stored amounts to an order", t);
        }
    }

    /** Every key one order is involved with, for scoping its page's item table. */
    @Unique
    private java.util.Set<AEKey> schedulercore$orderKeys(MultiJobState.Slot slot) {
        var keys = new java.util.HashSet<AEKey>();
        schedulercore$collectOrderKeys(slot, keys);
        return keys;
    }

    /**
     * Reports what the CPU is waiting for <b>in total</b>, summed over every scheduled order.
     *
     * <p>Vanilla reads its single job's ledger. Reporting only the displayed job (the first version of this
     * hook) made the item table show one order's numbers and zero for the others - on a real machine that
     * read as "only one recipe is highlighted". The screen's item table is a <i>per-CPU</i> view: the CPU's
     * inventory is shared between all its orders and {@code getStored} has always reported it as one pool, so
     * summing the ledgers is the same kind of statement - and the only one that is true of the machine.
     *
     * <p>These columns follow the page: with an order selected they are that order's plan, and with nothing
     * selected they are the machine's totals. The CPU's own row in the list is the exception - it always
     * reports the machine (see {@code MultiJobState.totals()}).
     */
    @Inject(method = "getWaitingFor", at = @At("HEAD"), cancellable = true)
    private void schedulercore$reportServedWaitingFor(AEKey template, CallbackInfoReturnable<Long> cir) {
        try {
            var state = schedulercore$state();
            if (state.isEmpty()) {
                return; // nothing scheduled: vanilla's answer (0) is correct
            }
            long total = 0;
            for (var slot : state.tableSlots()) {
                total += schedulercore$view.waitingFor(slot.job(), template);
            }
            cir.setReturnValue(total);
        } catch (Throwable t) {
            SchedulerCore.LOG.error("[schedulercore] could not report the CPU's waiting-for", t);
        }
    }

    /**
     * Adds every scheduled order's expected items to the caller's set, mirroring vanilla's
     * {@code getAllWaitingFor}.
     *
     * <p>AE2's crafting service collects these to answer "is this item being crafted right now", which is
     * what makes the terminal mark such items as in progress. Aggregated over all orders for the same reason
     * as {@link #schedulercore$reportServedWaitingFor}: the question is about the CPU.
     */
    @Inject(method = "getAllWaitingFor", at = @At("HEAD"), cancellable = true)
    private void schedulercore$reportServedWaitingForAll(java.util.Set<AEKey> waitingFor, CallbackInfo ci) {
        try {
            var state = schedulercore$state();
            if (state.isEmpty()) {
                return; // nothing scheduled: vanilla's answer (an empty set) is correct
            }
            for (var slot : state.tableSlots()) {
                for (var entry : schedulercore$access(slot.job()).schedulercore$waitingFor().list) {
                    waitingFor.add(entry.getKey());
                }
            }
            // Vanilla would add its single job's keys, and that job does not exist on a scheduler CPU.
            ci.cancel();
        } catch (Throwable t) {
            SchedulerCore.LOG.error("[schedulercore] could not report the CPU's expected items", t);
        }
    }

    /**
     * Reports the CPU's outstanding pattern outputs, summed over every scheduled order.
     *
     * <p>This is the "pending" column and the row highlight of the crafting-status screen. Vanilla computes
     * it as {@code sum(output.amount * taskProgress)} over its single job's task table; a scheduler CPU sums
     * the same quantity over every order, for the reason given in
     * {@link #schedulercore$reportServedWaitingFor}.
     */
    @Inject(method = "getPendingOutputs", at = @At("HEAD"), cancellable = true)
    private void schedulercore$reportServedPendingOutputs(AEKey template, CallbackInfoReturnable<Long> cir) {
        try {
            var state = schedulercore$state();
            if (state.isEmpty()) {
                return;
            }
            long total = 0;
            for (var slot : state.tableSlots()) {
                total += schedulercore$pendingOutputs(slot.job(), template);
            }
            cir.setReturnValue(total);
        } catch (Throwable t) {
            SchedulerCore.LOG.error("[schedulercore] could not report the served job's pending outputs", t);
        }
    }

    // The two suspend hooks are NOT here: they exist only where AE2 has the feature (19.2.16+, i.e. the
    // 1.21.1 target), so they live in that target's mixin and go through
    // {@link com.schedulercore.scheduler.SuspendSupport}. Everything shared reads the flag through that
    // class, which is why this file needs no idea whether suspend exists at all.


    /**
     * True when this CPU multiblock contains a scheduler core.
     *
     * <p>This is the takeover criterion: it is a property of the machine the player assembled, so it is
     * true from the very first job. The check only reads block states and never calls
     * {@code CraftingBlockEntity.getUnitBlock()} - that method casts the block at its position without a
     * guard and throws {@code ClassCastException} during the dismantle window (see ClusterUnits).
     */
    @Unique
    private boolean schedulercore$hasSchedulerCore() {
        try {
            return com.schedulercore.logic.ClusterUnits.hasSchedulerCore(cluster.getBlockEntities());
        } catch (Throwable t) {
            // A CPU being dismantled must never take the server down because of us; treating it as
            // "no core" simply leaves admission to vanilla.
            return false;
        }
    }

    /**
     * How many ticks a cached "does this CPU have a core" answer is reused before it is measured again.
     *
     * <p>One second. Long enough that the per-tick cost stops depending on the CPU's size, short enough that
     * noticing a block change late is harmless: the only decisions that depend on the answer are "should the
     * scheduler serve this CPU's queued jobs" and "was the core removed while orders were running", and the
     * second one is handled immediately and precisely by {@code SchedulerCoreBlock.onRemove} (D8).
     */
    @Unique
    private static final int SCHEDULERCORE_CORE_CHECK_INTERVAL = 20;

    /** Ticks until the cached answer must be re-measured; 0 means "measure on the next call". */
    @Unique
    private int schedulercore$coreCheckCountdown;

    /** The cached answer from the last measurement. */
    @Unique
    private boolean schedulercore$corePresentCached;

    /**
     * {@link #schedulercore$hasSchedulerCore()} for the per-tick path: same answer, sampled at most once per
     * {@link #SCHEDULERCORE_CORE_CHECK_INTERVAL} ticks.
     *
     * <p><b>Admission deliberately does not use this.</b> A player who places the core and immediately submits
     * an order must be taken over on that very submission; a stale "no core" there would hand the order to
     * vanilla and reproduce the "cannot submit a second order" bug in a new form. So the expensive path stays
     * exact and only the per-tick path is sampled.
     */
    @Unique
    private boolean schedulercore$hasSchedulerCoreCached() {
        if (schedulercore$coreCheckCountdown > 0) {
            schedulercore$coreCheckCountdown--;
            return schedulercore$corePresentCached;
        }
        schedulercore$coreCheckCountdown = SCHEDULERCORE_CORE_CHECK_INTERVAL;
        schedulercore$corePresentCached = schedulercore$hasSchedulerCore();
        return schedulercore$corePresentCached;
    }

    /** Vanilla's {@code usedOps} array, cached. It throttles a CPU to about c+1 ops per three ticks. */
    @Unique
    private static java.lang.reflect.Field schedulercore$usedOpsField;

    /** The live {@code usedOps} array, resolving the field once. Throws if it cannot be reached. */
    @Unique
    private int[] schedulercore$usedOpsArray() throws ReflectiveOperationException {
        if (schedulercore$usedOpsField == null) {
            var f = CraftingCpuLogic.class.getDeclaredField("usedOps");
            f.setAccessible(true);
            schedulercore$usedOpsField = f;
        }
        return (int[]) schedulercore$usedOpsField.get(this);
    }

    @Unique
    private int schedulercore$usedOps(int index) {
        try {
            var ops = schedulercore$usedOpsArray();
            return index >= 0 && index < ops.length ? ops[index] : 0;
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    /**
     * Slides the rolling window the same way vanilla does: {@code [2]=[1]; [1]=[0]; [0]=spent}.
     *
     * <p>The scheduler has to maintain this itself, because it cancels vanilla's own tick (where the
     * shifting normally happens). Skipping it would leave the window at zero forever, which is exactly how
     * skipping it would leave the window at zero for ever, which grants a full {@code c + 1} every single tick.
     */
    @Unique
    private void schedulercore$shiftUsedOps(int spentThisTick) {
        try {
            var ops = schedulercore$usedOpsArray();
            ops[2] = ops[1];
            ops[1] = ops[0];
            ops[0] = spentThisTick;
        } catch (ReflectiveOperationException e) {
            SchedulerCore.LOG.warn("[schedulercore] could not maintain usedOps; throughput may drift", e);
        }
    }

    @Unique
    private void schedulercore$postChange(AEKey key) {
        // Notify whatever the status menu registered, exactly as vanilla's own postChange does.
        for (var listener : schedulercore$listeners()) {
            listener.accept(key);
        }
    }

    /** Vanilla's listener set, reached by reflection so this mixin needs no accessor mixin. */
    @Unique
    @SuppressWarnings("unchecked")
    private java.util.Set<java.util.function.Consumer<AEKey>> schedulercore$listeners() {
        try {
            if (schedulercore$listenersField == null) {
                var f = CraftingCpuLogic.class.getDeclaredField("listeners");
                f.setAccessible(true);
                schedulercore$listenersField = f;
            }
            return (java.util.Set<java.util.function.Consumer<AEKey>>) schedulercore$listenersField.get(this);
        } catch (ReflectiveOperationException e) {
            return java.util.Set.of();
        }
    }

    @Unique
    private static java.lang.reflect.Field schedulercore$listenersField;

    /** Pushes this job's patterns with the whole budget, replicating vanilla's own do/while loop. */
    @Unique
    private int schedulercore$pushFor(ExecutingCraftingJob job, int budget, CraftingService craftingService,
            IEnergyService energyService) {
        var logic = (CraftingCpuLogic) (Object) this;
        // Point vanilla's single-job field at the job that owns this tick, run vanilla's own push loop,
        // then put the field back. Restoring in a finally block matters: leaving a stale job behind would
        // corrupt every other vanilla code path (insert, status, save).
        var previous = schedulercore$swapVanillaJob(job);
        try {
            int remaining = budget;
            int total = 0;
            do {
                int pushed = logic.executeCrafting(remaining, craftingService, energyService, cluster.getLevel());
                if (pushed <= 0) {
                    break;
                }
                total += pushed;
                remaining -= Math.min(pushed, remaining);
            } while (remaining > 0);
            return total;
        } finally {
            schedulercore$swapVanillaJob(previous);
        }
    }

    /**
     * Routes an incoming stack into one specific job.
     *
     * <p><b>Why the final output goes to the CPU inventory rather than the crafting link.</b> The scheduler
     * creates every job as a <i>standalone</i> link, and a standalone link is never wired into a
     * {@code CraftingLinkNexus}:
     *
     * <pre>
     * CraftingService.addLink(CraftingLink):
     *     if (link.isStandalone()) return;      // no nexus is ever created
     *
     * CraftingLink.insert(...):
     *     if (tie == null || tie.getRequest() == null) return 0;   // everything is discarded
     * </pre>
     *
     * So handing the final output to the link silently threw it away - measured on a real machine as
     * "the craft reports success but nothing arrives in the network".
     *
     * <p><b>The final output goes straight into network storage, immediately.</b> It must not sit in the
     * CPU inventory until the job ends: a real machine showed the whole order arriving in one lump at the
     * end, when AE2's behaviour is that each finished craft flows into the network as soon as the machine
     * hands it back. Intermediates stay in the CPU inventory (they are inputs for the next step), but the
     * product belongs to the network the moment it exists.
     */
    @Unique
    private long schedulercore$insertFor(ExecutingCraftingJob job, AEKey what, long amount, Actionable mode) {
        long move = schedulercore$view.takeWaitingFor(job, what, amount, mode);
        if (move <= 0) {
            return 0;
        }
        if (mode == Actionable.MODULATE) {
            // Vanilla's insert does this for every item it accepts, before deciding what the item is:
            //     job.timeTracker.decrementItems(amount, what.getType());
            // The scheduler reuses the ledger half of that accounting but used to skip the tracker half, so
            // "items still to come" never moved - which meant progress and ETA would have been permanently
            // wrong the moment anything read them (the crafting-status screen, /schedulercore status, and
            // /schedulercore measure's sampling all do).
            schedulercore$view.decrementTracker(job, what, move);

            var out = schedulercore$view.finalOutput(job);
            var isFinalOutput = out != null && what.matches(out);

            if (isFinalOutput) {
                // The product is parked, not buffered, and delivered on the next tick.
                //
                // <b>It cannot be delivered here.</b> This runs inside the network's own insert (an assembler
                // handed the item back through the storage service), and NetworkStorage.insert is itself
                // re-entrancy guarded: it sets mountsInUse for the duration and answers 0 to any nested
                // insert *before consulting a single storage*. Measured on a real rig: a delivery attempted
                // from here never reached a storage at all, and the whole order then arrived in one lump when
                // the job ended.
                //
                // <b>Nor can it go into the CPU inventory.</b> That inventory is the pool this job's own
                // extractPatternInputs draws from, so a finished product left there can be consumed as an
                // ingredient by any job sharing the CPU, and dumping the pool to "flush" it takes away the
                // inputs the surviving jobs have not used yet (measured: one job stalled at 996/1000 with its
                // entire input stock sitting in the network).
                schedulercore$view.setRemainingAmount(job,
                        Math.max(0, schedulercore$view.remainingAmount(job) - move));
                schedulercore$pendingProducts.add(what, move);
                schedulercore$postChange(what);
            } else {
                schedulercore$inventory().insert(what, move, Actionable.MODULATE);
            }
            cluster.markDirty();
        }
        return move;
    }

}
