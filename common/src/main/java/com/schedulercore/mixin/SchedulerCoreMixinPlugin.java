package com.schedulercore.mixin;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Disables this mod's mixins when AE2 is absent, instead of letting the game crash.
 *
 * <p>That is the only reason a whole mixin class can be absent, and it is decided once in
 * {@link #onLoad(String)} so that {@link #shouldApplyMixin} only reads a boolean.
 *
 * <p><b>Why it has to be detected this way.</b> Probing for target classes with {@code Class.forName} inside
 * {@link #onLoad(String)} crashes the game with {@code MixinTargetAlreadyLoadedException}: loading a target
 * class during mixin configuration makes it "already loaded" before mixins get a chance to apply. Target
 * detection therefore uses <b>resource</b> lookups only, which cannot load a class.
 *
 * <p><b>Why there is no second reason any more, and why that is not a gap.</b> Until 1.0.5 this plugin also
 * withdrew the two mixins that name AE2's crafting-job suspend whenever the installed AE2 was older than
 * 19.2.16 - the release that added it - so that those packs ran with the freeze feature off. The mod now
 * declares {@code ae2 [19.2.16,)} instead, and the loader enforces that before any of this code runs: a member
 * cannot be missing when the declared floor is the release that introduced it. The 1.20.1 target needed no such
 * check in the first place, because it carries neither of those mixins - AE2 15.x has no crafting-job suspend
 * at all.
 */
public final class SchedulerCoreMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOG = LoggerFactory.getLogger("SchedulerCore/Mixin");

    /** Resources that exist only when AE2 is installed. Deliberately NOT class names. */
    private static final String[] AE2_MARKER_RESOURCES = {
            "ae2.mixins.json",
            "assets/ae2/lang/en_us.json"
    };

    private boolean ae2Present = true;

    @Override
    public void onLoad(String mixinPackage) {
        for (String resource : AE2_MARKER_RESOURCES) {
            if (!resourceExists(resource)) {
                ae2Present = false;
                LOG.error("[schedulercore] AE2 marker resource '{}' is missing - disabling scheduler core "
                        + "mixins instead of crashing.", resource);
            }
        }
        if (ae2Present) {
            LOG.info("[schedulercore] AE2 detected - scheduler core mixins are armed.");
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
        return ae2Present;
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
