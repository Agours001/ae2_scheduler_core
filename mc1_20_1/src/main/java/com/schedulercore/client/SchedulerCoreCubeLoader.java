package com.schedulercore.client;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import net.minecraftforge.client.model.geometry.IGeometryLoader;

/**
 * Loader for {@code "loader": "schedulercore:crafting_cube"} - the formed scheduler core block model.
 *
 * <p>All of the interesting behaviour lives in {@link SchedulerCoreCubeGeometry}; the JSON carries only
 * which texture fills which role, which is exactly how AE2's own crafting cube models are described to
 * their code-side provider.
 *
 * <p>The only 1.20.1 difference from the 1.21.1 twin is the package of {@code IGeometryLoader}:
 * Forge's {@code net.minecraftforge.client.model.geometry.IGeometryLoader}, whose
 * {@code read(JsonObject, JsonDeserializationContext)} contract is otherwise identical, including the
 * {@link JsonParseException} it is declared to throw.
 */
public final class SchedulerCoreCubeLoader implements IGeometryLoader<SchedulerCoreCubeGeometry> {

    @Override
    public SchedulerCoreCubeGeometry read(JsonObject jsonObject, JsonDeserializationContext context)
            throws JsonParseException {
        return SchedulerCoreCubeGeometry.INSTANCE;
    }
}
