package com.schedulercore;

import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import appeng.api.networking.IInWorldGridNodeHost;
import appeng.blockentity.AEBaseBlockEntity;
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.capabilities.Capabilities;

import com.schedulercore.block.SchedulerCoreBlock;
import com.schedulercore.block.SchedulerCoreBlockItem;
import com.schedulercore.client.SchedulerCoreGuide;
import com.schedulercore.item.SchedulerCoreItem;
import com.schedulercore.support.ForgeSupport;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Mod entry point: registries, the block entity type, and the one capability AE2 will not register for us.
 *
 * <p>This is the 1.20.1 / Forge counterpart of the 1.21.1 entry point, and it differs where the loaders do.
 * Everything the shared scheduler needs from this generation is installed through {@link ForgeSupport}; the
 * multi-job time-slice scheduler itself lives in {@code scheduler/} and {@code mixin/}.
 *
 * <h2>Why the grid-node capability is attached here, rather than registered</h2>
 *
 * <p>On 1.21.1 the equivalent is one call to {@code RegisterCapabilitiesEvent.registerBlockEntity}. That call
 * does not exist on this generation: Forge 1.20.1's {@code RegisterCapabilitiesEvent} has only
 * {@code register(Class<T>)}, which declares a capability <i>type</i> and nothing about who provides it.
 *
 * <p>So on this generation the provider has to come from somewhere else, and AE2 15.x resolves it through the
 * capability either way: {@code GridHelper.getNodeHost} is
 * {@code level.getBlockEntity(pos).getCapability(Capabilities.IN_WORLD_GRID_NODE_HOST)}, i.e. it asks the block
 * entity (checked against the real 15.4.10 bytecode). Nothing in AE2's own block entity hierarchy implements
 * {@code ICapabilityProvider} - {@code AEBaseBlockEntity} is
 * {@code extends BlockEntity implements Nameable, ISegmentedInventory, Clearable} - so AE2 attaches the
 * capability from outside, and this mod has to do the same for the block entity type it registered itself.
 *
 * <p><b>Why that matters.</b> Without it, {@code getNodeHost} answers null for the scheduler core and no
 * neighbouring block can ever connect to it: the core would still see its AE2 neighbours (so it lights up and
 * joins the network) while nothing can connect <i>towards</i> it, and the rest of the CPU forms its own
 * unpowered island grid. That is the historical "split CPU" failure, and it is silent - the block looks fine.
 */
@Mod(SchedulerCore.MOD_ID)
public final class SchedulerCore {

    public static final String MOD_ID = "schedulercore";
    public static final Logger LOG = LogUtils.getLogger();

