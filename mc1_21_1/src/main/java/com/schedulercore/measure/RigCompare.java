package com.schedulercore.measure;

import java.util.concurrent.Future;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.security.IActionSource;
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.blockentity.crafting.PatternProviderBlockEntity;

import com.schedulercore.command.SchedulerRigCommand;

import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * L2 acceptance: run the <b>same order</b> on the scheduler CPU and on a plain AE2 CPU, one after the other,
 * and print both legs side by side.
 *
 * <h2>What it is for</h2>
 *
 * <p>This is the baseline this project is held to: a scheduler CPU must be
 * <i>indistinguishable</i> from a vanilla single-job CPU while it works, and must never be faster. The rig
 * has both CPUs built from the same blocks - same shape, same 16k storage, same zero co-processors - so the
 * only variable is the scheduler itself.
 *
 * <h2>Why the legs run one after the other and not at the same time</h2>
 *
 * <p>Both CPUs craft the same item into one network. If they ran together, an arriving stick would carry no
 * record of which CPU produced it, and the measurement would be unable to attribute progress - the whole
 * point of the exercise. Sampling them sequentially (and letting each finish before the next starts) makes
 * every arrival unambiguous. The cost is that they are not measured under identical world conditions
 * simultaneously; the benefit is that the numbers mean something, which matters more.
 *
 * <h2>What is measured, and why those things</h2>
 *
 * <ul>
 *   <li><b>Items that actually reached the network</b>, counted per tick - not the job's internal progress.
 *       Internal progress cannot be compared across the two paths: the tracker keeps a fraction on a
 *       {@code 2^31-1} scale, and the two CPUs' job objects are shaped
 *       differently. Network arrivals are the thing the player cares about and the only number both paths
 *       report the same way.</li>
 *   <li><b>Ticks to deliver the whole order</b> - the headline comparison.</li>
 *   <li><b>Longest gap between arrivals</b> - the "hands out work in bursts with a ~1 s pause" symptom from
 *       this symptom shows up here and nowhere else. A vanilla CPU fed by one assembler has a
 *       steady gap; a scheduler that stalls shows a long one.</li>
 * </ul>
 *
 * <p>The verdict is deliberately three-way. "Slower" is not the failure people expect: the interesting
 * failure is <b>faster</b>, because that means the scheduler is amplifying throughput (I3) - and that is
 * exactly what granting the full budget every tick would do.
 */
public final class RigCompare {

    /** Ticks to wait for AE2's asynchronous planner before abandoning a leg. */
    private static final int PLAN_TIMEOUT_TICKS = RigTiming.PLAN_TIMEOUT_TICKS;

    /** Safety cap per leg (10 minutes), so a job that never finishes cannot wedge the sampler for ever. */
    private static final int MAX_LEG_TICKS = 20 * 60 * 10;

    /** Ticks to let the grid storage snapshot catch up after supplying material. */
    private static final int SUPPLY_SETTLE_TICKS = 3;

    /** How far apart the two legs may be and still count as equivalent (percent). */
    private static final long EQUIVALENCE_PERCENT = 15;

    private enum Phase {
        IDLE, SUPPLY, PLANNING, SAMPLING, DONE
    }

    /** One CPU's run of the same order. */
    private static final class Leg {
        final String label;
        final BlockPos core;
        final long requested;
        int phaseTick;
        Future<ICraftingPlan> plan;
        IActionSource source;
        long startTick = -1;
        long ticksToFinish = -1;
        long baseline = -1;
        long arrived;
        long firstArrivalTick = -1;
        long lastArrivalTick = -1;
        long gap;
        long longestGap;
        long stallTicks;
        String note = "";

        Leg(String label, BlockPos core, long requested) {
            this.label = label;
            this.core = core;
            this.requested = requested;
        }
    }

    private static final class Comparison {
        final Leg[] legs;
        int index;
        Phase phase = Phase.SUPPLY;
        boolean printed;

        Comparison(long amount) {
            this.legs = new Leg[] {
                    new Leg("AA      (scheduler)", SchedulerRigCommand.aaCorePos(), amount),
                    new Leg("VANILLA (baseline) ", SchedulerRigCommand.vanillaCorePos(), amount)
            };
        }

        Leg current() {
            return legs[index];
        }
    }

    private static Comparison running;

