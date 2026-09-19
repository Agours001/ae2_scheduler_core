package com.schedulercore.mixin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Answers, without loading a single AE2 class, whether this AE2 can suspend a crafting job at all.
 *
 * <h2>Who asks</h2>
 *
 * <p>{@link SchedulerCoreMixinPlugin} asks it to decide whether to apply the mixins that name the feature's
 * members, and {@code NeoForgeSupport} asks it again before installing a suspend implementation. Both have to
 * get the same answer - a mixin that was withdrawn while the feature is assumed present would fail on first
 * use - so there is one probe and not two.
 *
 * <h2>Why the class cannot simply be looked up</h2>
 *
 * <p>The plugin is asking while mixin configuration is being applied, and loading a target class at that
 * moment is fatal: it defines the class before its mixins have had a chance to apply, and the game dies with
 * {@code MixinTargetAlreadyLoadedException}. The class file therefore has to be read as a <b>resource</b> - a
 * stream of bytes out of AE2's jar - which loads nothing.
 *
 * <h2>Why a field name is the signal</h2>
 *
 * <p>The flag is {@code ExecutingCraftingJob.suspended}, added by the same AE2 release as the two methods that
 * read and write it (19.2.16, PR #8635), and a field's own name sits in its declaring class's constant pool.
 * So that string being present in that class file is exactly "this AE2 can suspend a job" - measured rather
 * than assumed: absent from 19.2.0-beta, 19.2.4 and 19.2.15, present in 19.2.17.
 *
 * <p>Nothing else this mod needs varies across the 19.2 line: every other hook exists from 19.2.0-beta
 * onwards (checked member by member against those jars). That is what lets the mod keep an early floor and
 * still be honest about it - on a newer AE2 the freeze feature works, on an older one the feature is simply
 * absent, instead of the game being brought down by a mixin whose target is not there.
 */
public final class SuspendApiProbe {

    /** AE2's crafting job: the class that declares the flag. */
    private static final String JOB_CLASS_RESOURCE = "appeng/crafting/execution/ExecutingCraftingJob.class";

    /** The flag's own name, which exists in the class file only where the field does. */
    private static final String FLAG_FIELD = "suspended";

    /** Cached: the classpath cannot change within a launch, and the answer is asked once per mixin config. */
    private static Boolean available;

    private SuspendApiProbe() {
    }

    public static synchronized boolean available() {
        if (available == null) {
            available = probe();
        }
        return available;
    }

    private static boolean probe() {
        // The plugin's own loader is the one that sees the mod jars; a launcher sometimes puts them behind
        // the context loader instead. Either can be absent, hence the null checks.
        ClassLoader ownLoader = SuspendApiProbe.class.getClassLoader();
        if (ownLoader != null && hasFlag(ownLoader)) {
            return true;
        }
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        return contextLoader != null && hasFlag(contextLoader);
    }

    private static boolean hasFlag(ClassLoader loader) {
        try (InputStream in = loader.getResourceAsStream(JOB_CLASS_RESOURCE)) {
            if (in == null) {
                return false; // AE2 is not visible; the plugin reports that separately and disarms everything
            }
            // ISO-8859-1 maps each byte to exactly one char, so this stays a byte-for-byte search and cannot
            // be fooled by a multi-byte sequence the way a UTF-8 decode could.
            String classFile = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            return classFile.contains(FLAG_FIELD);
        } catch (IOException e) {
            // An unreadable class file means "no suspend": the feature is what is lost, never the game.
            return false;
        }
    }
}