    /** Id of the capability this mod attaches to its own block entity type; see the class note. */
    private static final ResourceLocation GRID_HOST_CAPABILITY =
            new ResourceLocation(MOD_ID, "in_world_grid_node_host");

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MOD_ID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MOD_ID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);

    // ------------------------------------------------------------------ registrations

    /** The installable component (also the item used by the in-place crafting-unit transform). */
    public static final RegistryObject<SchedulerCoreItem> SCHEDULER_CORE = ITEMS.register("scheduler_core",
            () -> new SchedulerCoreItem(new Item.Properties()));

    public static final RegistryObject<SchedulerCoreBlock> SCHEDULER_CORE_BLOCK = BLOCKS.register(
            "scheduler_core_block", SchedulerCoreBlock::new);

    /**
     * The block's item. AE2 15.x's {@code CraftingBlockItem} takes a third argument - the item a formed unit
     * gives back when it is disassembled - which 19.2.x dropped; the scheduler core component is what this
     * block is made of, so that is what it returns.
     */
    public static final RegistryObject<SchedulerCoreBlockItem> SCHEDULER_CORE_BLOCK_ITEM = ITEMS.register(
            "scheduler_core_block",
            () -> new SchedulerCoreBlockItem(SCHEDULER_CORE_BLOCK.get(), new Item.Properties(),
                    () -> SCHEDULER_CORE.get()));

    public static final RegistryObject<BlockEntityType<CraftingBlockEntity>> SCHEDULER_CORE_BE =
            BLOCK_ENTITIES.register("scheduler_core_block", SchedulerCore::createBlockEntityType);

    public static final RegistryObject<CreativeModeTab> CREATIVE_TAB = CREATIVE_TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + MOD_ID))
                    .icon(() -> new ItemStack(SCHEDULER_CORE_BLOCK_ITEM.get()))
                    .displayItems((params, output) -> {
                        output.accept(SCHEDULER_CORE.get());
                        output.accept(SCHEDULER_CORE_BLOCK_ITEM.get());
                    })
                    .build());

    /**
     * <p><b>1.20.1 note.</b> This constructor takes no arguments and asks {@code FMLJavaModLoadingContext} for
     * the mod event bus, which this Forge version marks deprecated. Constructor injection - the
     * {@code IEventBus} parameter the 1.21.1 target uses - does <b>not</b> exist here: FML 47.4.10 looks the
     * constructor up by name and fails the whole mod with {@code NoSuchMethodException: <init>()}. That was
     * found by running it, and the deprecation points at a later Forge, not at this one.
     */
    public SchedulerCore() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        CREATIVE_TABS.register(modBus);
        // Generic listener: this is how a Forge mod subscribes to AttachCapabilitiesEvent<BlockEntity>.
        MinecraftForge.EVENT_BUS.addGenericListener(BlockEntity.class, SchedulerCore::attachGridNodeHost);
        // The in-game guide (GuideME, shipped by AE2). Data-only: pages live under
        // assets/schedulercore/ae2guide/ and the page front matter maps items to them.
        SchedulerCoreGuide.register();
        // Hand the shared scheduler this generation's two version-specific capabilities: job serialisation
        // (whose signature here takes no registry lookup at all) and crafting-job suspend, which AE2 only
        // gained in 19.2.16 - a 1.21.1-era release - so this generation installs none and every order is
        // simply runnable. See ForgeSupport and, on the shared side, NbtSupport and SuspendSupport.
        ForgeSupport.install();
        LOG.info("{} loaded: scheduler core block + component; multi-job time-slice scheduler is armed.",
                MOD_ID);
    }

    /**
     * Attaches AE2's in-world grid node host capability to this mod's block entity.
     *
     * <p>See the class note for why this generation needs it attached rather than registered, and why a
     * missing capability is the silent "split CPU" failure. The id check keeps this idempotent: a second
     * provider under the same id would be an error, and nothing else is expected to attach anything to our
     * type in the first place.
     */
    private static void attachGridNodeHost(AttachCapabilitiesEvent<BlockEntity> event) {
        if (!(event.getObject() instanceof CraftingBlockEntity blockEntity)
                || blockEntity.getType() != SCHEDULER_CORE_BE.get()) {
            return;
        }
        if (event.getCapabilities().containsKey(GRID_HOST_CAPABILITY)) {
            return;
        }
        event.addCapability(GRID_HOST_CAPABILITY, new ICapabilityProvider() {
            @Override
            public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
                if (capability != Capabilities.IN_WORLD_GRID_NODE_HOST) {
                    return LazyOptional.empty();
                }
                return LazyOptional.of(() -> (IInWorldGridNodeHost) blockEntity).cast();
            }
        });
    }

    /**
     * Builds the block entity type for the scheduler core block.
     *
     * <p>We reuse AE2's own {@link CraftingBlockEntity} rather than subclassing it. That is deliberate:
     * {@code CraftingBlockEntity} carries the whole crafting-unit contract (multiblock membership,
     * {@code CraftingCubeModelData} neighbour connections for the formed model, {@code previousState}
     * restore, the {@code breakCluster} teardown path). Subclassing would buy us nothing at this stage and
     * would put our own class in the middle of AE2's teardown window.
     *
     * <p>This method must be called from the block entity type register (not at class-init time), because it
     * needs {@link #SCHEDULER_CORE_BLOCK} to already be bound and it registers the type back onto that block
     * via {@code setBlockEntity}. The {@link AtomicReference} self-reference is required because the factory
     * handed to {@code BlockEntityType.Builder} must be able to produce the very type we are still building.
     */
    private static BlockEntityType<CraftingBlockEntity> createBlockEntityType() {
        AtomicReference<BlockEntityType<CraftingBlockEntity>> typeHolder = new AtomicReference<>();

        SchedulerCoreBlock block = SCHEDULER_CORE_BLOCK.get();
        BlockEntityType<CraftingBlockEntity> type = BlockEntityType.Builder.of(
                (BlockPos pos, BlockState state) -> new CraftingBlockEntity(typeHolder.get(), pos, state),
                block).build(null);
        typeHolder.setPlain(type);

        // Makes AE2 recognise the item <-> block entity pairing (used by AE2's own item/part tooling).
        AEBaseBlockEntity.registerBlockEntityItem(type, block.asItem());
        // Wire the type back into the block so newBlockEntity() knows what to construct.
        block.setBlockEntity(CraftingBlockEntity.class, type, null, null);

        return type;
    }
}
