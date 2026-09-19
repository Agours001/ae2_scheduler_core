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
 */
public final class SchedulerCoreGuide {

    /** The guide's own id. Only used to look the guide up and to create its guide item, if ever wanted. */
    public static final ResourceLocation GUIDE_ID =
            ResourceLocation.fromNamespaceAndPath(SchedulerCore.MOD_ID, "guide");

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
