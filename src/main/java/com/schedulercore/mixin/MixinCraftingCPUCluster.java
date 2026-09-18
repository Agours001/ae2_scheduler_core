package com.schedulercore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.me.cluster.implementations.CraftingCPUCluster;

import com.schedulercore.scheduler.MultiJobState;
import com.schedulercore.scheduler.SchedulerScreenBridge;

/**
 * Makes a scheduler-managed crafting CPU report itself correctly to AE2's own admission gates.
 *
 * <p><b>{@code isBusy()} must mean "cannot accept another job", not "has any job".</b> The crafting confirm
 * dialog filters candidates with
 * {@code getAvailableStorage() >= plan.getUsedBytes() && !isBusy()}, so keeping vanilla's
 * "busy whenever a job exists" makes a second order impossible no matter what the submission path does -
 * the button lights up and immediately greys out again. Redefining busy as "no free capacity" is the only answer that keeps a CPU
 * with room left selectable, so that is what this reports.
 */
@Mixin(CraftingCPUCluster.class)
public abstract class MixinCraftingCPUCluster {

    /** Capacity that must remain free for the CPU to be offered as a candidate. */
    private static final long SCHEDULERCORE_MIN_FREE_BYTES = 8L;

    /**
     * Reports busy only when no further job could fit.
     *
     * <p>Returning true whenever the scheduler held a job would block every second
     * submission, so the answer is instead the honest question: is there room left over after the running
     * jobs' reservations?
     */
    @Inject(method = "isBusy", at = @At("HEAD"), cancellable = true)
    private void schedulercore$busyMeansNoFreeCapacity(CallbackInfoReturnable<Boolean> cir) {
        try {
            var self = (CraftingCPUCluster) (Object) this;
            var state = MultiJobState.forCluster(self);
            if (state == null) {
                return; // no scheduler involvement: vanilla's answer stands
            }
            if (state.isEmpty()) {
                // Nothing scheduled: vanilla's answer stands. Reporting "not busy" here also clears the
                // "unknown UI complained" case where a CPU with free space must stay selectable.
                if (self.getAvailableStorage() > 0) {
                    cir.setReturnValue(false);
                }
                return;
            }
            long freeBytes = self.getAvailableStorage() - state.totalReservedBytes();
            cir.setReturnValue(freeBytes < SCHEDULERCORE_MIN_FREE_BYTES);
        } catch (Throwable t) {
            // Never take the server down over a status query; vanilla's answer stands.
        }
    }

    /**
     * Makes the CPU's own row in the crafting-status list describe <b>the machine</b>, always.
     *
     * <p>AE2 builds that row out of {@code cluster.getJobStatus()}, which is itself built out of
     * {@code craftingLogic.getFinalJobOutput()} and {@code getElapsedTimeTracker()} - the two methods the
     * details pane also reads, and which therefore follow the row the player selected. That is right for the
     * pane and wrong for this row: with an order selected, the CPU's row reported <i>that order's</i> icon,
     * progress and ETA, so the machine appeared to have quietly changed what it was doing. Reported from a
     * real machine as "the CPU row follows the order I clicked".
     *
     * <p>So the row is answered directly instead, from the logic's aggregate: the Scheduler Core icon, the
     * progress of every order weighted by how much each asked for, and the oldest order's elapsed time. A row
     * is not a page, and this is the one row that must not follow the selection.
     */
    @Inject(method = "getJobStatus", at = @At("RETURN"), cancellable = true)
    private void schedulercore$reportCpuTotalsInList(
            CallbackInfoReturnable<appeng.api.networking.crafting.CraftingJobStatus> cir) {
        try {
            var self = (CraftingCPUCluster) (Object) this;
            var state = MultiJobState.forCluster(self);
            if (state == null || state.isEmpty()) {
                return; // no scheduler involvement: vanilla's answer stands
            }
            var status = ((SchedulerScreenBridge) (Object) self.craftingLogic).schedulercore$cpuStatus();
            if (status != null) {
                cir.setReturnValue(status);
            }
        } catch (Throwable t) {
            // A status query must never take the server down; vanilla's answer stands.
        }
    }

    /**
     * Makes the screen's cancel button cancel <b>the order it is showing</b> when one has been selected.
     *
     * <p>Vanilla's button ends in {@code CraftingCpuLogic.cancel()}, which the scheduler already hooks - and
     * there it has to cancel <i>every</i> order, because that same method is what a dismantled CPU runs on
     * its way out (a teardown must never leave orders behind). So the per-order case is handled one step
     * earlier, on the method only the GUI calls: if the screen has an order in focus, cancel just that one;
     * otherwise fall through to vanilla, which cancels the lot.
     *
     * <p>This is what makes a per-order row's cancel mean what it says, without touching the teardown path.
     */
    @Inject(method = "cancelJob", at = @At("HEAD"), cancellable = true)
    private void schedulercore$cancelFocusedOrder(CallbackInfo ci) {
        try {
            var self = (CraftingCPUCluster) (Object) this;
            var state = MultiJobState.forCluster(self);
            var focused = state == null ? null : state.focusedSlot();
            if (focused == null) {
                return; // no order selected: vanilla's cancel, which the scheduler turns into "cancel all"
            }
            if (((SchedulerScreenBridge) (Object) self.craftingLogic).schedulercore$cancelOrder(focused.id())) {
                ci.cancel();
            }
        } catch (Throwable t) {
            // Fall through to vanilla's cancel-all rather than leaving the player with a dead button.
            com.schedulercore.SchedulerCore.LOG.error(
                    "[schedulercore] could not cancel the focused order", t);
        }
    }
}
