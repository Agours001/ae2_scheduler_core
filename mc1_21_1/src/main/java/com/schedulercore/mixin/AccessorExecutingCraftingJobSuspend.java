package com.schedulercore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import appeng.crafting.execution.ExecutingCraftingJob;

/**
 * Reaches the crafting job's suspend flag, which exists only where AE2 can suspend a job: 19.2.16 and later
 * (PR #8635). The 1.20.1 line (15.x) has neither the field nor the screen button.
 *
 * <p><b>This interface can be absent at runtime, and that is the point.</b> {@link SuspendApiProbe} decides
 * whether this AE2 has the field, and {@code SchedulerCoreMixinPlugin} withdraws this mixin when it does not -
 * an accessor for a field that is not there is a hard failure at class-load time, and {@code require = 0} does
 * not reach field targets, so the only alternative would be refusing to load at all on those AE2 builds. What
 * is lost instead is one feature, the freeze button. {@code NeoForgeSupport} asks that same probe before it
 * installs a suspend implementation - by name of the probe rather than of this interface, since ordinary code
 * may not refer to a mixin class.
 */
@Mixin(ExecutingCraftingJob.class)
public interface AccessorExecutingCraftingJobSuspend {

    @Accessor("suspended")
    boolean schedulercore$suspended();

    @Accessor("suspended")
    void schedulercore$setSuspended(boolean suspended);
}
