package com.schedulercore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.nbt.CompoundTag;

import appeng.crafting.execution.ExecutingCraftingJob;

/**
 * Reaches this generation's job serialiser, which takes <b>no</b> argument at all.
 *
 * <p>That is the whole reason this file exists next to the 1.21.1 target's version of it: AE2 19.2.x's
 * {@code ExecutingCraftingJob.writeToNBT(CompoundTag, HolderLookup.Provider)} needs a registry lookup to
 * serialise keys, while 15.4.10's {@code writeToNBT()} takes nothing and returns the tag (checked against the
 * real jar: zero parameters). The shared save/load hooks cannot name either signature, so they go through
 * {@code NbtSupport} and this accessor is what {@code ForgeSupport} installs behind it.
 *
 * <p><b>What a missing accessor would cost.</b> {@code NbtSupport} would stay uninstalled, and saving a CPU
 * would throw the "no NBT support was installed" exception inside the save path - orders would stop persisting
 * with only a log line to show for it. That is why the accessor and its installation are written together.
 */
@Mixin(ExecutingCraftingJob.class)
public interface AccessorExecutingCraftingJobPersistence {

    /** The serialisation method is package-private, hence an {@code @Invoker}. */
    @Invoker("writeToNBT")
    CompoundTag schedulercore$writeToNBT();
}
