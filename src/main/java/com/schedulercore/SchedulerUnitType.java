package com.schedulercore;

import net.minecraft.world.item.Item;

import appeng.block.crafting.ICraftingUnitType;

/**
 * Crafting unit type for the scheduler core block.
 *
 * <p>It deliberately provides <b>no</b> crafting storage and <b>no</b> co-processor threads
 * (an intentional design choice). Consequences, both intended:
 * <ul>
 *   <li>AE2's {@code CraftingCPUCluster} sums {@code getStorageBytes()} over the multiblock, so a core
 *       contributes 0 bytes and the CPU's storage capacity stays exactly what the vanilla storage
 *       blocks say. The remaining-capacity ledger we later add therefore measures against the vanilla
 *       number rather than one we invented.</li>
 *   <li>{@code getAcceleratorThreads() == 0} keeps {@code c + 1} (the per-tick budget) a pure function
 *       of the vanilla accelerator blocks, so installing a core cannot change throughput.</li>
 * </ul>
 *
 * <p>{@link #getItemFromType()} is not cosmetic: {@code CraftingBlockEntity.getItemFromBlockEntity()}
 * calls {@code getUnitBlock().type.getItemFromType()} and the value is handed to
 * {@code getMainNode().setVisualRepresentation(...)}. Returning null there would break the grid node's
 * visual representation.
 */
public final class SchedulerUnitType implements ICraftingUnitType {

    public static final SchedulerUnitType INSTANCE = new SchedulerUnitType();

    private SchedulerUnitType() {
    }

    @Override
    public long getStorageBytes() {
        return 0;
    }

    @Override
    public int getAcceleratorThreads() {
        return 0;
    }

    @Override
    public Item getItemFromType() {
        return SchedulerCore.SCHEDULER_CORE_BLOCK_ITEM.get();
    }
}
