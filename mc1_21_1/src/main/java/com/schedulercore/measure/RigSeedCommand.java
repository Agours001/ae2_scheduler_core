package com.schedulercore.measure;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.blockentity.crafting.CraftingBlockEntity;

import com.schedulercore.command.SchedulerRigCommand;

import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /schedulercore seed <item> <amount>} - puts an arbitrary item straight into a rig CPU's shared
 * inventory, without submitting any order.
 *
 * <p><b>Why this exists.</b> The rule that returns a cancelled order's leftovers
 * ({@code schedulercore$releaseKeysNotRequired}) only does anything when the pool holds a key that no
 * remaining order can reach. With a single-recipe rig every pooled key belongs to an order, so that branch
 * can never be reached and the fix cannot be observed at all - which would leave "it compiles" as the only
 * evidence. Seeding an unrelated key by hand makes the branch reachable on demand.
 *
 * <p>Deliberately a bare diagnostic: no state, no scheduling, and it only ever touches the CPU inventory,
 * so it cannot affect what the scheduler decides.
 */
public final class RigSeedCommand {

    private RigSeedCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("schedulercore")
                .then(Commands.literal("seed")
                        // word(), not string(): a resource location has no spaces, and string() would require
                        // the caller to quote it - which RCON's parser does not preserve.
                        .then(Commands.argument("item", StringArgumentType.word())
                                .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                        .executes(ctx -> seed(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "item"),
                                                LongArgumentType.getLong(ctx, "amount"))))))
                // Bare-literal variant: takes no namespaced token at all, so it can be driven from RCON
                // without depending on how that path handles a `namespace:path` argument.
                .then(Commands.literal("seedcobble")
                        .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                .executes(ctx -> seed(ctx.getSource(), "minecraft:cobblestone",
                                        LongArgumentType.getLong(ctx, "amount"))))));
    }

    private static int seed(CommandSourceStack source, String itemId, long amount) {
        var level = source.getLevel();
        BlockPos core = SchedulerRigCommand.aaCorePos();
        if (!(level.getBlockEntity(core) instanceof CraftingBlockEntity be) || be.getCluster() == null) {
            source.sendFailure(Component.literal("[schedulercore] no formed CPU at " + core.toShortString()));
            return 0;
        }
        var id = ResourceLocation.tryParse(itemId);
        var item = id == null ? null : BuiltInRegistries.ITEM.get(id);
        if (id == null || item == null || item == net.minecraft.world.item.Items.AIR) {
            source.sendFailure(Component.literal("[schedulercore] unknown item: " + itemId));
            return 0;
        }
        var key = AEItemKey.of(new ItemStack(item, 1));
        if (key == null) {
            source.sendFailure(Component.literal("[schedulercore] " + itemId + " has no AE item key"));
            return 0;
        }
        be.getCluster().craftingLogic.getInventory().insert(key, amount, Actionable.MODULATE);
        be.getCluster().markDirty();
        source.sendSuccess(() -> Component.literal(
                "[schedulercore] seeded " + amount + " x " + itemId + " into the CPU inventory"), true);
        return 1;
    }
}
