package com.schedulercore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.nbt.CompoundTag;

import appeng.crafting.execution.CraftingCpuLogic;

import com.schedulercore.scheduler.SchedulerScreenBridge;

/**
 * The two job-persistence hooks for this target, where the serialiser takes no registry lookup.
 *
 * <p>This is the counterpart of the 1.21.1 target's {@code MixinCraftingCpuLogicPersistence}, and the reason
 * the two exist separately: {@code CraftingCpuLogic.writeToNBT}/{@code readFromNBT} gained a
 * {@code HolderLookup.Provider} parameter in 1.20.5, and Mixin matches a handler by descriptor without letting
 * it drop a trailing argument. A single portable handler is therefore impossible, while the work behind it is
 * not - it lives in {@link SchedulerScreenBridge} and is shared.
 *
 * <p>The registry lookup the newer generation needs is fetched from the CPU's level instead of being passed
 * in, which is what keeps the shared code from naming a type this generation does not have.
 */
@Mixin(CraftingCpuLogic.class)
public abstract class MixinCraftingCpuLogicPersistence {

    @Inject(method = "writeToNBT", at = @At("TAIL"))
    private void schedulercore$saveJobs(CompoundTag output, CallbackInfo ci) {
        ((SchedulerScreenBridge) (Object) this).schedulercore$saveJobs(output);
    }

    @Inject(method = "readFromNBT", at = @At("TAIL"))
    private void schedulercore$loadJobs(CompoundTag data, CallbackInfo ci) {
        ((SchedulerScreenBridge) (Object) this).schedulercore$loadJobs(data);
    }
}
