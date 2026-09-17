package com.schedulercore.measure;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.blockentity.crafting.PatternProviderBlockEntity;

import com.schedulercore.command.SchedulerRigCommand;

import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Measurement: run one craft on a chosen rig CPU and record the job's progress every tick.
 *
 * <h2>What is measured, and why</h2>
 *
 * <p>AE2 does not expose "how many patterns did this CPU push this tick". What it does expose is
 * {@link CraftingJobStatus}: total items, progress and elapsed time. For a single job, the per-tick delta
 * of {@code progress} is how much output the CPU actually produced that tick - which is exactly the
 * quantity the throughput guarantees care about. So the observable is progress-per-tick, and the
 * histogram of its deltas is the report. A stall - the CPU going idle for a stretch - shows up in the same
 * histogram as a run of zero deltas.
 *
 * <h2>Why this is a state machine and not a straight-line command</h2>
 *
 * <p>AE2 computes a craft plan asynchronously on a worker thread. The first version of this class called
 * {@code future.get()} inside the command handler, which blocks the <b>server thread</b>; because the
 * planner's completion path itself needs the server thread, that deadlocked the whole server (RCON stopped
 * answering and the process had to be killed). So the command only <i>starts</i> the calculation and
 * returns; {@link #onServerTick} polls {@link Future#isDone()} and chains the next step. The server thread
 * is never blocked, and a plan that never arrives times out instead of hanging.
 */
public final class RigMeasure {

    /** One tick's observation of a running job. */
    private record Sample(int tick, long progress) {
    }

    /** Where the measurement currently is. */
    private enum Phase {
        IDLE, PLANNING, SAMPLING, DONE
    }

    private static final class Run {
        final String cpuLabel;
        final long requested;
        final List<Sample> samples = new ArrayList<>();
        Phase phase = Phase.PLANNING;
        Future<ICraftingPlan> plan;
        appeng.api.networking.security.IActionSource source;
        int startedPlanningAtTick;
        long lastProgress = -1;
        int servedTick = -1;
        String note = "";

        Run(String cpuLabel, long requested) {
            this.cpuLabel = cpuLabel;
            this.requested = requested;
        }
    }

    private static Run active;

    /**
     * The optional second job used for the multi-job admission test, kept separate from {@link #active} so
     * that submitting it cannot disturb the first job's measurement.
     */
    private static Future<ICraftingPlan> secondPlan;
    private static appeng.api.networking.security.IActionSource secondSource;
    private static int secondRequestedAtTick;

    private RigMeasure() {
    }

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("schedulercore")
                .then(Commands.literal("pattern").executes(ctx -> installPattern(ctx.getSource())))
                .then(Commands.literal("grid").executes(ctx -> gridStatus(ctx.getSource())))
                .then(Commands.literal("creativetrial").executes(ctx -> creativeCellTrial(ctx.getSource())))
                .then(Commands.literal("craft")
                        .then(Commands.argument("cpu", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    b.suggest("aa");
                                    b.suggest("vanilla");
                                    return b.buildFuture();
                                })
                                .then(Commands.argument("amount", LongArgumentType.longArg(1, 100_000))
                                        .executes(ctx -> startCraft(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "cpu"),
                                                LongArgumentType.getLong(ctx, "amount"))))))
                .then(Commands.literal("submit").executes(ctx -> submitCraft(ctx.getSource())))
                .then(Commands.literal("clearstuck").executes(ctx -> clearStuck(ctx.getSource())))
                .then(Commands.literal("cpus").executes(ctx -> reportAllCpus(ctx.getSource())))
                .then(Commands.literal("status").executes(ctx -> schedulerStatus(ctx.getSource())))
                .then(Commands.literal("uiprobe")
                        .executes(ctx -> uiProbe(ctx.getSource(), "aa", false))
                        .then(Commands.literal("vanilla").executes(ctx -> uiProbe(ctx.getSource(), "vanilla", false)))
                        .then(Commands.literal("toggle").executes(ctx -> uiProbe(ctx.getSource(), "aa", true)))
                        .then(Commands.literal("toggle")
                                .then(Commands.literal("vanilla")
                                        .executes(ctx -> uiProbe(ctx.getSource(), "vanilla", true))))
                        .then(Commands.literal("rows").executes(ctx -> uiProbeRows(ctx.getSource())))
                        .then(Commands.literal("focus")
                                .then(Commands.argument("id", LongArgumentType.longArg(1))
                                        .executes(ctx -> uiProbeFocus(ctx.getSource(),
                                                LongArgumentType.getLong(ctx, "id")))))
                        .then(Commands.literal("release").executes(ctx -> uiProbeRelease(ctx.getSource())))
                        .then(Commands.literal("cancel").executes(ctx -> uiProbeCancel(ctx.getSource()))))
                .then(Commands.literal("submit2").executes(ctx -> submitSecond(ctx.getSource())))
                .then(Commands.literal("schedstate").executes(ctx -> schedulerState(ctx.getSource())))
                .then(Commands.literal("trace")
                        .then(Commands.literal("on").executes(ctx -> setTrace(ctx.getSource(), true)))
                        .then(Commands.literal("off").executes(ctx -> setTrace(ctx.getSource(), false))))
                .then(Commands.literal("measure")
                        .then(Commands.literal("status").executes(ctx -> measureStatus(ctx.getSource())))
                        .then(Commands.literal("reset").executes(ctx -> {
                            active = null;
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal("[schedulercore] measurement cleared"), false);
                            return 1;
                        }))));
    }

    // ------------------------------------------------------------------ pattern install

    private static int installPattern(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Object encoded = RigCrafts.encodeStickPattern(level);
        if (encoded instanceof String error) {
            source.sendFailure(Component.literal("[schedulercore] " + error));
            return 0;
        }
        if (!RigCrafts.installPattern(level, SchedulerRigCommand.providerPos(), (ItemStack) encoded)) {
            source.sendFailure(Component.literal("[schedulercore] could not install the pattern (provider missing?)"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "[schedulercore] crafting pattern installed: " + RigCrafts.INPUT_ITEM + " -> "
                        + RigCrafts.OUTPUT_ITEM + " x4"), true);
        return 1;
    }

    // ------------------------------------------------------------------ craft (non-blocking)

    private static int startCraft(CommandSourceStack source, String cpuName, long amount) {
        ServerLevel level = source.getLevel();
        BlockPos core = coreFor(cpuName);

        if (!(level.getBlockEntity(core) instanceof CraftingBlockEntity be) || be.getCluster() == null) {
            source.sendFailure(Component.literal("[schedulercore] no formed CPU at " + core.toShortString()));
            return 0;
        }
        IGrid grid = be.getCluster().getGrid();
        if (grid == null) {
            source.sendFailure(Component.literal("[schedulercore] CPU is not on a grid"));
            return 0;
        }

        long supplied = RigCrafts.supplyInput(level, SchedulerRigCommand.interfacePos(), amount * 4 + 4096);
        // Seed the CPU's own inventory as well. AE2 pulls a job's ingredients into exactly this inventory
        // when the job is submitted, and ListCraftingInventory is itself an ICraftingInventory, so having
        // the material there satisfies both the plan calculation and the execution. This is the path that
        // works on a freshly built rig, where the network storage service mounts nothing.
        long seeded = RigCrafts.supplyDirectToCpu(be.getCluster().craftingLogic, amount * 4 + 4096);
        source.sendSuccess(() -> Component.literal(
                "[schedulercore] supplied: interface=" + supplied + " cpuInventory=" + seeded), false);
        if (seeded <= 0 && supplied <= 0) {
            source.sendFailure(Component.literal("[schedulercore] could not supply any input material"));
            return 0;
        }

        var run = new Run(cpuName.toLowerCase(), amount);
        run.startedPlanningAtTick = -1; // no plan yet; 'submit' starts it
        active = run;

        source.sendSuccess(() -> Component.literal(
                "[schedulercore] material supplied for " + amount + " x " + RigCrafts.OUTPUT_ITEM
                        + " (CPU '" + cpuName + "'). Run '/schedulercore submit' on a later tick to start"
                        + " planning - the grid storage snapshot needs a tick to include the new material."),
                true);
        return 1;
    }

    /**
     * Starts the craft for the run prepared by {@code craft}.
     *
     * <p>Split from {@code craft} deliberately. The first version supplied the material and started the
     * plan in the same tick, and AE2's planner then saw its <i>pre-supply</i> storage snapshot: the plan
     * came back simulated with {@code patternTimes=0}, which reads like "no pattern" but actually means
     * "the ingredients were not in the snapshot yet". Running the two steps on separate ticks fixes it.
     */
    private static int submitCraft(CommandSourceStack source) {
        return submitToCpu(source, false);
    }

    /**
     * Submits a <b>second</b> job to the CPU that is already running one.
     *
     * <p>This is the multi-job admission test. Vanilla answers {@code CPU_BUSY} the moment a CPU holds a
     * job, so a second successful submission is direct evidence that the scheduler has taken over
     * admission. The plan is identical to the first job's on purpose: same pattern, same output, so the
     * only variable is the scheduler itself.
     */
    private static int submitSecond(CommandSourceStack source) {
        return submitToCpu(source, true);
    }

    private static int submitToCpu(CommandSourceStack source, boolean second) {
        Run run = active;
        if (run == null) {
            source.sendFailure(Component.literal("[schedulercore] run 'craft <cpu> <amount>' first"));
            return 0;
        }
        ServerLevel level = source.getLevel();
        BlockPos core = coreFor(run.cpuLabel);
        if (!(level.getBlockEntity(core) instanceof CraftingBlockEntity be) || be.getCluster() == null) {
            source.sendFailure(Component.literal("[schedulercore] no formed CPU at " + core.toShortString()));
            return 0;
        }
        IGrid grid = be.getCluster().getGrid();
        if (grid == null) {
            source.sendFailure(Component.literal("[schedulercore] CPU is not on a grid"));
            return 0;
        }
        // Attribute the request to the pattern provider: CraftingTreeNode needs a real grid node from the
        // simulation requester, otherwise it silently skips every pattern (see RigCrafts.beginPlan).
        if (!(level.getBlockEntity(SchedulerRigCommand.providerPos())
                instanceof PatternProviderBlockEntity providerHost)) {
            source.sendFailure(Component.literal("[schedulercore] no pattern provider to attribute the request to"));
            return 0;
        }
        var planning = RigCrafts.beginPlan(grid, level, run.requested, providerHost);
        if (second) {
            // Keep the second job's plan out of the run state: the run is measuring the FIRST job.
            secondPlan = planning.plan();
            secondSource = planning.source();
            secondRequestedAtTick = level.getServer().getTickCount();
            source.sendSuccess(() -> Component.literal(
                    "[schedulercore] SECOND job requested (" + run.requested + " x " + RigCrafts.OUTPUT_ITEM
                            + "); submitting to the already-busy CPU as soon as the plan is ready."), true);
            return 1;
        }
        run.plan = planning.plan();
        run.source = planning.source();
        run.phase = Phase.PLANNING;
        run.startedPlanningAtTick = level.getServer().getTickCount();

        // Probe craftability in the SAME tick as the plan request. A previous round measured
        // craftable=true on this very rig while the plan still came back with patternTimes=0, so the
        // two facts have to be observed together before either can be believed.
        var probeKey = appeng.api.stacks.AEItemKey.of(
                new ItemStack(BuiltInRegistries.ITEM.get(RigCrafts.OUTPUT_ITEM)));
        final boolean craftable = probeKey != null && grid.getCraftingService().isCraftable(probeKey);
        int providerPatterns = -1;
        if (level.getBlockEntity(SchedulerRigCommand.providerPos())
                instanceof PatternProviderBlockEntity provider) {
            providerPatterns = provider.getLogic().getAvailablePatterns().size();
        }
        final int patterns = providerPatterns;
        source.sendSuccess(() -> Component.literal(
                "[schedulercore] pre-plan probe: craftable=" + craftable + " providerPatterns=" + patterns), true);
        return 1;
    }

    // ------------------------------------------------------------------ tick state machine

    public static void onServerTick(ServerTickEvent.Post event) {
        Run run = active;
        if (run == null || run.phase == Phase.DONE || run.phase == Phase.IDLE) {
            // Still advance a queued second job: the first job may already have finished.
            tickSecondJob(event.getServer());
            return;
        }
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        int now = server.getTickCount();

        BlockPos core = coreFor(run.cpuLabel);
        if (!(level.getBlockEntity(core) instanceof CraftingBlockEntity be) || be.getCluster() == null) {
            finish(server, run, "CPU disappeared or unformed");
            return;
        }
        var cluster = be.getCluster();

        // The multi-job admission test: submit the queued second job once its plan is ready, and report
        // whether the already-busy CPU accepted it. Vanilla would answer CPU_BUSY here.
        tickSecondJob(server);

        switch (run.phase) {
            case PLANNING -> {
                if (run.plan == null) {
                    return; // 'craft' has supplied material but 'submit' has not started the plan yet
                }
                if (now - run.startedPlanningAtTick > RigTiming.PLAN_TIMEOUT_TICKS) {
                    finish(server, run, "craft plan timed out after " + RigTiming.PLAN_TIMEOUT_TICKS + " ticks");
                    return;
                }
                if (!run.plan.isDone()) {
                    return; // never block the server thread waiting for the planner
                }
                var outcome = RigCrafts.submitPlanned(run.plan, cluster.getGrid(), cluster, run.source);
                if (!outcome.submitted()) {
                    // Surface exactly what AE2 thinks is missing: guessing at "missing ingredients" from the
                    // outside wasted a lot of time, and this is the one place that can say it precisely.
                    StringBuilder missing = new StringBuilder();
                    try {
                        var p = run.plan.get();
                        if (p != null) {
                            missing.append(" simulation=").append(p.simulation())
                                    .append(" patternTimes=").append(p.patternTimes().size());
                            missing.append(" missing=").append(describe(p.missingItems()));
                            missing.append(" used=").append(describe(p.usedItems()));
                            missing.append(" emitted=").append(describe(p.emittedItems()));
                            missing.append(" bytes=").append(p.bytes());
                            missing.append(" finalOutput=").append(p.finalOutput());
                        } else {
                            missing.append(" plan=null");
                        }
                    } catch (Exception e) {
                        missing.append(" planError=").append(e);
                    }
                    finish(server, run, "submit failed: " + outcome.message() + missing);
                    return;
                }
                run.phase = Phase.SAMPLING;
                run.servedTick = now;
                RigTiming.broadcast(server, "[schedulercore] job submitted to '" + run.cpuLabel + "'");
            }
            case SAMPLING -> {
                CraftingJobStatus status = cluster.getJobStatus();
                if (status == null) {
                    finish(server, run, run.samples.isEmpty() ? "job never started" : "job finished");
                    return;
                }
                long progress = status.progress();
                run.samples.add(new Sample(now, progress));
                if (progress != run.lastProgress) {
                    run.lastProgress = progress;
                } else if (stalledFor(run) > RigTiming.STALL_TICKS) {
                    finish(server, run, "STALLED: no progress for " + RigTiming.STALL_TICKS + "+ ticks");
                }
            }
            default -> {
            }
        }
    }

    /**
     * Submits the queued second job once its plan is ready.
     *
     * <p>Kept as its own step so it works whether or not the first job's measurement is still running.
     */
    private static void tickSecondJob(MinecraftServer server) {
        if (secondPlan == null) {
            return;
        }
        if (!secondPlan.isDone()) {
            if (server.getTickCount() - secondRequestedAtTick > RigTiming.PLAN_TIMEOUT_TICKS) {
                secondPlan = null;
                secondSource = null;
                RigTiming.broadcast(server, "[schedulercore] second job: plan timed out");
            }
            return;
        }
        ServerLevel level = server.overworld();
        BlockPos core = coreFor(active == null ? "aa" : active.cpuLabel);
        if (!(level.getBlockEntity(core) instanceof CraftingBlockEntity be) || be.getCluster() == null) {
            secondPlan = null;
            secondSource = null;
            return;
        }
        var cluster = be.getCluster();
        var outcome = RigCrafts.submitPlanned(secondPlan, cluster.getGrid(), cluster, secondSource);
        secondPlan = null;
        secondSource = null;
        final String verdict = outcome.submitted()
                ? "ACCEPTED - the CPU was already busy, so this proves scheduler admission is in control"
                : "REJECTED (" + outcome.message() + ")";
        RigTiming.broadcast(server, "[schedulercore] second job: " + verdict);
    }

    /**
     * Reports <b>every</b> crafting CPU reachable from the pattern provider's grid.
     *
     * <p>Added because the scheduler-specific commands only knew about the rig's own coordinates, so a problem
     * on a player's own CPU was invisible. This walks the grid's crafting CPUs instead, which is what makes
     * "why will it not take a second order" answerable without guessing.
     */
    private static int reportAllCpus(CommandSourceStack source) {
        ServerLevel level = source.getLevel();

        var grid = rigGrid(source);
        // Say which grid answered: a console or a command block has no position, so "next to you" would be
        // wrong in exactly the case this fallback exists for.
        final String origin = grid != null ? "using the acceptance rig's grid" : "using the grid next to you";
        if (grid == null) {
            grid = findGridNear(level, BlockPos.containing(source.getPosition()));
        }
        if (grid == null) {
            source.sendFailure(Component.literal(
                    "[schedulercore] no AE grid found near you. Stand next to (or inside) your crafting CPU"
                            + " or its cables and run this again."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(origin), false);

        int cpuCount = grid.getCraftingService().getCpus().size();
        source.sendSuccess(() -> Component.literal(
                "=== grid with " + cpuCount + " crafting CPU(s) ==="), false);
        int index = 0;
        for (var cpu : grid.getCraftingService().getCpus()) {
            index++;
            final int i = index;
            var name = cpu.getName() == null ? "(unnamed)" : cpu.getName().getString();
            var cluster = cpu instanceof appeng.me.cluster.implementations.CraftingCPUCluster c ? c : null;
            var state = cluster == null ? null : com.schedulercore.scheduler.MultiJobState.forCluster(cluster);
            final String sched = state == null ? "no scheduled jobs" : state.describe();
            final String job = jobText(cpu);
            final String stuck = cluster == null ? "?"
                    : (com.schedulercore.scheduler.StuckJobRepair.hasUntrackedJob(cluster.craftingLogic)
                            ? "YES - untracked job is holding this CPU (run clearstuck)"
                            : "no");
            source.sendSuccess(() -> Component.literal(
                    "cpu#" + i + " " + name
                            + " busy=" + cpu.isBusy()
                            + " capacity=" + cpu.getAvailableStorage()
                            + " jobStatus=" + job
                            + " | scheduler: " + sched
                            + " | untracked=" + stuck), false);
        }
        return 1;
    }

    /** How much of its job a CPU reports having done, for every command that lists CPUs. */
    private static String jobText(ICraftingCPU cpu) {
        var status = cpu.getJobStatus();
        return status == null ? "none" : status.progress() + "/" + status.totalItems();
    }

    /** One rig CPU and the label commands print for it. */
    private record CoreRef(String label, BlockPos pos) {
    }

    /** The rig's two CPUs, in the order {@code clearstuck} and {@code schedstate} report them. */
    private static List<CoreRef> rigCores() {
        return List.of(
                new CoreRef("AA", SchedulerRigCommand.aaCorePos()),
                new CoreRef("VANILLA", SchedulerRigCommand.vanillaCorePos()));
    }

    /**
     * The core block position a {@code craft}/{@code uiprobe} CPU name refers to: anything other than
     * {@code "vanilla"} is the scheduler CPU {@code "aa"}.
     */
    private static BlockPos coreFor(String cpuName) {
        return "vanilla".equalsIgnoreCase(cpuName)
                ? SchedulerRigCommand.vanillaCorePos()
                : SchedulerRigCommand.aaCorePos();
    }

    /**
     * The AE grid the command source is standing next to, or null when there is none within reach.
     *
     * <p>Uses AE2's own node lookup ({@code GridHelper.getExposedNode}), which replaces two earlier attempts
     * that were unreliable: walking the chunk map (protected API) and reading a chunk's block entity map
     * (returned nothing in practice). Falls back to looking for a scheduler core block within 24 blocks,
     * so the command works as long as the player is near the CPU even if the exposed-node walk fails.
     *
     * <p>Shared by {@code /schedulercore cpus} and {@code /schedulercore status}.
     */
    private static appeng.api.networking.IGrid findGridNear(ServerLevel level, BlockPos origin) {
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    var pos = origin.offset(dx, dy, dz);
                    for (var dir : net.minecraft.core.Direction.values()) {
                        var node = appeng.api.networking.GridHelper.getExposedNode(level, pos, dir);
                        if (node != null && node.getGrid() != null) {
                            return node.getGrid();
                        }
                    }
                }
            }
        }

        for (int r = 0; r <= 24; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        // Visit each position once, on the shell where it first appears. Without this the
                        // r-th pass rescans the whole (2r+1)^3 cube, so every position is probed ~25 times -
                        // about 100k block lookups per command for the 49^3 box that actually matters. The
                        // shell order (and therefore which grid wins) is unchanged.
                        if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) < r) {
                            continue;
                        }
                        var pos = origin.offset(dx, dy, dz);
                        if (!level.isLoaded(pos)) {
                            continue;
                        }
                        if (!(level.getBlockState(pos).getBlock() instanceof com.schedulercore.block
                                .SchedulerCoreBlock)) {
                            continue;
                        }
                        if (level.getBlockEntity(pos) instanceof CraftingBlockEntity be) {
                            var node = be.getMainNode().getNode();
                            if (node != null && node.getGrid() != null) {
                                return node.getGrid();
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * The status command: the task list, each task's progress,
     * and the CPU's remaining capacity.
     *
     * <p>A CPU running several orders stays observable through a command rather than
     * through new GUI, so this is the command that answers "what is this CPU doing right now" for every CPU
     * on the grid the player is standing next to - not just the rig's.
     *
     * <p><b>Remaining capacity is computed, not read.</b> {@code CraftingCPUCluster.getAvailableStorage()} is
     * badly named: it returns the CPU's <i>total</i> storage, never subtracting what is in use. The
     * remaining figure therefore has to be derived here - total minus the
     * scheduler's reservations - which is also exactly the number the admission ledger compares a new plan
     * against, so the command reports what the machine will actually decide.
     */
    private static int schedulerStatus(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        var grid = rigGrid(source);
        if (grid == null) {
            grid = findGridNear(level, BlockPos.containing(source.getPosition()));
        }
        if (grid == null) {
            source.sendFailure(Component.literal(
                    "[schedulercore] no AE grid found near you. Stand next to (or inside) your crafting CPU"
                            + " or its cables and run this again."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("=== crafting CPU status ==="), false);
        int index = 0;
        for (var cpu : grid.getCraftingService().getCpus()) {
            index++;
            final int i = index;
            if (!(cpu instanceof appeng.me.cluster.implementations.CraftingCPUCluster cluster)) {
                continue;
            }
            var state = com.schedulercore.scheduler.MultiJobState.forCluster(cluster);
            long capacity = cluster.getAvailableStorage();
            long reserved = state == null ? 0 : state.totalReservedBytes();
            int jobs = state == null ? 0 : state.size();
            final String title = "CPU #" + i + " " + (cpu.getName() == null ? "(unnamed)" : cpu.getName().getString())
                    + "  剩余容量 " + (capacity - reserved) + " / 总计 " + capacity
                    + "  任务 " + jobs + "  busy=" + cpu.isBusy();
            source.sendSuccess(() -> Component.literal(title), false);

            if (state == null || state.isEmpty()) {
                source.sendSuccess(() -> Component.literal("  (no scheduler orders)"), false);
                continue;
            }
            long owner = state.policy().currentOwner();
            for (var slot : state.slots()) {
                var job = slot.job();
                var out = state.view().finalOutput(job);
                var tracker = state.view().timeTracker(job);
                long remainingAmount = state.view().remainingAmount(job);
                // Item counts come from the job's own remaining amount and the total recorded at admission -
                // NOT from the tracker. AE2's ElapsedTimeTracker keeps progress as a fraction and expresses
                // it on a fixed 2^31-1 scale: getStartItemCount() is always Integer.MAX_VALUE and
                // getRemainingItemCount() is MAX_VALUE * (1 - progress). Reporting those raw numbers looks
                // like garbage ("644245120/2147483647") even though the tracker is perfectly correct, so the
                // percentage is the only thing worth taking from it.
                final String percent = tracker == null ? "?"
                        : Math.round(tracker.getProgress() * 100.0f) + "%";
                // Elapsed time comes back in NANOSECONDS, not ticks: ElapsedTimeTracker.updateTime() is
                //     elapsedTime += System.nanoTime() - lastTime;
                // Measured against a job known to have been running for ~20 s, the raw value was 2.01e10 -
                // i.e. exactly 20.1 s of nanoseconds. Treating it as ticks (the first guess) printed
                // "1005466180s", which is how this comment got here.
                final String elapsed = tracker == null ? "?"
                        : (tracker.getElapsedTime() / 1_000_000_000L) + "s";
                final String line = "  #" + slot.id()
                        + " " + (out == null ? "?" : out.what().getDisplayName().getString())
                        + "  进度 " + slot.delivered(remainingAmount) + "/" + slot.totalAmount()
                        + " (" + percent + ")"
                        + "  尚欠 " + remainingAmount
                        + "  已用时 " + elapsed
                        + "  预留 " + slot.reservedBytes() + "B"
                        + (state.view().suspended(job) ? "  [已挂起]" : "")
                        + (slot.id() == owner ? "  [当前时间片]" : "");
                source.sendSuccess(() -> Component.literal(line), false);
            }
        }
        return 1;
    }

    /**
     * Prints the exact payload AE2's crafting-status screen is built from, or performs the exact call its
     * suspend button makes.
     *
     * <p><b>Why this command exists.</b> The screen cannot be driven from RCON, so a fix to the data it
     * displays would otherwise have to be called "probably right" and verified by eye in a real game. This
     * instead builds the same request the menu builds and prints what comes back:
     *
     * <pre>
     * CraftingStatus.create(helper, logic)      // exactly what CraftingCPUMenu calls
     * logic.setJobSuspended(!logic.isJobSuspended())   // exactly what the suspend button calls
     * </pre>
     *
     * <p>The second one is the whole point of the suspend fix. AE2's button is not "suspend" and "resume",
     * it is one toggle whose direction comes from the server's answer to {@code isJobSuspended()}, so a
     * scheduler CPU that always answered "not suspended" could only ever be suspended - which is exactly what
     * happened on a real machine. Printing the answer before and after the toggle turns that into a check
     * anyone can run.
     *
     * <p>Seeding the helper with the job's output, everything the CPU is storing and everything its ledger
     * expects reproduces the rows the screen would show: stored (ingredients) plus active/pending (the
     * products, which only exist as expectations until a machine hands them back).
     */
    private static int uiProbe(CommandSourceStack source, String cpuName, boolean toggle) {
        ServerLevel level = source.getLevel();
        BlockPos core = coreFor(cpuName);
        if (!(level.getBlockEntity(core) instanceof CraftingBlockEntity be) || be.getCluster() == null) {
            source.sendFailure(Component.literal("[schedulercore] no formed CPU at " + core.toShortString()));
            return 0;
        }
        var logic = be.getCluster().craftingLogic;

        if (toggle) {
            boolean before = logic.isJobSuspended();
            logic.setJobSuspended(!before); // literally what CraftingCPUMenu.toggleScheduling() does
            boolean after = logic.isJobSuspended();
            source.sendSuccess(() -> Component.literal(
                    "[schedulercore] uiprobe toggle (" + cpuName + "): isJobSuspended "
                            + before + " -> " + after
                            + (before == after ? "  <-- STUCK: the button cannot change it" : "  (toggled)")),
                    true);
            return 1;
        }

        var helper = new appeng.menu.me.common.IncrementalUpdateHelper();
        var out = logic.getFinalJobOutput();
        if (out != null) {
            helper.addChange(out.what());
        }
        for (var entry : logic.getInventory().list) {
            helper.addChange(entry.getKey());
        }
        var waiting = new java.util.HashSet<appeng.api.stacks.AEKey>();
        logic.getAllWaitingFor(waiting);
        for (var key : waiting) {
            helper.addChange(key);
        }

        var status = appeng.menu.me.crafting.CraftingStatus.create(helper, logic);
        // The focus is what getStored filters the table by, so it belongs in this report: a per-order page
        // and the pooled CPU page are otherwise indistinguishable when two orders share their consumables,
        // which is exactly how a focus that outlived its order could hide here.
        var cluster0 = be.getCluster();
        var schedulerState = com.schedulercore.scheduler.MultiJobState.forCluster(cluster0);
        long focusedOrder = schedulerState == null
                ? com.schedulercore.scheduler.SchedulingPolicy.Decision.NONE
                : schedulerState.focusedSlotId();
        source.sendSuccess(() -> Component.literal("=== uiprobe " + cpuName + " (what the screen gets) ==="), false);
        source.sendSuccess(() -> Component.literal(
                "job=" + (out == null ? "none" : out.what().getDisplayName().getString())
                        + "  suspended=" + status.isSuspended()
                        + "  focusedOrder=" + (focusedOrder < 0 ? "none" : focusedOrder)
                        + "  storedKeys=" + logic.getInventory().list.size()
                        + "  waitingKeys=" + waiting.size()), false);
        source.sendSuccess(() -> Component.literal(
                "elapsed(ns)=" + status.getElapsedTime()
                        + "  scale/start=" + status.getStartItemCount()
                        + "  scale/remaining=" + status.getRemainingItemCount()), false);
        if (status.getEntries().isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "  (no rows - this is the 'only ingredients, no products' symptom)"), false);
        }
        for (var entry : status.getEntries()) {
            final String line = "  row serial=" + entry.getSerial()
                    + " " + entry.getWhat().getDisplayName().getString()
                    + "  stored=" + entry.getStoredAmount()
                    + "  active=" + entry.getActiveAmount()
                    + "  pending=" + entry.getPendingAmount();
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    /**
     * Prints the crafting CPU list exactly as the crafting-status screen receives it.
     *
     * <p>This is the per-order UI's acceptance check: the screen's rows <i>are</i> the entries of
     * {@code ICraftingService.getCpus()}, so if a scheduled order does not appear here it cannot appear on
     * screen either, and if it appears with the wrong name or progress, that is what the row will show.
     */
    private static int uiProbeRows(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        var grid = rigGrid(source);
        if (grid == null) {
            grid = findGridNear(level, BlockPos.containing(source.getPosition()));
        }
        if (grid == null) {
            source.sendFailure(Component.literal("[schedulercore] no AE grid found near you"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("=== crafting CPU list (what the status screen lists) ==="),
                false);
        int index = 0;
        for (var cpu : grid.getCraftingService().getCpus()) {
            index++;
            final int i = index;
            var status = cpu.getJobStatus();
            final boolean isOrder = cpu instanceof com.schedulercore.scheduler.SchedulerJobCpu;
            // For a per-order row, print the ORDER's id (what /schedulercore status and the cancel path use),
            // not the row's position in this list - confusing the two cost a verification round.
            final String kind = isOrder ? "ORDER id=" + ((com.schedulercore.scheduler.SchedulerJobCpu) cpu).slotId()
                    : "CPU  ";
            final String line = "  " + String.format("%-10s", kind) + " row#" + i
                    + " name=\"" + (cpu.getName() == null ? "?" : cpu.getName().getString()) + "\""
                    + " busy=" + cpu.isBusy()
                    + " storage=" + cpu.getAvailableStorage()
                    + " job=" + (status == null ? "none" : status.progress() + "/" + status.totalItems());
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    /** Puts one order in focus: exactly what clicking its row does, minus the click. */
    private static int uiProbeFocus(CommandSourceStack source, long slotId) {
        var state = rigSchedulerState(source, "aa");
        if (state == null) {
            source.sendFailure(Component.literal("[schedulercore] no scheduler jobs on the rig CPU"));
            return 0;
        }
        if (state.byId(slotId) == null) {
            source.sendFailure(Component.literal("[schedulercore] no order #" + slotId));
            return 0;
        }
        state.focus(slotId);
        source.sendSuccess(() -> Component.literal(
                "[schedulercore] focus -> order #" + slotId + " (the details pane now describes it)"), true);
        return uiProbe(source, "aa", false);
    }

    private static int uiProbeRelease(CommandSourceStack source) {
        var state = rigSchedulerState(source, "aa");
        if (state != null) {
            state.releaseFocus();
        }
        source.sendSuccess(() -> Component.literal("[schedulercore] focus released"), true);
        return 1;
    }

    /**
     * Calls the screen's cancel button: {@code CraftingCPUCluster.cancelJob()}.
     *
     * <p>With an order in focus the scheduler cancels only that order; with no focus it cancels all of them
     * (vanilla's button means "cancel this CPU"). Running this with and without a focus is how the per-order
     * cancel is verified without a client.
     */
    private static int uiProbeCancel(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        if (!(level.getBlockEntity(SchedulerRigCommand.aaCorePos()) instanceof CraftingBlockEntity be)
                || be.getCluster() == null) {
            source.sendFailure(Component.literal("[schedulercore] the rig CPU is not formed"));
            return 0;
        }
        var state = com.schedulercore.scheduler.MultiJobState.forCluster(be.getCluster());
        final String mode = state == null || state.focusedSlot() == null
                ? "no order focused -> cancels ALL orders"
                : "order #" + state.focusedSlotId() + " focused -> cancels ONLY that order";
        source.sendSuccess(() -> Component.literal("[schedulercore] cancel button: " + mode), true);
        be.getCluster().cancelJob();
        return 1;
    }

    /**
     * The grid the rig's own CPU sits on.
     *
     * <p>Command blocks and RCON have no player position, so a probe that located its grid "near the command
     * source" silently answered nothing when driven from a console. Anchoring to the rig's controller removes
     * that dependency and makes every probe addressable from RCON.
     */
    private static appeng.api.networking.IGrid rigGrid(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        if (level.getBlockEntity(SchedulerRigCommand.aaCorePos()) instanceof CraftingBlockEntity be
                && be.getCluster() != null) {
            return be.getCluster().getGrid();
        }
        return null;
    }

    private static com.schedulercore.scheduler.MultiJobState rigSchedulerState(CommandSourceStack source,
            String cpuName) {
        ServerLevel level = source.getLevel();
        BlockPos core = coreFor(cpuName);
        if (!(level.getBlockEntity(core) instanceof CraftingBlockEntity be) || be.getCluster() == null) {
            return null;
        }
        return com.schedulercore.scheduler.MultiJobState.forCluster(be.getCluster());
    }

    /**
     * Clears an untracked job that is keeping a CPU permanently busy.
     *
     * <p>Recovery path for saves written by an earlier build, whose job NBT is restored by vanilla into the
     * single-job field where nothing ever runs or clears it. See {@code StuckJobRepair}.
     */
    private static int clearStuck(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        for (var core : rigCores()) {
            if (!(level.getBlockEntity(core.pos()) instanceof CraftingBlockEntity be) || be.getCluster() == null) {
                continue;
            }
            final String result = com.schedulercore.scheduler.StuckJobRepair.clear(be.getCluster().craftingLogic);
            source.sendSuccess(() -> Component.literal(
                    "clearstuck " + core.label() + ": " + result), false);
        }
        source.sendSuccess(() -> Component.literal(
                "[schedulercore] clearstuck done. If a CPU is still busy, run 'schedstate' and send the output."),
                false);
        return 1;
    }

    /**
     * Turns the scheduler's per-tick trace on or off.
     *
     * <p>Off by default: one line per tick is what flooded a real game log with 13874 lines in an earlier
     * round. On, it is the only way to tell "this job is being served and pushing" from "this job is being
     * served and pushing nothing" - see {@code Trace} for why that distinction matters.
     */
    private static int setTrace(CommandSourceStack source, boolean on) {
        com.schedulercore.scheduler.Trace.setEnabled(on);
        source.sendSuccess(() -> Component.literal("[schedulercore] per-tick trace " + (on ? "ON" : "OFF")), true);
        return 1;
    }

    /** Reports the scheduler's own view of a CPU: how many jobs it holds, and who owns the current slice. */
    private static int schedulerState(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        for (var core : rigCores()) {
            if (!(level.getBlockEntity(core.pos()) instanceof CraftingBlockEntity be) || be.getCluster() == null) {
                continue;
            }
            var cluster = be.getCluster();
            var state = com.schedulercore.scheduler.MultiJobState.forCluster(cluster);
            if (state == null) {
                source.sendSuccess(() -> Component.literal(
                        "scheduler " + core.label() + ": no scheduler jobs (vanilla path, busy="
                                + cluster.isBusy() + ")"),
                        false);
            } else {
                source.sendSuccess(() -> Component.literal(
                        "scheduler " + core.label() + ": " + state.describe()), false);
            }
        }
        return 1;
    }

    private static int stalledFor(Run run) {
        int streak = 0;
        for (int i = run.samples.size() - 1; i > 0; i--) {
            if (run.samples.get(i).progress() == run.samples.get(i - 1).progress()) {
                streak++;
            } else {
                break;
            }
        }
        return streak;
    }

    private static void finish(MinecraftServer server, Run run, String why) {
        run.phase = Phase.DONE;
        run.note = why;
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("[schedulercore] " + run.cpuLabel + ": " + why + "\n" + render(run)), false);
    }

    private static int measureStatus(CommandSourceStack source) {
        Run run = active;
        if (run == null) {
            source.sendSuccess(() -> Component.literal("[schedulercore] no measurement recorded yet"), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal(render(run)), false);
        return 1;
    }

    /**
     * Puts a creative storage cell into the rig's drive and reports whether the grid storage service
     * picks it up.
     *
     * <p>This is the experiment that decides between two very different diagnoses: if a creative cell
     * makes {@code availableStacks} non-empty, the drive mounts fine and the earlier problem was only
     * that our cells held nothing; if it stays empty, the drive is genuinely not mounted at all and no
     * amount of cell juggling will help.
     */
    private static int creativeCellTrial(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        var beAny = level.getBlockEntity(SchedulerRigCommand.drivePos());
        if (!(beAny instanceof appeng.blockentity.storage.DriveBlockEntity drive)) {
            source.sendFailure(Component.literal("[schedulercore] no ME drive at "
                    + SchedulerRigCommand.drivePos().toShortString()));
            return 0;
        }
        var creative = BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(
                "ae2:creative_storage_cell"));
        if (creative == null) {
            source.sendFailure(Component.literal("[schedulercore] ae2:creative_storage_cell not found"));
            return 0;
        }
        for (int i = 0; i < drive.getInternalInventory().size(); i++) {
            drive.getInternalInventory().setItemDirect(i, new ItemStack(creative));
        }
        drive.saveChanges();

        if (!(level.getBlockEntity(SchedulerRigCommand.controllerPos())
                instanceof appeng.blockentity.networking.ControllerBlockEntity controller)) {
            source.sendFailure(Component.literal("[schedulercore] no controller"));
            return 0;
        }
        IGrid grid = controller.getMainNode().getGrid();
        if (grid == null) {
            source.sendFailure(Component.literal("[schedulercore] no grid"));
            return 0;
        }
        var counter = new KeyCounter();
        grid.getStorageService().getInventory().getAvailableStacks(counter);
        final int count = counter.size();
        StringBuilder sample = new StringBuilder();
        int shown = 0;
        for (var e : counter) {
            if (shown++ >= 5) {
                break;
            }
            sample.append(' ').append(e.getKey().getDisplayName().getString())
                    .append('x').append(e.getLongValue());
        }
        final String sampleText = sample.toString();
        source.sendSuccess(() -> Component.literal(
                "[schedulercore] creative cells installed -> grid storage stacks=" + count + sampleText), true);
        return 1;
    }

    /** Renders a {@link KeyCounter} as readable text; its own toString is just an object identity. */
    private static String describe(KeyCounter counter) {
        if (counter == null) {
            return "null";
        }
        var sb = new StringBuilder("[");
        int n = 0;
        for (var e : counter) {
            if (n++ > 0) {
                sb.append(", ");
            }
            sb.append(e.getKey().getDisplayName().getString()).append('x').append(e.getLongValue());
            if (n >= 6) {
                sb.append(", ...");
                break;
            }
        }
        return sb.append(']').toString();
    }

    /** Renders the per-tick progress trace and the summary the requirements ask for. */
    private static String render(Run run) {
        var head = new StringBuilder("measure(").append(run.cpuLabel).append(") phase=").append(run.phase)
                .append(" requested=").append(run.requested);
        if (!run.note.isEmpty()) {
            head.append(" note=\"").append(run.note).append('"');
        }
        if (run.samples.isEmpty()) {
            return head.append(" samples=0").toString();
        }
        int first = run.samples.get(0).tick();
        int last = run.samples.get(run.samples.size() - 1).tick();
        long finalProgress = run.samples.get(run.samples.size() - 1).progress();
        head.append(" progress=").append(finalProgress)
                .append(" ticks=").append(last - first + 1)
                .append(" samples=").append(run.samples.size());

        // The delta histogram is the diagnostic: a long run of 0 means the CPU was idle; a steady small
        // value means the machine cycle is the bottleneck, which is the correct behaviour.
        var histogram = new java.util.TreeMap<Long, Integer>();
        int zeroTicks = 0;
        long maxDelta = 0;
        for (int i = 1; i < run.samples.size(); i++) {
            long delta = run.samples.get(i).progress() - run.samples.get(i - 1).progress();
            if (delta <= 0) {
                zeroTicks++;
                continue;
            }
            histogram.merge(delta, 1, Integer::sum);
            maxDelta = Math.max(maxDelta, delta);
        }
        head.append(" maxDelta=").append(maxDelta).append(" idleTicks=").append(zeroTicks);
        head.append(" deltas=").append(histogram.isEmpty() ? "{}" : histogram.toString());
        return head.toString();
    }

    // ------------------------------------------------------------------ diagnostics

    /** Diagnoses why a craft may be refused: what the grid holds, and the state of both CPUs. */
    private static int gridStatus(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        var beAny = level.getBlockEntity(SchedulerRigCommand.controllerPos());
        if (!(beAny instanceof appeng.blockentity.networking.ControllerBlockEntity controller)) {
            source.sendFailure(Component.literal("[schedulercore] no controller block entity"));
            return 0;
        }
        IGrid grid = controller.getMainNode().getGrid();
        if (grid == null) {
            source.sendFailure(Component.literal("[schedulercore] controller is not on a grid"));
            return 0;
        }

        var counter = new KeyCounter();
        grid.getStorageService().getInventory().getAvailableStacks(counter);
        var stored = new StringBuilder();
        int shown = 0;
        for (var entry : counter) {
            if (shown++ >= 8) {
                stored.append(" ...");
                break;
            }
            stored.append(' ').append(entry.getKey().getDisplayName().getString())
                    .append('x').append(entry.getLongValue());
        }
        final int stackCount = counter.size();
        final String storedText = stored.length() == 0 ? " (empty)" : stored.toString();
        source.sendSuccess(() -> Component.literal("grid storage: stacks=" + stackCount + storedText), false);

        // The ME interface is the rig's intended item source; its contents are what to check first.
        if (level.getBlockEntity(SchedulerRigCommand.interfacePos())
                instanceof appeng.blockentity.misc.InterfaceBlockEntity iface) {
            var stack = iface.getInterfaceLogic().getStorage().getStack(0);
            final String ifaceText = stack == null ? "empty"
                    : stack.what().getDisplayName().getString() + "x" + stack.amount();
            source.sendSuccess(() -> Component.literal("interface slot 0: " + ifaceText), false);
        } else {
            source.sendSuccess(() -> Component.literal("interface: MISSING"), false);
        }

        // Cell capacity probe. This is the measurement that separates "the cell really has no room" from
        // "the cell is fine but nothing ever mounted it into the storage service".
        if (level.getBlockEntity(SchedulerRigCommand.drivePos())
                instanceof appeng.blockentity.storage.DriveBlockEntity drive) {
            var stack = drive.getInternalInventory().getStackInSlot(0);
            if (stack.getItem() instanceof appeng.api.storage.cells.IBasicCellItem cell) {
                final int bytes = cell.getBytes(stack);
                final int types = cell.getTotalTypes(stack);
                final String status = String.valueOf(drive.getCellStatus(0));
                source.sendSuccess(() -> Component.literal(
                        "cell: getBytes=" + bytes + " totalTypes=" + types + " driveStatus=" + status), false);
            } else {
                source.sendSuccess(() -> Component.literal(
                        "cell: slot 0 holds " + stack.getHoverName().getString()
                                + " which is not an IBasicCellItem"), false);
            }
            // Can the drive's own mounted inventory be written to, independently of the storage service?
            var cellInv = drive.getCellInventory(0);
            var probe = appeng.api.stacks.AEItemKey.of(
                    new ItemStack(BuiltInRegistries.ITEM.get(RigCrafts.INPUT_ITEM)));
            if (cellInv != null && probe != null) {
                long direct = cellInv.insert(probe, 64, appeng.api.config.Actionable.SIMULATE, null);
                source.sendSuccess(() -> Component.literal(
                        "cell direct insert simulate: " + direct + " (0 = mounted cell cannot store it)"), false);
                // Decisive test: put items INTO the mounted cell and see whether the grid storage service
                // picks them up. If it does not, the drive is simply not mounted, which is the real bug.
                cellInv.insert(probe, 64, appeng.api.config.Actionable.MODULATE, null);
                var after = new KeyCounter();
                grid.getStorageService().getInventory().getAvailableStacks(after);
                final int afterCount = after.size();
                final long seen = after.get(probe);
                source.sendSuccess(() -> Component.literal(
                        "after writing 64 into the cell: grid stacks=" + afterCount + " seen=" + seen
                                + "  (seen>0 means the drive IS mounted)"), false);
            }
        }

        // Can the crafting service actually see the pattern at all? This is the authoritative question:
        // "plan is simulated" with patternTimes=0 means no pattern was applied, i.e. the grid does not
        // know this item is craftable - not that materials are missing.
        var outKey = appeng.api.stacks.AEItemKey.of(
                new ItemStack(BuiltInRegistries.ITEM.get(RigCrafts.OUTPUT_ITEM)));
        if (outKey != null) {
            final boolean craftable = grid.getCraftingService().isCraftable(outKey);
            source.sendSuccess(() -> Component.literal(
                    "craftable(" + RigCrafts.OUTPUT_ITEM + ") = " + craftable
                            + "   <-- false means the provider never registered the pattern"), false);
        }

        // What the provider itself thinks it holds and where it may push.
        if (level.getBlockEntity(SchedulerRigCommand.providerPos())
                instanceof PatternProviderBlockEntity provider) {
            var logic = provider.getLogic();
            final int patternCount = logic.getAvailablePatterns().size();
            final String targets = provider.getTargets().toString();
            source.sendSuccess(() -> Component.literal(
                    "provider: patterns=" + patternCount + " targets=" + targets), false);
        }

        for (var cpu : grid.getCraftingService().getCpus()) {
            var status = cpu.getJobStatus();
            final String detail = "cpu busy=" + cpu.isBusy()
                    + " storage=" + cpu.getAvailableStorage()
                    + " job=" + (status == null ? "none" : status.progress() + "/" + status.totalItems());
            source.sendSuccess(() -> Component.literal(detail), false);
        }
        return 1;
    }
}
