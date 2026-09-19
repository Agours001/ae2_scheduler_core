package com.schedulercore.rig;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.block.Block;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;

import appeng.api.config.Actionable;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.blockentity.crafting.PatternProviderBlockEntity;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.helpers.MachineSource;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;

import com.schedulercore.SchedulerCore;
import com.schedulercore.scheduler.MultiJobState;

import java.util.concurrent.Future;

/**
 * Acceptance rig for the 1.20.1 target: builds one working setup, submits real crafting orders through AE2's
 * own API, and prints what the shared scheduler thinks is happening.
 *
 * <h2>Why this target needs its own</h2>
 *
 * <p>The 1.21.1 rig is written against a much newer AE2 dev API, and this generation cannot run it. What is
 * needed here is deliberately small: build the multiblock, install one real pattern, submit orders, and print
 * the scheduler's own summary ({@link MultiJobState#describe()}) plus the CPU list the screen would draw. That
 * is enough to tell "this order was cancelled" from "this order is still here but never picked", which is the
 * distinction a report like "the second order takes over the first" cannot settle by itself.
 *
 * <h2>Everything goes through AE2's API</h2>
 *
 * <p>The pattern is encoded with {@code PatternDetailsHelper.encodeCraftingPattern} from the server's real
 * recipe, the job is planned with {@code ICraftingService.beginCraftingCalculation} and submitted with
 * {@code submitJob} to a specific CPU, so the CPU sees exactly what a player's terminal would ask of it.
 *
 * <p><b>The plan must not be waited for on the server thread.</b> AE2 computes it on a worker thread whose
 * completion path itself needs the server thread, so a blocking {@code get()} in the command handler deadlocks
 * the whole server. The command only starts the calculation and {@link #onServerTick} submits it when done.
 */
public final class RigCommand {

    /** Where the rig lives. Kept away from origin so it never lands on the world's spawn platform. */
    private static final BlockPos CELL = new BlockPos(0, 100, 0);

    private static final BlockPos STORAGE = new BlockPos(0, 101, 0);
    private static final BlockPos CORE = new BlockPos(0, 102, 0);
    private static final BlockPos UNIT = new BlockPos(0, 103, 0);
    private static final BlockPos PROVIDER = new BlockPos(1, 100, 0);
    /** Pattern provider pushes into its neighbours, so the rig gives it two assemblers to push into. */
    private static final BlockPos ASSEMBLER_A = new BlockPos(1, 101, 0);
    private static final BlockPos ASSEMBLER_B = new BlockPos(1, 99, 0);
    /** Holds the raw material: an interface's own storage is visible to the network, a bare grid's is not. */
    private static final BlockPos INTERFACE = new BlockPos(2, 100, 0);

    private static final ResourceLocation INPUT_ITEM = new ResourceLocation("minecraft:oak_planks");
    private static final ResourceLocation OUTPUT_ITEM = new ResourceLocation("minecraft:stick");

    /** The order being planned, if any. Never block on it; see the class note. */
    private static Future<ICraftingPlan> pendingPlan;
    private static IActionSource pendingSource;
    private static long pendingAmount;

    private RigCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("schedulercore")
                .then(Commands.literal("rig")
                        .then(Commands.literal("build").executes(ctx -> build(ctx.getSource())))
                        .then(Commands.literal("pattern").executes(ctx -> pattern(ctx.getSource())))
                        .then(Commands.literal("craft")
                                .then(Commands.argument("amount", LongArgumentType.longArg(1, 100_000))
                                        .executes(ctx -> craft(ctx.getSource(),
                                                LongArgumentType.getLong(ctx, "amount")))))
                        .then(Commands.literal("state")
                                .executes(ctx -> state(ctx.getSource(), CORE))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> state(ctx.getSource(),
                                                BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
                        .then(Commands.literal("probe")
                                .executes(ctx -> probe(ctx.getSource(), CORE))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> probe(ctx.getSource(),
                                                BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))));
    }

    public static void install() {
        MinecraftForge.EVENT_BUS.addListener(RigCommand::register);
        MinecraftForge.EVENT_BUS.addListener(RigCommand::onServerTick);
    }

