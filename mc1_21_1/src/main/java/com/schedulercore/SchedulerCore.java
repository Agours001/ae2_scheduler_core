package com.schedulercore;

import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.schedulercore.block.SchedulerCoreBlock;
import com.schedulercore.block.SchedulerCoreBlockItem;
import com.schedulercore.item.SchedulerCoreItem;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;

import appeng.api.AECapabilities;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.blockentity.AEBaseBlockEntity;
import appeng.blockentity.crafting.CraftingBlockEntity;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Mod entry point: registries, the block entity type, and the one capability that AE2 will not
 * register for us.
 *
 * <p><b>Scope note.</b> This class is the mod entry point only: registries, the block entity type, and the
 * one capability that AE2 will not register for us. The multi-job time-slice scheduler itself lives in
 * {@code scheduler/} (pure policy logic) and {@code mixin/} (the three AE2 hooks); see the README for the
 * guarantees those are held to.
 */
@Mod(SchedulerCore.MOD_ID)
public final class SchedulerCore {

    public static final String MOD_ID = "schedulercore";
    public static final Logger LOG = LogUtils.getLogger();

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);

    // ------------------------------------------------------------------ registrations

    /** The installable component (also the item used by the in-place crafting-unit transform). */
    public static final DeferredItem<SchedulerCoreItem> SCHEDULER_CORE = ITEMS.register("scheduler_core",
            () -> new SchedulerCoreItem(new Item.Properties()));

    public static final DeferredBlock<SchedulerCoreBlock> SCHEDULER_CORE_BLOCK = BLOCKS.register(
            "scheduler_core_block", SchedulerCoreBlock::new);

    public static final DeferredItem<SchedulerCoreBlockItem> SCHEDULER_CORE_BLOCK_ITEM = ITEMS.register(
            "scheduler_core_block",
            () -> new SchedulerCoreBlockItem(SCHEDULER_CORE_BLOCK.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CraftingBlockEntity>> SCHEDULER_CORE_BE =
            BLOCK_ENTITIES.register("scheduler_core_block", SchedulerCore::createBlockEntityType);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CREATIVE_TAB = CREATIVE_TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + MOD_ID))
                    .icon(() -> new ItemStack(SCHEDULER_CORE_BLOCK_ITEM.get()))
                    .displayItems((params, output) -> {
                        output.accept(SCHEDULER_CORE.get());
                        output.accept(SCHEDULER_CORE_BLOCK_ITEM.get());
                    })
                    .build());

    public SchedulerCore(IEventBus modBus, ModContainer container) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        CREATIVE_TABS.register(modBus);
        modBus.addListener(SchedulerCore::registerCapabilities);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS
                .addListener(com.schedulercore.command.SchedulerRigCommand::register);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS
                .addListener(com.schedulercore.measure.RigMeasure::register);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS
                .addListener(com.schedulercore.measure.RigMeasure::onServerTick);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS
                .addListener(com.schedulercore.measure.RigCompare::register);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS
                .addListener(com.schedulercore.measure.RigCompare::onServerTick);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS
                .addListener(com.schedulercore.measure.RigSeedCommand::register);
        // The in-game guide (GuideME, shipped by AE2). Data-only: pages live under
        // assets/schedulercore/ae2guide/ and the page front matter maps items to them.
        com.schedulercore.client.SchedulerCoreGuide.register();
        // Hand the shared scheduler this generation's two version-specific capabilities: job serialisation
        // (which gained a registry lookup in 1.20.5) and crafting-job suspend (AE2 19.2.16+). See
        // NeoForgeSupport and, on the shared side, NbtSupport and SuspendSupport.
        com.schedulercore.support.NeoForgeSupport.install();
        LOG.info("{} loaded: scheduler core block + component; multi-job time-slice scheduler is armed.",
                MOD_ID);
    }

    /**
     * Registers AE2's in-world grid node host capability for the scheduler core block entity.
     *
     * <p><b>This is not optional, and it is the root cause of the historical "split CPU" bug.</b> AE2
     * registers {@code AECapabilities.IN_WORLD_GRID_NODE_HOST} only for block entity types it created
     * itself ({@code appeng.init.InitCapabilityProviders#register} walks AE2's own type list). A type
     * registered by another mod is not in that list, so {@code GridHelper.getExposedNode()} answers
     * {@code null} for it and <b>no neighbouring block can ever connect to the scheduler core</b>.
     *
     * <p>The result is asymmetric: the core can see its AE2 neighbours and connects to them (so it ends
     * up lit and in the network), while nothing can connect <i>towards</i> it - so the rest of the CPU
     * forms its own unpowered island grid and never merges. Registering the capability makes the block
     * behave exactly like an {@code ae2:crafting_unit} does.
     */
    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST, SCHEDULER_CORE_BE.get(),
                (blockEntity, context) -> (IInWorldGridNodeHost) blockEntity);
    }

    /**
     * Builds the block entity type for the scheduler core block.
     *
     * <p>We reuse AE2's own {@link CraftingBlockEntity} rather than subclassing it. That is deliberate:
     * {@code CraftingBlockEntity} carries the whole crafting-unit contract (multiblock membership,
     * {@code CraftingCubeModelData} neighbour connections for the formed model, {@code previousState}
     * restore, the {@code breakCluster} teardown path). Subclassing would buy us nothing at this stage
     * and would put our own class in the middle of AE2's teardown window - exactly where the old
     * {@code ClassCastException} crash lived.
     *
     * <p>This method must be called from the block entity type register (not at class-init time),
     * because it needs {@link #SCHEDULER_CORE_BLOCK} to already be bound and it registers the type back
     * onto that block via {@code setBlockEntity}. AE2's own crafting units do the same thing from
     * {@code AEBlockEntities#create}.
     *
     * <p>The {@link AtomicReference} self-reference is required because the factory handed to
     * {@code BlockEntityType.Builder} must be able to produce the very type we are still building.
     */
    private static BlockEntityType<CraftingBlockEntity> createBlockEntityType() {
        var typeHolder = new AtomicReference<BlockEntityType<CraftingBlockEntity>>();

        var block = SCHEDULER_CORE_BLOCK.get();
        var type = BlockEntityType.Builder.of(
                (pos, state) -> new CraftingBlockEntity(typeHolder.get(), pos, state), block).build(null);
        typeHolder.setPlain(type);

        // Makes AE2 recognise the item <-> block entity pairing (used by AE2's own item/part tooling).
        AEBaseBlockEntity.registerBlockEntityItem(type, block.asItem());
        // Wire the type back into the block so newBlockEntity() knows what to construct.
        block.setBlockEntity(CraftingBlockEntity.class, type, null, null);

        return type;
    }
}
