package com.schedulercore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.crafting.execution.CraftingCpuLogic;
import appeng.me.cluster.implementations.CraftingCPUCluster;

import com.schedulercore.SchedulerCore;
import com.schedulercore.scheduler.MultiJobState;
import com.schedulercore.scheduler.SuspendSupport;

/**
 * Makes the crafting screen's suspend button mean what it says, on this target only.
 *
 * <p><b>Why this file is not in {@code common}.</b> {@code isJobSuspended} and {@code setJobSuspended} exist
 * only where AE2 can suspend a job - 19.2.16 and later, i.e. the 1.21.1 line. The 1.20.1 line has neither the
 * methods nor the button, so a shared hook could not even name its target, and there is nothing to port: the
 * feature simply does not exist there. {@code SuspendSupport} is how the shared scheduler reads the flag
 * without knowing which of the two worlds it is in.
 *
 * <h2>Why these hooks exist at all</h2>
 *
 * <p>Vanilla's {@code isJobSuspended()} is {@code job != null && job.suspended}, and the scheduler leaves that
 * field empty between ticks - so it always answered "not suspended". The screen asks the server to toggle, i.e.
 * it evaluates {@code setJobSuspended(!isJobSuspended())}, so every press of the button meant "suspend" and the
 * order could never be resumed: reported from a real machine as "挂起成功，但挂起后无法恢复".
 *
 * <p><b>Withdrawn when its target is absent.</b> {@code SchedulerCoreMixinPlugin} takes this whole class out
 * when this AE2 has no {@code isJobSuspended}/{@code setJobSuspended} at all - what 19.2.0 through 19.2.15 are
 * - so those versions load with the freeze feature off instead of dying in mixin application. Nothing here is
 * {@code require = 0}: either the members exist and these hooks must apply, or the plugin has already removed
 * the class. A silent no-op would quietly restore the one-way button this file exists to fix.
 *
 * <p><b>Which subject, and why it is not the slice owner.</b> The button belongs to a page. With an order's
 * row in focus it means that order; with no order in focus - the CPU's own row, or the CPU block's own screen,
 * which has no list at all - it means <b>every order on this CPU</b>, so the machine can be frozen and released
 * in one press. Reading the current slice owner instead would make the CPU page's button act on whichever order
 * happened to be served this tick, which is not something the page can explain to the player. The two hooks
 * have to agree about the subject or the button's next press would go the wrong way.
 */
@Mixin(CraftingCpuLogic.class)
public abstract class MixinCraftingCpuLogicSuspension {

    /** The CPU this logic belongs to; the same field the shared mixin shadows. */
    @Shadow
    CraftingCPUCluster cluster;

    @Inject(method = "isJobSuspended", at = @At("HEAD"), cancellable = true)
    private void schedulercore$reportSuspension(CallbackInfoReturnable<Boolean> cir) {
        try {
            var state = MultiJobState.forCluster(cluster);
            if (state == null) {
                return; // no scheduler involvement: vanilla's answer stands
            }
            var focused = state.focusedSlot();
            if (focused != null) {
                cir.setReturnValue(SuspendSupport.suspended(focused.job()));
            } else if (!state.isEmpty()) {
                cir.setReturnValue(state.allSuspended());
            }
        } catch (Throwable t) {
            SchedulerCore.LOG.error("[schedulercore] could not report suspension", t);
        }
    }

    @Inject(method = "setJobSuspended", at = @At("TAIL"))
    private void schedulercore$applySuspension(boolean suspended, CallbackInfo ci) {
        try {
            var state = MultiJobState.forCluster(cluster);
            if (state == null || state.isEmpty()) {
                return; // vanilla path already did the right thing
            }
            // Target the job the screen is describing, not "whoever owns the current slice". Those differ
            // exactly when it matters: suspending a job takes it out of the rotation, so the owner becomes a
            // different job (or nobody) immediately after, and the screen keeps describing the job that was
            // just suspended.
            var focused = state.focusedSlot();
            if (focused != null) {
                SuspendSupport.setSuspended(focused.job(), suspended);
                SchedulerCore.LOG.info("[schedulercore] job #{} suspended={}", focused.id(), suspended);
                return;
            }
            state.setAllSuspended(suspended);
            SchedulerCore.LOG.info("[schedulercore] all {} job(s) suspended={}", state.size(), suspended);
        } catch (Throwable t) {
            SchedulerCore.LOG.error("[schedulercore] could not change suspension", t);
        }
    }
}
