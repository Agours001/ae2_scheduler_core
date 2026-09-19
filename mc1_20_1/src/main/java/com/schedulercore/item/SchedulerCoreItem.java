package com.schedulercore.item;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * The scheduler core component. It is installed into a crafting unit either by crafting the block
 * ({@code data/schedulercore/recipe/scheduler_core_block.json}) or by using it on an existing crafting unit
 * block in the world (the {@code ae2:crafting_unit_transform} recipe).
 *
 * <p><b>1.20.1 note.</b> The tooltip hook is the one signature that differs: this generation passes a
 * {@link Level} where 1.21.1 passes an {@code Item.TooltipContext} (checked against the real jar).
 */
public class SchedulerCoreItem extends Item {

    public SchedulerCoreItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.schedulercore.scheduler_core.line1")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.schedulercore.scheduler_core.line2")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
