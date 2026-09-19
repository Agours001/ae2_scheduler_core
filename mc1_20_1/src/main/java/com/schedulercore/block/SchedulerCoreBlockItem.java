package com.schedulercore.block;

import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;

import appeng.block.crafting.CraftingBlockItem;

/**
 * Block item for the scheduler core block.
 *
 * <p>Extends AE2's {@link CraftingBlockItem} so that the "remove a CPU part" interaction (alternate-use on a
 * formed unit) works exactly like it does for AE2's own CPU parts, which relies on our
 * {@code data/schedulercore/recipe/scheduler_core_block_upgrade.json} ({@code ae2:crafting_unit_transform})
 * recipe being present.
 *
 * <p>Tooltips are intentionally <b>not</b> implemented here: {@code AEBaseBlockItem#appendHoverText} is final
 * and delegates to {@code Block#appendHoverText}, so the text lives on {@link SchedulerCoreBlock}.
 *
 * <p><b>1.20.1 note.</b> AE2 15.x's constructor takes a third argument that 19.2.x dropped: the item a formed
 * unit gives back when it is disassembled. The scheduler core component is what this block is made of, so that
 * is what is passed.
 */
public class SchedulerCoreBlockItem extends CraftingBlockItem {

    public SchedulerCoreBlockItem(Block block, Properties properties, java.util.function.Supplier<ItemLike> disassemblyExtra) {
        super(block, properties, disassemblyExtra);
    }
}
