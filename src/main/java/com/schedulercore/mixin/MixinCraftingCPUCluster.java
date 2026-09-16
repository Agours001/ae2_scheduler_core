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
