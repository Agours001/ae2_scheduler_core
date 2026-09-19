package com.schedulercore.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

import appeng.crafting.execution.ExecutingCraftingJob;

/**
 * The two {@link ExecutingCraftingJob} members the 1.21.1 target can reach but the 1.20.1 target cannot.
 *
 * <p>They are separated from {@link AccessorExecutingCraftingJob} - which both targets share - for one reason
 * each, and both reasons are API-era facts rather than preferences:
 *
 * <ul>
 *   <li><b>{@code suspended}</b> exists only where AE2 can suspend a crafting job at all. That is 19.2.16 and
 *       later (PR #8635, a 1.21.1-era release); the whole 1.20.1 line (15.x) has neither the field nor the
 *       screen button. Keeping the accessor here is what lets the shared code stay silent about it - it reads
 *       the flag through {@code SuspendSupport}.</li>
 *   <li><b>{@code writeToNBT}</b> gained a {@code HolderLookup.Provider} parameter in 1.20.5. Naming that type
 *       in a shared interface would stop it compiling against 1.20.1, so it lives here and is reached through
 *       {@code NbtSupport}.</li>
 * </ul>
 *
 * <p>Both are wired in {@code NeoForgeSupport}, which is also where the level's registry lookup is paired with
 * the job that needs it.
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
