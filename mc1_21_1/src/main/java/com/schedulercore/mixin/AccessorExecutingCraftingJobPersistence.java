package com.schedulercore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

import appeng.crafting.execution.ExecutingCraftingJob;

/**
 * Reaches {@link ExecutingCraftingJob}'s serialiser, whose signature gained a {@code HolderLookup.Provider} in
 * 1.20.5 - a type the 1.20.1 target cannot name, which is why this accessor is here and not in {@code common}.
 *
 * <p>Its sibling {@link AccessorExecutingCraftingJobSuspend} looks equally "1.21.1 only" but is not: it names
 * a member that only some builds of this target's own AE2 line have. The two are separate interfaces for
 * exactly that reason - the mixin plugin can withdraw the suspend one on an AE2 that has no such field, and if
 * they shared a file that withdrawal would take the save down with it.
 */
@Mixin(ExecutingCraftingJob.class)
public interface AccessorExecutingCraftingJobPersistence {

    /** The serialisation method is package-private, hence an {@code @Invoker}. */
    @Invoker("writeToNBT")
    CompoundTag schedulercore$writeToNBT(HolderLookup.Provider registries);
}
