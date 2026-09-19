package com.schedulercore.client;

import com.schedulercore.SchedulerCore;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Registers the {@code schedulercore:crafting_cube} model loader on the client.
 *
 * <p>Client-only by annotation: FML reads the {@code value = Dist.CLIENT} on the class file without
 * loading the class, so a dedicated server never touches this class or the client-only model classes it
 * references. That mirrors how AE2 keeps its own client model registration off the server.
 *
 * <p><b>Two 1.20.1 differences from the 1.21.1 twin, both silent if you get them wrong.</b>
 *
 * <ol>
 *   <li><b>The bus has to be stated.</b> NeoForge's {@code @EventBusSubscriber} routes a listener by
 *       inspecting its event type ({@code ModelEvent.RegisterGeometryLoaders} implements
 *       {@code IModBusEvent}), so the 1.21.1 class omits {@code bus}. Forge has no such sniffing and its
 *       {@code @Mod.EventBusSubscriber.bus} defaults to {@link Mod.EventBusSubscriber.Bus#FORGE}, so
 *       without {@code bus = Bus.MOD} the handler would be subscribed to the game bus, never receive the
 *       event, and the model would fail to load with a "model loader not found" parse error.</li>
 *   <li><b>{@code register} takes the path only.</b> Forge's
 *       {@code ModelEvent.RegisterGeometryLoaders#register(String, IGeometryLoader<?>)} builds the id as
 *       {@code new ResourceLocation(ModLoadingContext.get().getActiveNamespace(), name)}. FML sets that
 *       active namespace to this mod's id while it dispatches the event to our listener, so the constant
 *       is the bare path {@code "crafting_cube"} and the full id stays
 *       {@code schedulercore:crafting_cube}. There is no {@code ResourceLocation} overload here, which is
 *       why the 1.21.1 constant cannot be carried over as-is.</li>
 * </ol>
 */
@Mod.EventBusSubscriber(modid = SchedulerCore.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT)
public final class SchedulerCoreClientModels {

    /**
     * Path of the id used by {@code assets/schedulercore/models/block/scheduler_core_block_formed.json};
     * the {@code schedulercore} namespace is added by the event, see the class note.
     */
    public static final String CRAFTING_CUBE_LOADER = "crafting_cube";

    private SchedulerCoreClientModels() {
    }

    @SubscribeEvent
    public static void registerGeometryLoaders(ModelEvent.RegisterGeometryLoaders event) {
        event.register(CRAFTING_CUBE_LOADER, new SchedulerCoreCubeLoader());
    }
}
