package com.schedulercore.block;

import net.minecraft.world.level.block.Block;

import appeng.block.crafting.CraftingBlockItem;

/**
 * Block item for the scheduler core block.
 *
 * <p>Extends AE2's {@link CraftingBlockItem} so that the "remove a CPU part" interaction (alternate-use
 * on a formed unit) works exactly like it does for AE2's own CPU parts, which relies on our
 * {@code data/schedulercore/recipe/scheduler_core_block_upgrade.json} ({@code ae2:crafting_unit_transform})
 * recipe being present.
 *
 * <p>Tooltips are intentionally <b>not</b> implemented here: {@code AEBaseBlockItem#appendHoverText} is
 * final and delegates to {@code Block#appendHoverText}, so the text lives on {@link SchedulerCoreBlock}.
 */
public class SchedulerCoreBlockItem extends CraftingBlockItem {

    public SchedulerCoreBlockItem(Block block, Properties properties) {
        super(block, properties);
    }
}
