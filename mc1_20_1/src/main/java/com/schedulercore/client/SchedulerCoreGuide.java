package com.schedulercore.client;

import net.minecraft.resources.ResourceLocation;

import guideme.Guide;

import com.schedulercore.SchedulerCore;

/**
 * Registers this mod's in-game guide page, so hovering an item with the guide key shows it.
 *
 * <p>AE2 ships GuideME for exactly this, and its own guide is registered the same way
 * ({@code Guide.builder(id).folder("ae2guide").build()}). Everything the guide needs is data:
 *
 * <ul>
 *   <li>the pages live under {@code assets/schedulercore/ae2guide/}, and {@code folder("ae2guide")}
 *       points the guide at them;</li>
 *   <li>the page front matter maps items to the page ({@code item_ids}), which is what makes the
 *       "hold the guide key while hovering" affordance work - so no mixin or event hook is needed
 *       for the item interaction itself;</li>
 *   <li>{@code <RecipeFor id="..."/>} inside a page renders the item's own recipe, straight from the
 *       recipe manager, so the guide cannot drift away from the actual recipes.</li>
 * </ul>
 *
 * <p>Registration is attempted once at mod construction. A failure here is logged and nothing else:
 * a missing or malformed guide must never be the reason a world will not start.
 *
 * <p><b>1.20.1 difference from the 1.21.1 twin.</b> Only the id is built differently, with the
 * two-argument {@code new ResourceLocation(String, String)} constructor instead of
 * {@code ResourceLocation.fromNamespaceAndPath}. Forge <i>does</i> backport that factory to this
 * generation (verified with {@code javap} against {@code forge-1.20.1-47.4.10-merged.jar}), but it
 * marks the constructor {@code @Deprecated(forRemoval = true, since = "1.20.6")} precisely because of
 * that backport, and the constructor is the form present on <i>every</i> Forge 47.x build - which is
 * what this target's declared runtime range, {@code [47.1.3,)}, allows. Keep the constructor here, as
 * the rest of this target does; the one deprecation warning it prints is the same one this target
 * already prints for {@code SchedulerCore}'s own {@code ResourceLocation}.
 *
 * <p>The GuideME 20.1.15 API this class calls - {@code Guide.builder(ResourceLocation)},
 * {@code GuideBuilder.folder(String)}, {@code GuideBuilder.build()}, with {@code GuideBuilder.register}
 * still defaulting to {@code true} so {@code build()} self-registers - is the same shape as the
 * GuideME the 1.21.1 target uses.
 */
public final class SchedulerCoreGuide {

    /** The guide's own id. Only used to look the guide up and to create its guide item, if ever wanted. */
    public static final ResourceLocation GUIDE_ID =
            new ResourceLocation(SchedulerCore.MOD_ID, "guide");

    /** The data folder under {@code assets/schedulercore/} that holds the pages. */
    public static final String FOLDER = "ae2guide";

    private static boolean registered;

    private SchedulerCoreGuide() {
    }

    /** Builds and registers the guide. Safe to call more than once. */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        try {
            Guide.builder(GUIDE_ID)
                    .folder(FOLDER)
                    .build();
            SchedulerCore.LOG.info("[schedulercore] in-game guide registered ({} pages under {})",
                    SchedulerCore.MOD_ID + ":" + FOLDER + "/index.md", FOLDER);
        } catch (Throwable t) {
            SchedulerCore.LOG.warn("[schedulercore] could not register the in-game guide; the mod works"
                    + " without it", t);
        }
    }
}
