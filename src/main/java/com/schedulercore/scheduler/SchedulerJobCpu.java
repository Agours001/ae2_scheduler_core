package com.schedulercore.scheduler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.network.chat.Component;

import appeng.api.config.CpuSelectionMode;
import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.stacks.GenericStack;
import appeng.me.cluster.implementations.CraftingCPUCluster;

/**
 * One scheduled order, presented to AE2 as a crafting CPU of its own.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Every row of the crafting-status screen <b>is an {@link ICraftingCPU}</b>: the menu builds the list by
 * iterating {@code ICraftingService.getCpus()} and reads each row's name, progress and job from that one
 * object ({@code CraftingStatusMenu}, verified with {@code javap}). Its identity is also what the list uses
 * for row serials - {@code WeakHashMap<ICraftingCPU, Integer>} keyed by object identity - and clicking a row
 * hands that same object back to {@code setCPU}. There is therefore no way to show, select or control one
 * order separately without giving it an object of its own: a server cannot add a row to that list by any
 * other means.
 *
 * <p>So this is the minimal version of that idea: a thin adapter that answers AE2's seven questions about one
 * order instead of about a whole CPU. It deliberately does <b>not</b> add a "remaining capacity" entry, does
 * not re-order or re-group the list, and does not try to fix the terminal's in-progress counter - those are
 * separate, optional pieces listed in the README's limitations.
 *
 * <h2>One instance per order, for the life of that order</h2>
 *
 * <p>Not a detail: the list assigns row serials by object identity, so a fresh adapter per tick would make
 * the list flicker and lose the selection. Instances are cached per order id by
 * {@link #forCluster} and dropped only when their order is gone.
 */
public final class SchedulerJobCpu implements ICraftingCPU {

    /** Every live adapter, keyed by cluster and then by order id. Weak on the cluster so a CPU can unload. */
    private static final Map<Object, Map<Long, SchedulerJobCpu>> REGISTRY =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private final CraftingCPUCluster cluster;
    private final long slotId;

    private SchedulerJobCpu(CraftingCPUCluster cluster, long slotId) {
        this.cluster = cluster;
        this.slotId = slotId;
    }

    /** The order this adapter speaks for. */
    public long slotId() {
        return slotId;
    }

    /** The real CPU the order runs on. */
    public CraftingCPUCluster cluster() {
        return cluster;
    }

    /**
     * The adapters for one cluster's live orders, creating and retiring them as orders come and go.
     *
     * <p>Called from the CPU-list path, i.e. once per menu update rather than once per tick, so the
     * bookkeeping stays off the tick path entirely.
     */
    public static List<SchedulerJobCpu> forCluster(CraftingCPUCluster cluster) {
        var state = MultiJobState.forCluster(cluster);
        if (state == null || state.isEmpty()) {
            REGISTRY.remove(cluster);
            return List.of();
        }
        Map<Long, SchedulerJobCpu> bySlot;
        synchronized (REGISTRY) {
            bySlot = REGISTRY.computeIfAbsent(cluster, c -> new LinkedHashMap<>());
        }
        synchronized (bySlot) {
            var result = new ArrayList<SchedulerJobCpu>(state.size());
            for (var slot : state.slots()) {
                var cpu = bySlot.get(slot.id());
                if (cpu == null) {
                    cpu = new SchedulerJobCpu(cluster, slot.id());
                    bySlot.put(slot.id(), cpu);
                }
                result.add(cpu);
            }
            // Orders that finished or were cancelled must not keep a row alive.
            bySlot.keySet().retainAll(result.stream().map(SchedulerJobCpu::slotId).toList());
            return result;
        }
    }

    // ------------------------------------------------------------------ ICraftingCPU

    /** An order that exists is work in progress; the list shows it as running. */
    @Override
    public boolean isBusy() {
        return state() != null;
    }

    /**
     * This order's own progress.
     *
     * <p>Mirrors {@code CraftingCPUCluster.getJobStatus()} but reads <b>this order's</b> elapsed-time tracker
     * rather than the CPU's resident job, so several rows can each show their own progress and ETA at the
     * same time. Note what the numbers mean: the tracker keeps progress as a fraction on a 2^31-1 scale,
     * and the elapsed time is in nanoseconds.
     */
    @Override
    public CraftingJobStatus getJobStatus() {
        var state = state();
        var slot = state == null ? null : state.byId(slotId);
        if (state == null || slot == null) {
            return null;
        }
        GenericStack output = state.view().finalOutput(slot.job());
        if (output == null) {
            return null;
        }
        var tracker = state.view().timeTracker(slot.job());
        long start = tracker == null ? 0 : tracker.getStartItemCount();
        long remaining = tracker == null ? 0 : tracker.getRemainingItemCount();
        long progress = Math.max(0, start - remaining);
        long elapsed = tracker == null ? 0 : tracker.getElapsedTime();
        return new CraftingJobStatus(output, start, progress, elapsed);
    }

    /**
     * Cancels just this order.
     *
     * <p>Routed through {@link CraftingCPUCluster#cancelJob()} so it takes the same path the screen's own
     * cancel button takes; the scheduler's hook on that method sees the focused order and cancels only it,
     * instead of everything on the CPU.
     */
    @Override
    public void cancelJob() {
        var state = state();
        if (state == null) {
            return;
        }
        state.focus(slotId);
        cluster.cancelJob();
        state.releaseFocus();
    }

    /** What this order reserved against the CPU's storage. */
    @Override
    public long getAvailableStorage() {
        var state = state();
        var slot = state == null ? null : state.byId(slotId);
        return slot == null ? 0 : slot.reservedBytes();
    }

    @Override
    public int getCoProcessors() {
        return cluster.getCoProcessors();
    }

    /**
     * The row's label: what the order produces, and how much.
     *
     * <p>Built with {@code Component.literal} on purpose: a name assembled from the item's own display
     * name needs no client-side language keys or other client resources.
     */
    @Override
    public Component getName() {
        var state = state();
        var slot = state == null ? null : state.byId(slotId);
        if (slot == null) {
            return Component.literal("?");
        }
        var output = state.view().finalOutput(slot.job());
        String item = output == null ? "?" : output.what().getDisplayName().getString();
        return Component.literal(item + " x" + slot.totalAmount());
    }

    @Override
    public CpuSelectionMode getSelectionMode() {
        return cluster.getSelectionMode();
    }

    @Override
    public String toString() {
        return "SchedulerJobCpu[slot=" + slotId + " " + getName().getString() + "]";
    }

    private MultiJobState state() {
        var state = MultiJobState.forCluster(cluster);
        return state == null || state.byId(slotId) == null ? null : state;
    }
}
