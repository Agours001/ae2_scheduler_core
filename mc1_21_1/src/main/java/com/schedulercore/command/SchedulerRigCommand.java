package com.schedulercore.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import appeng.block.crafting.AbstractCraftingUnitBlock;
import appeng.block.networking.ControllerBlock;
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.blockentity.crafting.PatternProviderBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;

import com.schedulercore.SchedulerCore;

import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /schedulercore rig ...} - builds and inspects the A/B measurement rig.
 *
 * <p>The rig is built from commands rather than hand-placed so it is reproducible: the test world under
 * {@code run/} is gitignored, and this command is what reconstructs the experiment anywhere.
 *
 * <p><b>Layout choice: no cables.</b> AE2's grid connects when two grid hosts are adjacent, so every
 * device is placed face-to-face with the ME Controller instead of being wired up. That avoids having to
 * place cable <i>parts</i> - cables live in the cable-bus block entity, not in a block state, so they
 * cannot be expressed with {@code /setblock} at all.
 *
 * <p><b>Why the two CPUs run along Y and not along X.</b> AE2 merges any adjacent crafting-unit blocks
 * into one multiblock. The first version of this rig laid both CPUs out along X, and because their
 * blocks touched, the two CPUs silently merged into a single 5-block CPU - {@code rig status} then
 * reported identical bounds for both. Laying them out perpendicular to each other on opposite sides of
 * the controller both separates them and keeps each one adjacent to the controller (so both are on the
 * grid). If the two bounds printed by {@code rig status} are ever equal again, the CPUs have merged.
 *
 * <pre>
 *   y=103  (0,103,0) AA end            &lt;- AA CPU carries the scheduler core here (the FAR block)
 *   y=102  (0,102,0) AA middle = plain crafting unit
 *   y=101  (0,101,0) AA near  = 16k crafting storage
 *   y=100  (0,100,0) ME CONTROLLER  --- (0,100,2) pattern provider --- (0,101,2) assembler A
 *                                                                \--- (0, 99,2) assembler B
 *   y= 99  (0, 99,0) vanilla CPU end   &lt;- baseline CPU has 16k storage in its middle
 *   y= 98  (0, 98,0) vanilla CPU middle = 16k CRAFTING STORAGE
 *   y= 97  (0, 97,0) vanilla CPU end
 * </pre>
 *
 * <p><b>This diagram was wrong once</b> (it claimed the core sat in the middle at y=102), which cost a
 * verification round: a test that replaced y=102 replaced a crafting unit with an identical crafting unit
 * and proved nothing. The truth is in {@link #buildCpu}: the core goes on the <b>far</b> block, and the
 * storage on the <b>near</b> block (the one touching the controller). If this comment and that method ever
 * disagree again, the method wins.
 */
public final class SchedulerRigCommand {

    /** Bottom-north-west corner the whole rig is laid out from. */
    public static final BlockPos ORIGIN = new BlockPos(0, 97, 0);

    private static final BlockPos CONTROLLER = ORIGIN.offset(0, 3, 0);
    /** Vertical CPUs: AA goes up from the controller, the vanilla baseline goes down. */
    private static final BlockPos AA_CORE = CONTROLLER.above();
    private static final BlockPos VANILLA_CORE = CONTROLLER.below();
    /**
     * The provider and everything chained off it sit one block EAST, directly touching the controller.
     *
     * <p><b>Why adjacency to the controller matters.</b> The controller acts as this network's energy
     * acceptor, so only the blocks touching it are powered - its six faces are the entire power budget. The
     * first layout hung the drive and interface off the pattern provider, which is two and three blocks
     * away from the controller: both nodes came up {@code powered=false}, so neither ever mounted its
     * inventory and the grid reported zero storage no matter what cells were installed. Keeping the chain
     * vertical, and its bottom block on the controller, fixes that within the six-face limit.
     */
    private static final BlockPos PROVIDER = CONTROLLER.offset(1, 0, 0);
    /**
     * The molecular assembler sits <b>directly on</b> the provider; that adjacency is what forms the pattern
     * group AE2 uses to decide the provider can actually run a pushed pattern. An earlier layout put the
     * drive in this slot, leaving the assembler two blocks away - the CPU then accepted a job and made no
     * progress at all, because nothing could receive the pattern.
     */
    private static final BlockPos ASM = PROVIDER.above();
    /** Drive and interface continue the same vertical chain, kept inside the controller's powered faces. */
    private static final BlockPos DRIVE = PROVIDER.above(2);
    private static final BlockPos INTERFACE = PROVIDER.above(3);

    private SchedulerRigCommand() {
    }

    /** Rig layout facts other classes need (measurement, diagnostics). */
    public static BlockPos aaCorePos() {
        return AA_CORE;
    }

    public static BlockPos vanillaCorePos() {
        return VANILLA_CORE;
    }

    public static BlockPos providerPos() {
        return PROVIDER;
    }

    public static BlockPos controllerPos() {
        return CONTROLLER;
    }

    public static BlockPos drivePos() {
        return DRIVE;
    }

    public static BlockPos interfacePos() {
        return INTERFACE;
    }

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("schedulercore")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("rig")
                        .executes(ctx -> status(ctx.getSource()))
                        .then(Commands.literal("build").executes(ctx -> build(ctx.getSource())))
                        .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                        .then(Commands.literal("clear").executes(ctx -> clear(ctx.getSource())))
                        .then(Commands.literal("forceload")
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 8))
                                        .executes(ctx -> forceload(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "radius")))))));
    }

    // ------------------------------------------------------------------ build

    private static int build(CommandSourceStack source) {
        ServerLevel level = source.getLevel();

        // Load the chunks first: setBlock on an unloaded chunk does nothing at all, which would leave a
        // silently half-built rig that is very confusing to debug.
        for (int cx = -1; cx <= 1; cx++) {
            for (int cz = -1; cz <= 1; cz++) {
                level.getChunk(ORIGIN.getX() + cx * 16, ORIGIN.getZ() + cz * 16);
            }
        }

        // Clear first so leftovers from an earlier build cannot merge into this one (AE2 would happily
        // absorb a stray crafting unit into the multiblock and silently change the CPU's shape).
        clearArea(level);

        // --- ME network: every device faces the controller, so no cables are needed ---
        set(level, CONTROLLER, AEBlocks.CONTROLLER.block().defaultBlockState());
        // Power: ae2:creative_energy_cell, on the controller's free south face.
        //
        // This placement is load-bearing and was got wrong twice by reasoning instead of looking. The
        // controller is what accepts and distributes energy here, and only the blocks touching it are on
        // the powered bus; so the power source must sit on one of its six faces, and putting any device
        // there instead silently overwrites it and takes the whole network offline (the controller then
        // reports state=offline and every other node reads powered=false, which looks exactly like "the
        // drive is present but mounts nothing").
        set(level, CONTROLLER.offset(0, 0, 1), AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState());

        // --- one pattern provider feeding two real molecular assemblers ---
        // Default state is push_direction=all (push to every adjacent face), which is what this rig
        // wants since the assemblers sit above and below the provider.
        set(level, PROVIDER, AEBlocks.PATTERN_PROVIDER.block().defaultBlockState());
        set(level, ASM, AEBlocks.MOLECULAR_ASSEMBLER.block().defaultBlockState());

        // --- storage: an ME drive with item cells, plus an ME interface as the item source ---
        // A crafting CPU cannot accept a job it has no ingredients for, and the CPU also needs somewhere
        // to put the finished output. The interface holds the raw material in its local storage, which is
        // exactly how a player feeds a real base, and it doubles as network storage.
        set(level, DRIVE, AEBlocks.DRIVE.block().defaultBlockState());
        com.schedulercore.measure.RigCrafts.installStorageCell(level, DRIVE, AEItems.ITEM_CELL_1K.get());
        set(level, INTERFACE, AEBlocks.INTERFACE.block().defaultBlockState());

        // --- the two CPUs, perpendicular so they cannot merge ---
        buildCpu(level, CONTROLLER, Direction.UP, true);
        buildCpu(level, CONTROLLER, Direction.DOWN, false);

        source.sendSuccess(() -> Component.literal(
                "[schedulercore] rig built at " + ORIGIN.toShortString()
                        + ". AA CPU (scheduler core) is ABOVE the controller; the vanilla baseline CPU is BELOW. "
                        + "Run '/schedulercore rig status' and confirm the two bounds differ."), true);
        return 1;
    }

    /**
     * Places one 1x1x3 crafting CPU growing from {@code controller} in {@code direction}.
     *
     * <p>The block touching the controller is the part that puts this CPU on the grid, so it goes down
     * last; the multiblock forms on that final neighbour update.
     *
     * <p><b>Both CPUs are structurally identical and both carry exactly one 16k storage block.</b> AE2's
     * {@code CraftingCPUCalculator.verifyInternalStructure} refuses to form a CPU whose blocks all report
     * zero storage, so a CPU made only of plain units and a scheduler core would never form at all - the
     * core contributes 0 bytes by design. Giving both sides the same single 16k storage keeps their
     * capacities equal, so no throughput difference can be blamed on different admission limits.
     *
     * <p>The only difference between the two CPUs is the far block: a scheduler core on the AA side, a
     * plain crafting unit on the baseline. Same shape, same storage, same co-processors.
     */
    private static void buildCpu(ServerLevel level, BlockPos controller, Direction direction,
            boolean withSchedulerCore) {
        BlockPos near = controller.relative(direction);
        BlockPos middle = near.relative(direction);
        BlockPos far = middle.relative(direction);

        // Place outside-in and finish with the block that touches the controller: that last placement
        // is the neighbour update that forms the cluster.
        if (withSchedulerCore) {
            set(level, far, SchedulerCore.SCHEDULER_CORE_BLOCK.get().defaultBlockState()
                    .setValue(AbstractCraftingUnitBlock.FORMED, false)
                    .setValue(AbstractCraftingUnitBlock.POWERED, false));
        } else {
            set(level, far, AEBlocks.CRAFTING_UNIT.block().defaultBlockState());
        }
        set(level, middle, AEBlocks.CRAFTING_UNIT.block().defaultBlockState());
        // The near block is the CPU's storage on both sides, so capacities match exactly.
        set(level, near, AEBlocks.CRAFTING_STORAGE_16K.block().defaultBlockState());
    }

    // ------------------------------------------------------------------ status

    private static int status(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        source.sendSuccess(() -> Component.literal("=== scheduler rig @ " + ORIGIN.toShortString() + " ==="), false);

        reportGrid(source, level);
        var aaBounds = reportCpu(source, level, "AA      (scheduler core)", AA_CORE);
        var vanillaBounds = reportCpu(source, level, "VANILLA (no core)      ", VANILLA_CORE);

        // The merge check: AE2 silently welds adjacent crafting-unit blocks into one multiblock, so two
        // identical bounds mean the rig is not measuring two CPUs at all.
        if (aaBounds != null && aaBounds.equals(vanillaBounds)) {
            source.sendSuccess(() -> Component.literal(
                    "!! the two CPUs share bounds (" + aaBounds + ") - they have MERGED into one multiblock"), false);
        }

        if (level.getBlockEntity(PROVIDER) instanceof PatternProviderBlockEntity) {
            source.sendSuccess(() -> Component.literal(
                    "provider : present at " + PROVIDER.toShortString() + "  (encode patterns before measuring)"),
                    false);
        } else {
            source.sendSuccess(() -> Component.literal("provider : MISSING at " + PROVIDER.toShortString()), false);
        }

        // Channel/power state of the storage path. A node that is not active never mounts its
        // inventories, which would look exactly like "the drive is there but holds nothing".
        reportNode(source, level, "drive    ", DRIVE);
        reportNode(source, level, "interface", INTERFACE);
        reportNeighbourhood(source, level);
        return 1;
    }

    /** Reports whether a block entity's grid node is active (i.e. powered and has a channel). */
    private static void reportNode(CommandSourceStack source, ServerLevel level, String label, BlockPos pos) {
        var be = level.getBlockEntity(pos);
        if (!(be instanceof appeng.blockentity.grid.AENetworkedBlockEntity networked)) {
            source.sendSuccess(() -> Component.literal(
                    label + ": no networked block entity at " + pos.toShortString()), false);
            return;
        }
        var node = networked.getMainNode().getNode();
        if (node == null) {
            source.sendSuccess(() -> Component.literal(label + ": node is NULL (not ready)"), false);
            return;
        }
        String detail = label + ": active=" + node.isActive()
                + " powered=" + node.isPowered()
                + " gridBooted=" + node.hasGridBooted();
        source.sendSuccess(() -> Component.literal(detail), false);
    }

    /**
     * Prints the blocks around the controller.
     *
     * <p>Added after getting the power layout wrong twice by reasoning about it instead of looking: the
     * grid is powered through whatever AE2 device happens to touch the controller, so the exact neighbours
     * are load-bearing and a single misplaced block silently takes the whole network offline.
     */
    private static void reportNeighbourhood(CommandSourceStack source, ServerLevel level) {
        var out = new StringBuilder("controller neighbours:");
        for (Direction d : Direction.values()) {
            BlockPos p = CONTROLLER.relative(d);
            var state = level.getBlockState(p);
            String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            out.append(' ').append(d.getName()).append('=').append(name);
        }
        source.sendSuccess(() -> Component.literal(out.toString()), false);
    }

    /**
     * Reports whether the ME network is actually up.
     *
     * <p>Worth checking explicitly: every measurement is meaningless if the grid is unpowered, and an
     * unpowered CPU still happily reports "formed". AE2 exposes the controller's own state property, and
     * an unformed controller reads as offline.
     */
    private static void reportGrid(CommandSourceStack source, ServerLevel level) {
        BlockState controllerState = level.getBlockState(CONTROLLER);
        String state = "?";
        if (controllerState.getBlock() instanceof ControllerBlock
                && controllerState.hasProperty(ControllerBlock.CONTROLLER_STATE)) {
            state = controllerState.getValue(ControllerBlock.CONTROLLER_STATE).toString();
        }
        final String controllerStateText = state;
        source.sendSuccess(() -> Component.literal(
                "controller: " + controllerStateText + " at " + CONTROLLER.toShortString()
                        + (("online".equals(controllerStateText)) ? "" : "   <-- network NOT online")),
                false);
    }

    /** Reports one CPU and returns its cluster bounds, or null when it is missing/not formed. */
    private static String reportCpu(CommandSourceStack source, ServerLevel level, String label, BlockPos core) {
        if (!(level.getBlockEntity(core) instanceof CraftingBlockEntity be)) {
            source.sendSuccess(() -> Component.literal(label + ": no crafting block entity at " + core.toShortString()),
                    false);
            return null;
        }
        var cluster = be.getCluster();
        if (cluster == null) {
            source.sendSuccess(() -> Component.literal(label + ": cluster NOT formed at " + core.toShortString()), false);
            return null;
        }
        String bounds = cluster.getBoundsMin().toShortString() + ".." + cluster.getBoundsMax().toShortString();
        String detail = "formed  bounds=" + bounds
                + "  storageCapacity=" + cluster.getAvailableStorage()
                + "  coProcessors=" + cluster.getCoProcessors()
                + "  busy=" + cluster.isBusy();
        source.sendSuccess(() -> Component.literal(label + ": " + detail), false);
        return bounds;
    }

    // ------------------------------------------------------------------ clear / forceload

    private static int clear(CommandSourceStack source) {
        clearArea(source.getLevel());
        source.sendSuccess(() -> Component.literal("[schedulercore] rig cleared"), true);
        return 1;
    }

    private static void clearArea(ServerLevel level) {
        for (BlockPos pos : BlockPos.betweenClosed(ORIGIN.offset(-3, -4, -3), ORIGIN.offset(3, 9, 5))) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static int forceload(CommandSourceStack source, int radius) {
        ServerLevel level = source.getLevel();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                level.setChunkForced((ORIGIN.getX() >> 4) + x, (ORIGIN.getZ() >> 4) + z, true);
            }
        }
        source.sendSuccess(() -> Component.literal(
                "[schedulercore] force-loaded radius " + radius + " around " + ORIGIN.toShortString()), true);
        return 1;
    }

    // ------------------------------------------------------------------ helpers

    private static void set(ServerLevel level, BlockPos pos, BlockState state) {
        // Flag 3 = notify neighbours and send to clients. Neighbour updates matter: AE2 forms crafting
        // CPUs and re-evaluates grid connections from neighbourChanged().
        level.setBlock(pos, state, Block.UPDATE_ALL);
    }
}