    private RigCompare() {
    }

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("schedulercore")
                .then(Commands.literal("compare")
                        .then(Commands.argument("amount", LongArgumentType.longArg(1, 100_000))
                                .executes(ctx -> start(ctx.getSource(),
                                        LongArgumentType.getLong(ctx, "amount"))))));
    }

    private static int start(CommandSourceStack source, long amount) {
        if (running != null) {
            source.sendFailure(Component.literal(
                    "[schedulercore] a comparison is already running; wait for it to print"));
            return 0;
        }
        running = new Comparison(amount);
        source.sendSuccess(() -> Component.literal(
                "[schedulercore] compare: " + amount + " x " + RigCrafts.OUTPUT_ITEM
                        + " on AA (scheduler) then on VANILLA (baseline)."
                        + " Legs run one after the other so every arrival is attributable."), true);
        return 1;
    }

    // ------------------------------------------------------------------ tick state machine

    public static void onServerTick(ServerTickEvent.Post event) {
        var comparison = running;
        if (comparison == null || comparison.phase == Phase.DONE) {
            return;
        }
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        int now = server.getTickCount();
        var leg = comparison.current();

        if (!(level.getBlockEntity(leg.core) instanceof CraftingBlockEntity be) || be.getCluster() == null) {
            leg.note = " CPU not formed";
            finishLeg(comparison, server, now);
            return;
        }
        var cluster = be.getCluster();
        IGrid grid = cluster.getGrid();
        if (grid == null) {
            leg.note = " CPU has no grid";
            finishLeg(comparison, server, now);
            return;
        }
        if (cluster.isBusy()) {
            // Never overlap the two legs: a job left over from the previous leg (or from the test rig's own
            // state) would be credited to this one's arrival count.
            if (now - leg.phaseTick > MAX_LEG_TICKS) {
                leg.note = " CPU still busy after " + MAX_LEG_TICKS + " ticks";
                finishLeg(comparison, server, now);
            }
            return;
        }

        switch (comparison.phase) {
            case SUPPLY -> {
                long supplied = RigCrafts.supplyInput(level, SchedulerRigCommand.interfacePos(),
                        leg.requested * 4 + 4096);
                long seeded = RigCrafts.supplyDirectToCpu(cluster.craftingLogic, leg.requested * 4 + 4096);
                if (supplied <= 0 && seeded <= 0) {
                    leg.note = " could not supply material";
                    finishLeg(comparison, server, now);
                    return;
                }
                leg.phaseTick = now;
                comparison.phase = Phase.PLANNING;
            }
            case PLANNING -> {
                if (leg.plan == null) {
                    if (now - leg.phaseTick < SUPPLY_SETTLE_TICKS) {
                        return; // let the grid's storage snapshot include the material just supplied
                    }
                    if (!(level.getBlockEntity(SchedulerRigCommand.providerPos())
                            instanceof PatternProviderBlockEntity providerHost)) {
                        leg.note = " no pattern provider";
                        finishLeg(comparison, server, now);
                        return;
                    }
                    var planning = RigCrafts.beginPlan(grid, level, leg.requested, providerHost);
                    leg.plan = planning.plan();
                    leg.source = planning.source();
                    leg.phaseTick = now;
                    return;
                }
                if (now - leg.phaseTick > PLAN_TIMEOUT_TICKS) {
                    leg.note = " plan timed out";
                    finishLeg(comparison, server, now);
                    return;
                }
                if (!leg.plan.isDone()) {
                    return; // never block the server thread on AE2's planner
                }
                var outcome = RigCrafts.submitPlanned(leg.plan, grid, cluster, leg.source);
                leg.plan = null;
                if (!outcome.submitted()) {
                    leg.note = " submit failed: " + outcome.message();
                    finishLeg(comparison, server, now);
                    return;
                }
                leg.startTick = now;
                leg.baseline = RigCrafts.countOutput(grid);
                comparison.phase = Phase.SAMPLING;
                RigTiming.broadcast(server, "[schedulercore] compare: " + leg.label.strip() + " started ("
                        + leg.requested + " requested, network already holds " + leg.baseline + ")");
            }
            case SAMPLING -> {
                long arrivedNow = RigCrafts.countOutput(grid) - leg.baseline;
                if (arrivedNow > leg.arrived) {
                    leg.arrived = arrivedNow;
                    if (leg.firstArrivalTick < 0) {
                        leg.firstArrivalTick = now - leg.startTick;
                    }
                    leg.lastArrivalTick = now - leg.startTick;
                    leg.longestGap = Math.max(leg.longestGap, leg.gap);
                    leg.gap = 0;
                } else {
                    leg.gap++;
                    if (leg.arrived > 0) {
                        leg.stallTicks++;
                    }
                }
                boolean finished = cluster.getJobStatus() == null;
                boolean timedOut = now - leg.startTick > MAX_LEG_TICKS;
                if (finished || timedOut) {
                    leg.ticksToFinish = now - leg.startTick;
                    if (timedOut) {
                        leg.note = " TIMEOUT after " + leg.ticksToFinish + " ticks";
                    }
                    finishLeg(comparison, server, now);
                }
            }
            default -> {
            }
        }
    }

    private static void finishLeg(Comparison comparison, MinecraftServer server, int now) {
        var leg = comparison.current();
        RigTiming.broadcast(server, "[schedulercore] compare: leg finished - " + leg.label.strip()
                + " arrived=" + leg.arrived + "/" + leg.requested
                + " ticks=" + (leg.ticksToFinish < 0 ? "?" : leg.ticksToFinish)
                + " longestGap=" + leg.longestGap + leg.note);
        comparison.index++;
        if (comparison.index >= comparison.legs.length) {
            comparison.phase = Phase.DONE;
            report(server, comparison);
            running = null;
            return;
        }
        var next = comparison.current();
        next.phaseTick = now;
        comparison.phase = Phase.SUPPLY;
    }

    /**
     * Prints the side-by-side verdict.
     *
     * <p>Three outcomes, all of them actionable:
     * <ul>
     *   <li><b>equivalent</b> - within {@link #EQUIVALENCE_PERCENT}; the CPU behaves like vanilla.</li>
     *   <li><b>SLOWER</b> - the scheduler is losing time somewhere. Not a correctness failure by itself, but
     *       it means the two orders are not sharing the machine the way they should.</li>
     *   <li><b>FASTER</b> - throughput amplification, i.e. an I3 violation. Treated as the serious one:
     *       granting every job the full per-tick budget on every tick is measurably ~3x vanilla, which is
     *       exactly what this check exists to catch.</li>
     * </ul>
     */
    private static void report(MinecraftServer server, Comparison comparison) {
        var aa = comparison.legs[0];
        var vanilla = comparison.legs[1];
        var sb = new StringBuilder();
        sb.append("=== compare: ").append(comparison.legs[0].requested).append(" x ")
                .append(RigCrafts.OUTPUT_ITEM).append(" ===");
        for (var leg : comparison.legs) {
            sb.append('\n').append(leg.label).append(": arrived=").append(leg.arrived).append('/')
                    .append(leg.requested)
                    .append("  ticks=").append(leg.ticksToFinish < 0 ? "?" : leg.ticksToFinish)
                    .append("  firstArrival=t").append(leg.firstArrivalTick)
                    .append("  lastArrival=t").append(leg.lastArrivalTick)
                    .append("  longestGap=").append(leg.longestGap).append("t")
                    .append("  idleAfterFirst=").append(leg.stallTicks).append("t")
                    .append(leg.note);
        }
        if (aa.ticksToFinish > 0 && vanilla.ticksToFinish > 0) {
            long diff = aa.ticksToFinish - vanilla.ticksToFinish;
            long percent = Math.round(100.0 * diff / vanilla.ticksToFinish);
            String verdict;
            if (Math.abs(percent) <= EQUIVALENCE_PERCENT) {
                verdict = "EQUIVALENT (" + percent + "% vs baseline) - I2 holds";
            } else if (percent > 0) {
                verdict = "SLOWER by " + percent + "% - the scheduler is losing time";
            } else {
                verdict = "FASTER by " + (-percent) + "% - THROUGHPUT AMPLIFICATION (I3 violation)";
            }
            sb.append("\nverdict: ").append(verdict);
        } else {
            sb.append("\nverdict: not comparable (a leg did not finish)");
        }
        sb.append("\nnote: identical order on two identically built CPUs; legs ran one after the other so")
                .append(" every arrival is attributable to one of them.");
        RigTiming.broadcast(server, sb.toString());
    }
}
