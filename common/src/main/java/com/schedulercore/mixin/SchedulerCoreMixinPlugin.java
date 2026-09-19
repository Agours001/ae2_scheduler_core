package com.schedulercore.mixin;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Disables the mixins whose target does not exist, instead of letting the game crash.
 *
 * <p>Two separate reasons a whole mixin class can be absent, decided in {@link #onLoad(String)} so that
 * {@link #shouldApplyMixin} only reads two booleans:
 *
 * <ul>
 *   <li><b>AE2 is not installed.</b> Then every mixin goes; without this the game would die applying a mixin
 *       to a class that is not on the classpath at all.</li>
 *   <li><b>This AE2 cannot suspend a crafting job</b> (everything before 19.2.16). Then the two mixin classes
 *       that name members of that feature go, and the mod runs with the rest - see {@link SuspendApiProbe}
 *       for how that is decided without loading a class.</li>
 * </ul>
 *
 * <p><b>Why not {@code Class.forName}.</b> Probing for target classes with {@code Class.forName} inside
 * {@link #onLoad(String)} crashes the game with {@code MixinTargetAlreadyLoadedException}: loading a target
 * class during mixin configuration makes it "already loaded" before mixins get a chance to apply. Target
 * detection therefore uses <b>resource</b> lookups only, which cannot load a class.
 */
public final class SchedulerCoreMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOG = LoggerFactory.getLogger("SchedulerCore/Mixin");

    /** Resources that exist only when AE2 is installed. Deliberately NOT class names. */
    private static final String[] AE2_MARKER_RESOURCES = {
            "ae2.mixins.json",
            "assets/ae2/lang/en_us.json"
    };

    /**
     * The mixins that name crafting-job suspend, which AE2 gained in 19.2.16.
     *
     * <p>Fully qualified, because that is what Mixin passes to {@link #shouldApplyMixin}.
     */
    private static final Set<String> SUSPEND_MIXINS = Set.of(
            "com.schedulercore.mixin.AccessorExecutingCraftingJobSuspend",
            "com.schedulercore.mixin.MixinCraftingCpuLogicSuspension");

    private boolean ae2Present = true;

    private boolean suspendSupported = true;

    @Override
    public void onLoad(String mixinPackage) {
        for (String resource : AE2_MARKER_RESOURCES) {
            if (!resourceExists(resource)) {
                ae2Present = false;
                LOG.error("[schedulercore] AE2 marker resource '{}' is missing - disabling scheduler core "
                        + "mixins instead of crashing.", resource);
            }
        }
        if (!ae2Present) {
            return;
        }

        suspendSupported = SuspendApiProbe.available();
        LOG.info("[schedulercore] AE2 detected - scheduler core mixins are armed"
                + (suspendSupported ? "." : ", except the ones for job suspend (needs AE2 19.2.16+)."));
        if (!suspendSupported) {
            // Worth a line of its own: on this AE2 the crafting screen has no suspend button at all, so the
            // absence is expected rather than a failure, and saying so saves reading it as one.
            LOG.warn("[schedulercore] this AE2 predates crafting-job suspend - the freeze feature is off, "
                    + "everything else works normally.");
        }
    }

    /** Resource lookup only: this must never load a class. */
    private static boolean resourceExists(String path) {
        var ownLoader = SchedulerCoreMixinPlugin.class.getClassLoader();
        if (ownLoader != null && ownLoader.getResource(path) != null) {
            return true;
        }
        var contextLoader = Thread.currentThread().getContextClassLoader();
        return contextLoader != null && contextLoader.getResource(path) != null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!ae2Present) {
            return false;
        }
        // Withdrawn, not applied-with-require=0: on an AE2 without those members there is nothing to inject
        // into, and require=0 does not cover field targets, so a half-applied suspend mixin is not a state
        // worth having. Deciding once, here, is also what keeps every *other* hook failing loudly.
        return suspendSupported || !SUSPEND_MIXINS.contains(mixinClassName);
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
