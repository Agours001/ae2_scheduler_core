package com.schedulercore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

import appeng.crafting.execution.ExecutingCraftingJob;

/**
 * The two {@link ExecutingCraftingJob} members this target can reach but the 1.20.1 target cannot.
 *
 * <p>They are separated from {@link AccessorExecutingCraftingJob} - which both targets share - because neither
 * exists on 1.20.1's AE2 15.x, each for its own reason:
 *
 * <ul>
 *   <li><b>{@code writeToNBT}</b> gained a {@code HolderLookup.Provider} parameter in 1.20.5, and 15.4.10's
 *       takes no argument at all, so not even the shape of the call is shared; 1.20.1 has its own accessor and
 *       its own {@code NbtSupport} implementation for it.</li>
 *   <li><b>{@code suspended}</b> arrived with crafting-job suspend in AE2 19.2.16, which is a release of this
 *       line only: the whole 1.20.1 line has neither the field nor the screen button.</li>
 * </ul>
 *
 * <p>Both are wired in {@code NeoForgeSupport}, which is also where the level's registry lookup is paired with
 * the job that needs it. Since 1.0.5 the mod declares {@code ae2 [19.2.16,)} - the release that added this flag
 * and the two methods that read it - so this interface is always applied on this target. That is what removed
 * the need for it to be a separate, withdrawable mixin.
 */
@Mixin(ExecutingCraftingJob.class)
public interface AccessorExecutingCraftingJobTarget {

    @Accessor("suspended")
    boolean schedulercore$suspended();

    @Accessor("suspended")
    void schedulercore$setSuspended(boolean suspended);

    /** The serialisation method is package-private, hence an {@code @Invoker}. */
    @Invoker("writeToNBT")
    CompoundTag schedulercore$writeToNBT(HolderLookup.Provider registries);
}
