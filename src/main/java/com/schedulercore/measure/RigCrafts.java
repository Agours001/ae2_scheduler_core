package com.schedulercore.measure;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;

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
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.PatternProviderBlockEntity;
import appeng.blockentity.misc.InterfaceBlockEntity;
import appeng.me.helpers.MachineSource;
import appeng.crafting.execution.CraftingCpuLogic;

import com.schedulercore.SchedulerCore;

/**
 * Prepares and drives one craft for measurement: encode a real crafting pattern, install it into the
 * rig's pattern provider, supply the raw material, and ask a specific CPU to craft the output.
 *
 * <p>Everything here is done through AE2's own API ({@code PatternDetailsHelper.encodeCraftingPattern}
 * and {@code ICraftingService.beginCraftingCalculation}/{@code submitJob}) rather than by faking NBT, so
 * the CPU sees exactly the same thing it would see if a player had encoded the pattern by hand.
 *
 * <p><b>Why a crafting pattern and not a processing pattern.</b> The rig pushes into molecular
 * assemblers, and an assembler only accepts patterns it can actually execute as a crafting recipe
 * ({@code IMolecularAssemblerSupportedPattern}). A processing pattern would be handed to a machine that
 * cannot run it, and the provider would simply never accept the push.
 */
public final class RigCrafts {

    /** Output item used by the rig: cheap, one-ingredient, and crafts in whole stacks. */
    public static final ResourceLocation OUTPUT_ITEM = ResourceLocation.parse("minecraft:stick");
    public static final ResourceLocation INPUT_ITEM = ResourceLocation.parse("minecraft:oak_planks");

    private RigCrafts() {
    }

    // ------------------------------------------------------------------ pattern

