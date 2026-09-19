package com.schedulercore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

import appeng.crafting.execution.CraftingCpuLogic;

import com.schedulercore.scheduler.SchedulerScreenBridge;

/**
 * The two job-persistence hooks, kept on the target because their target signatures are not portable.
 *
 * <p>{@code CraftingCpuLogic.writeToNBT} and {@code readFromNBT} gained a {@code HolderLookup.Provider}
 * parameter in 1.20.5, so the 1.21.1 and 1.20.1 targets cannot share one handler: Mixin matches a handler by
 * descriptor and does not let it drop a trailing argument, which is precisely what one would need to write a
 * single portable handler. (That is not a guess - the first attempt at this refactor did exactly that, and the
 * server refused to load a block entity with {@code InvalidInjectionException: Invalid descriptor}.)
 *
 * <p>So each target declares the hook in its own shape and forwards into
 * {@link SchedulerScreenBridge}, where the actual work lives and knows nothing about registries: it asks
 * {@code NbtSupport} for whatever this generation's serialiser needs.
 */
@Mixin(CraftingCpuLogic.class)
public abstract class MixinCraftingCpuLogicPersistence {

    @Inject(method = "writeToNBT", at = @At("TAIL"))
    private void schedulercore$saveJobs(CompoundTag output, HolderLookup.Provider registries, CallbackInfo ci) {
        ((SchedulerScreenBridge) (Object) this).schedulercore$saveJobs(output);
    }

    @Inject(method = "readFromNBT", at = @At("TAIL"))
    private void schedulercore$loadJobs(CompoundTag data, HolderLookup.Provider registries, CallbackInfo ci) {
        ((SchedulerScreenBridge) (Object) this).schedulercore$loadJobs(data);
    }
}
