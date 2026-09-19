package com.schedulercore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.player.Player;

import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.menu.me.crafting.CraftingCPUMenu;

import com.schedulercore.scheduler.MultiJobState;

/**
 * Releases the per-order focus when the crafting screen closes.
 *
 * <p><b>Why this hook is on this class and not on {@code CraftingStatusMenu}.</b> The screen the player
 * actually sees is {@code CraftingStatusMenu}, which extends {@link CraftingCPUMenu} - but {@code removed()}
 * is declared <i>here</i>, in the parent, and {@code @Inject} resolves its target against the annotated
 * class's own methods. An injection naming an inherited method is not "found later"; it is a hard mixin
 * failure and the game refuses to start:
 *
 * <pre>
 * InvalidInjectionException: &#64;Inject annotation on ... could not find any targets matching 'removed' in
 * appeng/menu/me/crafting/CraftingStatusMenu
 * </pre>
 *
 * <p>Injecting on the declaring class covers every subclass, which is what the screen needs anyway.
 *
 * <p>Without this, the focus set by clicking a per-order row would outlive the screen: the next time the
 * player opened any CPU's screen it would describe an order they picked minutes ago, and a stale focus on a
 * retired order would leave the details pane stuck.
 */
@Mixin(CraftingCPUMenu.class)
public class MixinCraftingCPUMenu {

    /** The CPU this menu is showing; the field is private in AE2's own class, hence the shadow. */
    @Shadow
    private CraftingCPUCluster cpu;

    @Inject(method = "removed", at = @At("TAIL"))
    private void schedulercore$releaseOrderFocus(Player player, CallbackInfo ci) {
        try {
            if (cpu == null) {
                return;
            }
            var state = MultiJobState.forCluster(cpu);
            if (state != null) {
                state.releaseFocus();
            }
        } catch (Throwable t) {
            com.schedulercore.SchedulerCore.LOG.error("[schedulercore] could not release the order focus", t);
        }
    }
}