    /**
     * Builds an encoded crafting pattern for {@code output} from the server's own recipe manager.
     *
     * @return the encoded pattern stack, or an error string prefixed with {@code !}
     */
    public static Object encodeStickPattern(ServerLevel level) {
        Item outputItem = BuiltInRegistries.ITEM.get(OUTPUT_ITEM);
        ItemStack output = new ItemStack(outputItem, 4);

        // Find a 2x2-shaped planks -> sticks recipe in the loaded recipe manager. Using the real recipe
        // holder (instead of hand-built ingredients) is what makes the pattern molecular-assembler legal.
        RecipeHolder<CraftingRecipe> found = null;
        for (var holder : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            if (!(holder.value() instanceof ShapedRecipe shaped)) {
                continue;
            }
            if (!ItemStack.isSameItemSameComponents(shaped.getResultItem(level.registryAccess()), output)) {
                continue;
            }
            var ingredients = shaped.getIngredients();
            boolean allPlanks = !ingredients.isEmpty();
            for (Ingredient ingredient : ingredients) {
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
                found = new RecipeHolder<>(holder.id(), shaped);
                break;
            }
        }
        if (found == null) {
            return "! no planks -> sticks crafting recipe found";
        }

        // The pattern grid: a 3x3 crafting grid. Unused cells MUST be explicit EMPTY stacks, not null -
        // AECraftingPattern.encode maps over the array and a null entry blows up with an NPE.
        var inputs = new ItemStack[9];
        java.util.Arrays.fill(inputs, ItemStack.EMPTY);
        var ingredients = found.value().getIngredients();
        var shaped = (ShapedRecipe) found.value();
        int width = shaped.getWidth();
        int height = shaped.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                if (index < ingredients.size() && !ingredients.get(index).isEmpty()) {
                    inputs[y * 3 + x] = ingredients.get(index).getItems()[0].copy();
                }
            }
        }

        ItemStack pattern = PatternDetailsHelper.encodeCraftingPattern(found, inputs, output, false, false);
        if (pattern.isEmpty()) {
            return "! encodeCraftingPattern returned an empty stack";
        }
        return pattern;
    }

    /** Installs one pattern into the rig's provider, replacing whatever was in slot 0. */
    public static boolean installPattern(ServerLevel level, BlockPos providerPos, ItemStack pattern) {
        if (!(level.getBlockEntity(providerPos) instanceof PatternProviderBlockEntity provider)) {
            return false;
        }
        InternalInventory terminal = provider.getTerminalPatternInventory();
        if (terminal == null || terminal.size() == 0) {
            return false;
        }
        terminal.setItemDirect(0, pattern);
        provider.getLogic().updatePatterns();
        provider.saveChanges();
        return true;
    }

    // ------------------------------------------------------------------ materials

    /**
     * Puts the raw material into the rig's ME interface, which is how a player feeds a real base.
     *
     * <p>The interface's own local storage counts as network storage, so the crafting CPU finds these
     * items when it plans and executes the job. This is deliberately used instead of inserting into the
     * storage service: on a freshly built rig the drive's cells are mounted ({@code status=EMPTY},
     * inventory {@code DriveWatcher}) yet the storage service still reports zero bytes and refuses every
     * insert, so the service path is not usable as the rig's item source.
     */
    public static long supplyInput(ServerLevel level, BlockPos interfacePos, long amount) {
        if (!(level.getBlockEntity(interfacePos) instanceof InterfaceBlockEntity iface)) {
            SchedulerCore.LOG.warn("[rig] no ME interface at {}", interfacePos);
            return 0;
        }
        var key = AEItemKey.of(new ItemStack(BuiltInRegistries.ITEM.get(INPUT_ITEM)));
        if (key == null) {
            return 0;
        }
        var storage = iface.getInterfaceLogic().getStorage();
        long existing = 0;
        var current = storage.getStack(0);
        if (current != null) {
            existing = current.amount();
        }
        storage.setStack(0, new GenericStack(key, existing + amount));
        iface.saveChanges();
        return amount;
    }

    /**
     * Puts raw material straight into a crafting CPU's own inventory.
     *
     * <p>This deliberately bypasses the network storage service. On the rig the drive's cells mount
     * ({@code CellState.EMPTY} with a {@code DriveWatcher} inventory) and the ME interface holds items,
     * yet {@code getStorageService().getInventory()} still reports zero stacks and refuses every insert -
     * so the service path is not usable here. Seeding the CPU inventory is equivalent for a craft: AE2
     * pulls a job's ingredients into exactly this inventory, so having them there already satisfies both
     * the plan calculation and the execution.
     */
    public static long supplyDirectToCpu(CraftingCpuLogic logic, long amount) {
        var key = AEItemKey.of(new ItemStack(BuiltInRegistries.ITEM.get(INPUT_ITEM)));
        if (key == null) {
            return 0;
        }
        var inv = logic.getInventory();
        long before = inv.extract(key, Long.MAX_VALUE, Actionable.SIMULATE);
        inv.insert(key, amount, Actionable.MODULATE);
        long after = inv.extract(key, Long.MAX_VALUE, Actionable.SIMULATE);
        return after - before;
    }

    /** How much of {@code output} the network currently holds (used to watch arrivals). */
    public static long countOutput(IGrid grid) {
        var key = AEItemKey.of(new ItemStack(BuiltInRegistries.ITEM.get(OUTPUT_ITEM), 1));
        if (key == null) {
            return 0;
        }
        var counter = new appeng.api.stacks.KeyCounter();
        grid.getStorageService().getInventory().getAvailableStacks(counter);
        return counter.get(key);
    }

    // ------------------------------------------------------------------ craft request

    /** Starts a plan and hands back both the future and the action source the submission will need. */
    public record Planning(Future<ICraftingPlan> plan, IActionSource source) {
    }

    /**
     * Starts AE2's asynchronous craft calculation.
     *
     * <p><b>The caller must NOT block on the returned future.</b> The plan is computed by AE2's worker
     * thread, and that thread's completion path needs the server thread; calling {@code get()} from a
     * command handler therefore deadlocks the whole server. Poll {@code isDone()} from a tick handler.
     *
     * <p><b>The simulation requester must supply a grid node.</b> This is not optional and cost a lot of
     * time to find. {@code CraftingTreeNode#buildChildPatterns} does:
     *
     * <pre>
     * var gridNode = this.job.simRequester.getGridNode();
     * // If the node is null, we just skip patterns and let the request (likely) fail.
     * if (gridNode != null) { ... for (var details : craftingService.getCraftingFor(this.what)) ... }
     * </pre>
     *
     * If a lambda implements only {@code getActionSource()}, the interface's default {@code getGridNode()}
     * returns null, <b>every pattern is skipped</b>, and the planner reports a simulated plan with
     * {@code patternTimes=0} and the whole request listed as "missing". That looks exactly like "the grid
     * cannot craft this item", which sends debugging into storage, power and layout - none of which are the
     * problem.
     *
     * @param grid   the grid whose crafting service computes the plan
     * @param level  the level the calculation runs in
     * @param amount how many of {@link #OUTPUT_ITEM} to craft
     * @param host   the block entity the request is attributed to; it supplies both the grid node and the
     *               action source AE2 threads through the simulation state
     */
    public static Planning beginPlan(IGrid grid, ServerLevel level, long amount, IActionHost host) {
        ICraftingService crafting = grid.getCraftingService();
        var key = AEItemKey.of(new ItemStack(BuiltInRegistries.ITEM.get(OUTPUT_ITEM), 1));
        var machineSource = new MachineSource(host);
        ICraftingSimulationRequester requester = new ICraftingSimulationRequester() {
            @Override
            public IActionSource getActionSource() {
                return machineSource;
            }

            @Override
            public IGridNode getGridNode() {
                return host.getActionableNode();
            }
        };
        return new Planning(
                crafting.beginCraftingCalculation(level, requester, key, amount, CalculationStrategy.CRAFT_LESS),
                machineSource);
    }

    /** Result of submitting a finished plan to a specific CPU. */
    public record SubmitOutcome(boolean submitted, String message) {
    }

    /**
     * Submits an already-completed craft plan to a specific CPU.
     *
     * <p>Passing the CPU explicitly is what makes the A/B comparison meaningful: without it AE2 picks the
     * CPU itself, and the two rig CPUs would not reliably get one job each.
     */
    public static SubmitOutcome submitPlanned(Future<ICraftingPlan> planFuture, IGrid grid, ICraftingCPU targetCpu,
            IActionSource source) {
        ICraftingPlan plan;
        try {
            // Safe here: the caller has already established that the future is done, so this cannot block.
            plan = planFuture.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SubmitOutcome(false, "plan interrupted");
        } catch (ExecutionException e) {
            return new SubmitOutcome(false, "plan failed: " + e.getCause());
        }
        if (plan == null) {
            return new SubmitOutcome(false, "no plan (nothing craftable)");
        }
        if (plan.simulation()) {
            return new SubmitOutcome(false, "plan is simulated - missing ingredients");
        }
        // The source must not be null: CraftingCpuLogic.trySubmitJob calls src.player() without a guard,
        // so passing null crashes the server outright (verified the hard way).
        if (source == null) {
            return new SubmitOutcome(false, "internal error: no action source for submission");
        }
        var result = grid.getCraftingService().submitJob(plan, null, targetCpu, false, source);
        if (result.errorCode() != null) {
            return new SubmitOutcome(false, "submit rejected: " + result.errorCode());
        }
        return new SubmitOutcome(true, "submitted");
    }

    /** Fills the ME drive at {@code drivePos} with storage cells so the network has somewhere to keep items. */
    public static boolean installStorageCell(ServerLevel level, BlockPos drivePos,
            net.minecraft.world.item.Item cellItem) {
        if (!(level.getBlockEntity(drivePos) instanceof appeng.blockentity.storage.DriveBlockEntity drive)) {
            SchedulerCore.LOG.warn("[rig] no ME drive at {}", drivePos);
            return false;
        }
        var inv = drive.getInternalInventory();
        SchedulerCore.LOG.info("[rig] ME drive at {} reports {} slots", drivePos, inv.size());
        // Fill every slot: a mounted-but-empty drive slot and an unmapped slot look identical from the
        // outside, so this is also the experiment that tells the two apart.
        for (int i = 0; i < inv.size(); i++) {
            inv.setItemDirect(i, new ItemStack(cellItem));
        }
        drive.saveChanges();
        return true;
    }
}
