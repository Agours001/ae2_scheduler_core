package com.schedulercore.client;

import com.schedulercore.SchedulerCore;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;

/**
 * Registers the {@code schedulercore:crafting_cube} model loader on the client.
 *
 * <p>Client-only by annotation: FML reads the {@code value = Dist.CLIENT} on the class file without
 * loading the class, so a dedicated server never touches this class or the client-only model classes it
 * references. That mirrors how AE2 keeps its own client model registration off the server.
 *
 * <p>No {@code bus} is given: {@code ModelEvent.RegisterGeometryLoaders} implements
 * {@code IModBusEvent}, and FML routes each listener to the right bus by inspecting its event type.
 */
@EventBusSubscriber(modid = SchedulerCore.MOD_ID, value = Dist.CLIENT)
public final class SchedulerCoreClientModels {

    /** Id used by {@code assets/schedulercore/models/block/scheduler_core_block_formed.json}. */
    public static final ResourceLocation CRAFTING_CUBE_LOADER = ResourceLocation
            .fromNamespaceAndPath(SchedulerCore.MOD_ID, "crafting_cube");

    private SchedulerCoreClientModels() {
    }

    @SubscribeEvent
    public static void registerGeometryLoaders(ModelEvent.RegisterGeometryLoaders event) {
        event.register(CRAFTING_CUBE_LOADER, new SchedulerCoreCubeLoader());
    }
}