    // ------------------------------------------------------------------ build

    private static final ResourceLocation CONTROLLER_FREE_CELL = new ResourceLocation("ae2", "creative_energy_cell");
    private static final ResourceLocation STORAGE_BLOCK = new ResourceLocation("ae2", "16k_crafting_storage");
    private static final ResourceLocation UNIT_BLOCK = new ResourceLocation("ae2", "crafting_unit");
    private static final ResourceLocation PROVIDER_BLOCK = new ResourceLocation("ae2", "pattern_provider");
    private static final ResourceLocation ASSEMBLER_BLOCK = new ResourceLocation("ae2", "molecular_assembler");
    private static final ResourceLocation INTERFACE_BLOCK = new ResourceLocation("ae2", "interface");

    /**
     * Builds the rig: a powered grid with one scheduler-led CPU and one pattern provider.
     *
     * <p>Blocks are looked up by registry id rather than through AE2's {@code AEBlocks} fields on purpose: the
     * field names are internal to AE2 and changed between generations, while the ids are what a player's
     * command would use and are stable.
     */
    private static int build(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        place(level, CELL, CONTROLLER_FREE_CELL);
        place(level, STORAGE, STORAGE_BLOCK);
        level.setBlockAndUpdate(CORE, SchedulerCore.SCHEDULER_CORE_BLOCK.get().defaultBlockState());
        place(level, UNIT, UNIT_BLOCK);
        place(level, PROVIDER, PROVIDER_BLOCK);
        place(level, ASSEMBLER_A, ASSEMBLER_BLOCK);
        place(level, ASSEMBLER_B, ASSEMBLER_BLOCK);
        place(level, INTERFACE, INTERFACE_BLOCK);

        source.sendSuccess(() -> Component.literal("[schedulercore] rig built: cell " + CELL.toShortString()
                + ", CPU " + STORAGE.toShortString() + ".." + UNIT.toShortString()
                + ", provider " + PROVIDER.toShortString() + " with assemblers, interface "
                + INTERFACE.toShortString()), true);
        source.sendSuccess(() -> Component.literal(
                "[schedulercore] now run: /schedulercore rig pattern, then rig craft <n>"), false);
        return 1;
    }

    private static void place(ServerLevel level, BlockPos pos, ResourceLocation blockId) {
        Block block = BuiltInRegistries.BLOCK.get(blockId);
        if (block == null) {
            return;
        }
        level.setBlockAndUpdate(pos, block.defaultBlockState());
    }

    // ------------------------------------------------------------------ pattern

    /** Encodes a real planks-to-sticks crafting pattern and puts it in the provider. */
    private static int pattern(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        ItemStack encoded = encodeStickPattern(level);
        if (encoded.isEmpty()) {
            source.sendFailure(Component.literal("[schedulercore] could not encode the pattern"));
            return 0;
        }
        if (!(level.getBlockEntity(PROVIDER) instanceof PatternProviderBlockEntity provider)) {
            source.sendFailure(Component.literal("[schedulercore] no pattern provider at " + PROVIDER.toShortString()));
            return 0;
        }
        InternalInventory patterns = provider.getLogic().getPatternInv();
        patterns.setItemDirect(0, encoded);
        provider.getLogic().updatePatterns();
        provider.saveChanges();
        source.sendSuccess(() -> Component.literal("[schedulercore] pattern installed: "
                + INPUT_ITEM + " -> " + OUTPUT_ITEM + " x4"), true);
        return 1;
    }

