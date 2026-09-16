package com.schedulercore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.menu.me.crafting.CraftingStatusMenu;

import com.schedulercore.scheduler.MultiJobState;
import com.schedulercore.scheduler.SchedulerJobCpu;
import com.schedulercore.scheduler.SchedulingPolicy;

/**
 * Makes a per-order row selectable: clicking it focuses that order and shows the real CPU's details for it.
 *
 * <h2>The problem this solves</h2>
 *
 * <p>{@code CraftingStatusMenu extends CraftingCPUMenu}, i.e. the crafting-status screen <b>is</b> a CPU
 * screen: the list on top, and the selected CPU's item table below. Selecting a row calls
 * {@code setCPU(ICraftingCPU)}, and the implementation stores that argument in a field typed
 * {@link CraftingCPUCluster} - so handing it a per-order entry is not merely unsupported, it cannot work.
 *
 * <p>So the click is intercepted: the order is put in focus in the scheduler state and the call is repeated
 * with the <b>real cluster</b>. The menu then shows the genuine CPU, while everything the menu asks about
 * that CPU - the job in the header, its progress, the suspend flag, the item table, and the cancel button -
 * is answered for the focused order, because {@code MultiJobState.currentSlot()} prefers the focused order.
 * That is what turns a row click into per-order control without touching a single line of GUI code: the screen
 * keeps behaving exactly like AE2's, it just describes a different subject.
 *
 * <h2>Why the re-entry flag</h2>
 *
 * <p>The repeated call would hit this same injection. The flag is the standard guard for that pattern (the
 * scheduler uses the same technique for its inventory dump); without it the second call would recurse for
 * ever.
 */
@Mixin(CraftingStatusMenu.class)
public abstract class MixinCraftingStatusMenu {

    @Unique
    private boolean schedulercore$forwarding;

    /**
     * The vanilla selection method, called again with the real CPU.
     *
     * <p>Declared as a shadow rather than called directly: it is {@code protected} in AE2's own package, and
     * a mixin class is compiled as an unrelated class - the shadow is what tells Mixin to resolve the call to
     * the target's method once the two are merged.
     */
    @Shadow
    protected abstract void setCPU(ICraftingCPU cpu);

    @Inject(method = "setCPU", at = @At("HEAD"), cancellable = true)
    private void schedulercore$selectOrderRow(ICraftingCPU cpu, CallbackInfo ci) {
        if (schedulercore$forwarding) {
            return; // our own repeated call: leave it to AE2
        }
        if (!(cpu instanceof SchedulerJobCpu order)) {
            // A real CPU was selected - i.e. the player went back to the CPU's own page.
            //
            // This case has to be handled too, and leaving it out was a bug: the focus set by a per-order row
            // would survive, so the CPU's page kept being filtered down to that one order, and which order it
            // showed depended on which row the player had clicked last. Reported from a real machine as "the
            // CPU page shows one order at random". Releasing the focus restores the CPU-wide view, which is
            // what that page means: every order's items, pooled.
            if (cpu instanceof CraftingCPUCluster cluster) {
                var state = MultiJobState.forCluster(cluster);
                if (state != null && state.focusedSlotId() != SchedulingPolicy.Decision.NONE) {
                    state.releaseFocus();
                    com.schedulercore.SchedulerCore.LOG.info(
                            "[schedulercore] crafting screen returned to the CPU view (focus released)");
                    // The table was showing one order's rows; it has to be re-sent for the pooled view.
                    ((com.schedulercore.scheduler.SchedulerScreenBridge) (Object) cluster.craftingLogic)
                            .schedulercore$refreshStatusRows();
                }
            }
            return;
        }
        try {
            var cluster = order.cluster();
            var state = MultiJobState.forCluster(cluster);
            if (state == null || state.byId(order.slotId()) == null) {
                // The order ended between the list being drawn and the click landing. Just show the CPU.
                ci.cancel();
                return;
            }
            state.focus(order.slotId());
            com.schedulercore.SchedulerCore.LOG.info(
                    "[schedulercore] crafting screen focused on order #{}", order.slotId());

            // Show the real CPU's pane, with the focused order behind every question it asks.
            schedulercore$forwarding = true;
            try {
                setCPU(cluster);
            } finally {
                schedulercore$forwarding = false;
            }

            // AE2's screen is incremental: it only re-sends a row when that key is reported as changed, and
            // selecting a CPU does not do that. Without this the client keeps the previous table, so every
            // order's page looked identical - the "several orders mixed together" report from a real machine.
            ((com.schedulercore.scheduler.SchedulerScreenBridge) (Object) cluster.craftingLogic)
                    .schedulercore$refreshStatusRows();
        } catch (Throwable t) {
            com.schedulercore.SchedulerCore.LOG.error(
                    "[schedulercore] could not focus a scheduled order", t);
        }
        ci.cancel();
    }
}
