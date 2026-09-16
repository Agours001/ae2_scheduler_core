package com.schedulercore.client;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import net.neoforged.neoforge.client.model.geometry.IGeometryLoader;

/**
 * Loader for {@code "loader": "schedulercore:crafting_cube"} - the formed scheduler core block model.
 *
 * <p>All of the interesting behaviour lives in {@link SchedulerCoreCubeGeometry}; the JSON carries only
 * which texture fills which role, which is exactly how AE2's own crafting cube models are described to
 * their code-side provider.
 */
public final class SchedulerCoreCubeLoader implements IGeometryLoader<SchedulerCoreCubeGeometry> {

    @Override
    public SchedulerCoreCubeGeometry read(JsonObject jsonObject, JsonDeserializationContext context)
            throws JsonParseException {
        return SchedulerCoreCubeGeometry.INSTANCE;
    }
}