    /**
     * Builds an encoded crafting pattern for sticks from the server's own recipe manager.
     *
     * <p>The real recipe is used rather than hand-made ingredients because AE2's molecular assembler only
     * accepts patterns it can execute as a crafting recipe.
     */
    private static ItemStack encodeStickPattern(ServerLevel level) {
        ItemStack wanted = new ItemStack(BuiltInRegistries.ITEM.get(OUTPUT_ITEM), 4);

        ShapedRecipe found = null;
        for (CraftingRecipe recipe : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            if (!(recipe instanceof ShapedRecipe shaped)) {
                continue;
            }
            if (!ItemStack.isSameItemSameTags(shaped.getResultItem(level.registryAccess()), wanted)) {
                continue;
            }
            boolean allPlanks = !shaped.getIngredients().isEmpty();
            for (Ingredient ingredient : shaped.getIngredients()) {
                if (ingredient.isEmpty()) {
                    continue;
                }
                boolean matches = false;
                for (ItemStack candidate : ingredient.getItems()) {
                    if (candidate.is(BuiltInRegistries.ITEM.get(INPUT_ITEM))) {
                        matches = true;
                        break;
                    }
                }
                if (!matches) {
                    allPlanks = false;
                    break;
                }
            }
            if (allPlanks) {
                found = shaped;
                break;
            }
        }
        if (found == null) {
            return ItemStack.EMPTY;
        }

        // The pattern grid is 3x3, and unused cells must be explicit EMPTY stacks: AE2's encoder walks the
        // whole array and a null entry fails there.
        ItemStack[] inputs = new ItemStack[9];
        java.util.Arrays.fill(inputs, ItemStack.EMPTY);
        var ingredients = found.getIngredients();
        for (int y = 0; y < found.getHeight(); y++) {
            for (int x = 0; x < found.getWidth(); x++) {
                int index = y * found.getWidth() + x;
                if (index < ingredients.size() && !ingredients.get(index).isEmpty()) {
                    inputs[y * 3 + x] = ingredients.get(index).getItems()[0].copy();
                }
            }
        }
        return PatternDetailsHelper.encodeCraftingPattern(found, inputs, wanted, false, false);
    }

    // ------------------------------------------------------------------ orders

    /** Supplies the raw material and starts planning one order. */
    private static int craft(CommandSourceStack source, long amount) {
        ServerLevel level = source.getLevel();
        if (pendingPlan != null) {
            source.sendFailure(Component.literal("[schedulercore] a plan is still being computed"));
            return 0;
        }
        if (!(level.getBlockEntity(CORE) instanceof CraftingBlockEntity core) || core.getCluster() == null) {
            source.sendFailure(Component.literal("[schedulercore] no formed CPU at " + CORE.toShortString()
                    + " - run /schedulercore rig build first"));
            return 0;
        }
        CraftingCPUCluster cluster = core.getCluster();
        IGrid grid = cluster.getGrid();
        if (grid == null) {
            source.sendFailure(Component.literal("[schedulercore] the CPU is not on a grid"));
            return 0;
        }

        // Feed the ingredient straight into the CPU's own inventory, which is where AE2 pulls a job's
        // ingredients anyway. Going through the storage service would need a drive, a cell and the network's
        // storage to be mounted; none of that is needed to test scheduling.
        CraftingCpuLogic logic = cluster.craftingLogic;
        AEItemKey input = AEItemKey.of(new ItemStack(BuiltInRegistries.ITEM.get(INPUT_ITEM)));
        if (input == null) {
            source.sendFailure(Component.literal("[schedulercore] could not resolve the input item"));
            return 0;
        }
        // Feed the ingredient both ways: an interface's local storage counts as network storage, which is what
        // the *plan* looks at, and the CPU's own inventory is where the *execution* pulls ingredients from. The
        // plan is what silently degenerates if only the CPU inventory is seeded - it comes back "simulated",
        // which reads as "this grid cannot craft that item" and sends you looking at storage and power instead.
        // The order counts sticks; a craft turns 4 planks into 4 sticks, so the material to supply is a
        // quarter of it. Getting this wrong is silent: the planner just reports "missing ingredients".
        long planks = (amount + 3) / 4;
        logic.getInventory().insert(input, planks, Actionable.MODULATE);
        if (level.getBlockEntity(INTERFACE) instanceof appeng.blockentity.misc.InterfaceBlockEntity iface) {
            // Spread the material over every interface slot: one slot holds 64, so a single-slot supply is
            // silently clamped and the plan then comes back "simulated" for any order needing more.
            var storage = iface.getInterfaceLogic().getStorage();
            long remaining = planks;
            for (int slot = 0; slot < storage.size() && remaining > 0; slot++) {
                long put = Math.min(64, remaining);
                storage.setStack(slot, new appeng.api.stacks.GenericStack(input, put));
                remaining -= put;
            }
            iface.saveChanges();
            if (remaining > 0) {
                source.sendFailure(Component.literal("[schedulercore] the interface can only hold "
                        + (storage.size() * 64) + " planks, so orders above " + (storage.size() * 64 * 4)
                        + " sticks cannot be supplied (the CPU inventory is not enough: the planner does not"
                        + " read it)"));
                return 0;
            }
        } else {
            source.sendFailure(Component.literal("[schedulercore] no ME interface at " + INTERFACE.toShortString()
                    + " - run /schedulercore rig build first"));
            return 0;
        }

        AEItemKey output = AEItemKey.of(new ItemStack(BuiltInRegistries.ITEM.get(OUTPUT_ITEM)));
        MachineSource machineSource = new MachineSource(core);
        ICraftingSimulationRequester requester = new ICraftingSimulationRequester() {
            @Override
            public IActionSource getActionSource() {
                return machineSource;
            }

            @Override
            public IGridNode getGridNode() {
                // Not optional: with a null node AE2 skips every pattern and the plan comes back "simulated",
                // which looks exactly like "this grid cannot craft that item".
                return ((IActionHost) core).getActionableNode();
            }
        };
        pendingPlan = grid.getCraftingService().beginCraftingCalculation(
                level, requester, output, amount, CalculationStrategy.CRAFT_LESS);
        pendingSource = machineSource;
        pendingAmount = amount;
        source.sendSuccess(() -> Component.literal("[schedulercore] planning an order for " + amount + " x "
                + OUTPUT_ITEM + "; it is submitted on a later tick"), true);
        return 1;
    }

