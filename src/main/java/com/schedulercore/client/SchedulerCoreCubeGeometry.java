package com.schedulercore.client;

import java.util.function.Function;

import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;

import appeng.client.render.crafting.LightBakedModel;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IUnbakedGeometry;

/**
 * Bakes the formed scheduler core into AE2's own crafting-cube shell model.
 *
 * <p><b>Why this class exists.</b> AE2 renders a formed crafting unit with
 * {@code appeng.client.render.crafting.CraftingCubeBakedModel}, which reads the neighbour connection
 * set out of the block entity's {@code ModelData} and grows/shrinks the inner cube plus draws the
 * corner/edge "ring" pieces accordingly. That is the mechanism that makes a crafting storage butt
 * seamlessly against the units next to it.
 *
 * <p>AE2 does not expose that geometry through a model-JSON loader id: its formed models are literally
 * {@code {}} and the real model is injected from code via {@code appeng.hooks.BuiltInModelHooks}, whose
 * lookup starts with {@code if (!"ae2".equals(id.getNamespace())) return null;}. That hook therefore
 * cannot serve another mod's namespace, so we mirror the loader instead: a plain NeoForge geometry loader
 * ({@code schedulercore:crafting_cube}) that bakes into AE2's <i>own</i> {@link LightBakedModel}. The
 * geometry, the {@code ModelData} contract, the powered/emissive handling and the connection ring are
 * then literally AE2's code, not a re-implementation.
 *
 * <p>Texture roles, identical to what {@code CraftingUnitModelProvider} feeds AE2's storage units:
 * <ul>
 *   <li>{@code ring_corner} / {@code ring_side_hor} / {@code ring_side_ver} - the metal joiners drawn
 *       around every side that has no neighbour; taken from AE2 unchanged so the core lines up with the
 *       units around it.</li>
 *   <li>{@code base} - the dark base face of a formed storage unit (AE2's {@code light_base}).</li>
 *   <li>{@code light} - the emissive connection band, i.e. our recoloured-to-#39C5BB copy of AE2's
 *       {@code 16k_storage_light}.</li>
 * </ul>
 */
public final class SchedulerCoreCubeGeometry implements IUnbakedGeometry<SchedulerCoreCubeGeometry> {

    /** The model JSON is static, so one immutable instance is enough. */
    public static final SchedulerCoreCubeGeometry INSTANCE = new SchedulerCoreCubeGeometry();

    private SchedulerCoreCubeGeometry() {
    }

    @Override
    public BakedModel bake(IGeometryBakingContext context, ModelBaker baker,
            Function<Material, TextureAtlasSprite> spriteGetter, ModelState modelState, ItemOverrides overrides) {
        return new LightBakedModel(
                sprite(context, spriteGetter, "ring_corner"),
                sprite(context, spriteGetter, "ring_side_hor"),
                sprite(context, spriteGetter, "ring_side_ver"),
                sprite(context, spriteGetter, "base"),
                sprite(context, spriteGetter, "light"));
    }

    private static TextureAtlasSprite sprite(IGeometryBakingContext context,
            Function<Material, TextureAtlasSprite> spriteGetter, String slot) {
        return spriteGetter.apply(context.getMaterial(slot));
    }
}