    /** Submits the finished plan - never on the thread that started it, and never by blocking on it. */
    private static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || pendingPlan == null || !pendingPlan.isDone()) {
            return;
        }
        Future<ICraftingPlan> plan = pendingPlan;
        IActionSource source = pendingSource;
        long amount = pendingAmount;
        pendingPlan = null;
        pendingSource = null;

        ICraftingPlan result;
        try {
            result = plan.get();
        } catch (Exception e) {
            SchedulerCore.LOG.warn("[schedulercore] rig: plan failed: {}", e.toString());
            return;
        }
        if (result == null || result.simulation()) {
            SchedulerCore.LOG.warn("[schedulercore] rig: no usable plan (simulated or missing ingredients)");
            return;
        }

        ServerLevel level = event.getServer().overworld();
        if (!(level.getBlockEntity(CORE) instanceof CraftingBlockEntity core) || core.getCluster() == null) {
            return;
        }
        CraftingCPUCluster cluster = core.getCluster();
        IGrid grid = cluster.getGrid();
        if (grid == null) {
            return;
        }
        ICraftingCPU target = cluster;
        var outcome = grid.getCraftingService().submitJob(result, null, target, false, source);
        if (outcome.successful()) {
            SchedulerCore.LOG.info("[schedulercore] rig: submitted an order for {} x {}", amount, OUTPUT_ITEM);
        } else {
            SchedulerCore.LOG.warn("[schedulercore] rig: submit rejected: {}", outcome.errorCode());
        }
    }

    // ------------------------------------------------------------------ probe

    /**
     * Prints why a craft can or cannot be planned: whether the grid knows the pattern, whether it can see the
     * raw material, and whether every block of the rig ended up on the same grid.
     *
     * <p>Written after a plan came back "simulated" with no other symptom: "cannot craft it" and "cannot find
     * the ingredient" look identical from the command's side, and this tells them apart.
     */
    private static int probe(CommandSourceStack source, BlockPos corePos) {
        ServerLevel level = source.getLevel();
        if (!(level.getBlockEntity(corePos) instanceof CraftingBlockEntity core) || core.getCluster() == null) {
            source.sendFailure(Component.literal("[schedulercore] no formed CPU at " + corePos.toShortString()));
            return 0;
        }
        CraftingCPUCluster cluster = core.getCluster();
        IGrid grid = cluster.getGrid();
        if (grid == null) {
            source.sendFailure(Component.literal("[schedulercore] the CPU is not on a grid"));
            return 0;
        }
        ICraftingService crafting = grid.getCraftingService();
        AEItemKey output = AEItemKey.of(new ItemStack(BuiltInRegistries.ITEM.get(OUTPUT_ITEM)));
        AEItemKey input = AEItemKey.of(new ItemStack(BuiltInRegistries.ITEM.get(INPUT_ITEM)));

        report(source, "grid craftable(" + OUTPUT_ITEM + ")", crafting.isCraftable(output));
        report(source, "patterns known for output", output == null ? -1 : crafting.getCraftingFor(output).size());
        report(source, "grid knows input item", input != null && crafting.isCraftable(input) ? "yes" : "n/a");

        CraftingCpuLogic logic = cluster.craftingLogic;
        long inCpu = input == null ? -1 : logic.getInventory().extract(input, Long.MAX_VALUE, Actionable.SIMULATE);
        report(source, "material in CPU inventory", inCpu);

        if (level.getBlockEntity(INTERFACE) instanceof appeng.blockentity.misc.InterfaceBlockEntity iface) {
            var stack = iface.getInterfaceLogic().getStorage().getStack(0);
            report(source, "material in interface storage", stack == null ? 0 : stack.amount());
            // InterfaceBlockEntity has no getGrid() on this generation; the node is the way to ask.
            IGridNode ifaceNode = ((IActionHost) iface).getActionableNode();
            report(source, "interface on the same grid", ifaceNode != null && ifaceNode.getGrid() == grid);
        } else {
            report(source, "interface block entity", "missing");
        }

        if (level.getBlockEntity(PROVIDER) instanceof PatternProviderBlockEntity provider) {
            report(source, "patterns in the provider", provider.getLogic().getAvailablePatterns().size());
            report(source, "provider on the same grid", provider.getGrid() == grid);
        } else {
            report(source, "provider block entity", "missing");
        }
        report(source, "CPU storage bytes available", cluster.getAvailableStorage());
        return 1;
    }

    private static void report(CommandSourceStack source, String what, Object value) {
        source.sendSuccess(() -> Component.literal("  " + what + ": " + value), false);
    }

    // ------------------------------------------------------------------ state

    /**
     * Prints the scheduler's own summary plus the CPU list the status screen is built from.
     *
     * <p>This is the whole point of the rig: {@link MultiJobState#describe()} names every admitted order and
     * which one owns the current tick, so "the first order never runs again" can be answered with "it is gone"
     * or "it is here and never picked" instead of a guess.
     */
    private static int state(CommandSourceStack source, BlockPos corePos) {
        ServerLevel level = source.getLevel();
        if (!(level.getBlockEntity(corePos) instanceof CraftingBlockEntity core) || core.getCluster() == null) {
            source.sendFailure(Component.literal("[schedulercore] no formed CPU at " + corePos.toShortString()
                    + " - pass the position of the scheduler core block, e.g. /schedulercore rig state 10 64 -30"));
            return 0;
        }
        CraftingCPUCluster cluster = core.getCluster();
        MultiJobState scheduler = MultiJobState.forCluster(cluster);
        source.sendSuccess(() -> Component.literal("scheduler: " + (scheduler == null
                ? "(none - this CPU has no scheduler jobs)"
                : scheduler.describe())), false);

        IGrid grid = cluster.getGrid();
        if (grid == null) {
            source.sendFailure(Component.literal("[schedulercore] the CPU is not on a grid"));
            return 0;
        }
        ICraftingService crafting = grid.getCraftingService();
        source.sendSuccess(() -> Component.literal("--- CPU list (what the screen lists) ---"), false);
        int index = 0;
        for (ICraftingCPU cpu : crafting.getCpus()) {
            index++;
            final int row = index;
            final String name = cpu.getName() == null ? "?" : cpu.getName().getString();
            source.sendSuccess(() -> Component.literal("  row#" + row + " name=\"" + name + "\" busy="
                    + cpu.isBusy() + " storage=" + cpu.getAvailableStorage()), false);
        }
        final int rows = index;
        source.sendSuccess(() -> Component.literal("  total rows: " + rows
                + " (one real CPU plus one per running order)"), false);
        return 1;
    }
}
