package com.schedulercore.measure;

import java.lang.reflect.Method;

import appeng.crafting.execution.CraftingCpuLogic;
import appeng.menu.me.crafting.CraftingStatus;

/**
 * Calls AE2's crafting-job suspend API by name, because the AE2 versions this mod supports do not all have it.
 *
 * <h2>Why reflection instead of a plain call</h2>
 *
 * <p>{@code CraftingCpuLogic.isJobSuspended}, {@code setJobSuspended} and {@code CraftingStatus.isSuspended}
 * arrived with crafting-job suspend in AE2 19.2.16, while this mod runs from 19.2.0 onwards. A direct call is a
 * hard link on an optional member: it does not even compile against the older jars, and on a runtime that
 * lacks the members it fails at the call site rather than at the feature. Looking them up once turns both
 * cases into "this AE2 has no such feature", which the probe then reports as such.
 *
 * <p>The rig is the only place that needs AE2's own suspend entry points. The scheduler itself reaches the
 * flag through {@code SuspendSupport}, whose implementation is installed only where the flag exists.
 */
final class RigSuspendApi {

    /** Public members, so {@code getMethod} finds them as-is. */
    private static final Method IS_JOB_SUSPENDED = lookup(CraftingCpuLogic.class, "isJobSuspended");

    private static final Method SET_JOB_SUSPENDED =
            lookup(CraftingCpuLogic.class, "setJobSuspended", boolean.class);

    private static final Method STATUS_IS_SUSPENDED = lookup(CraftingStatus.class, "isSuspended");

    private RigSuspendApi() {
    }

    /** Whether this AE2 has the pair the suspend button is built on. */
    static boolean available() {
        return IS_JOB_SUSPENDED != null && SET_JOB_SUSPENDED != null;
    }

    static boolean isJobSuspended(CraftingCpuLogic logic) throws ReflectiveOperationException {
        return (Boolean) IS_JOB_SUSPENDED.invoke(logic);
    }

    static void setJobSuspended(CraftingCpuLogic logic, boolean suspended) throws ReflectiveOperationException {
        SET_JOB_SUSPENDED.invoke(logic, suspended);
    }

    /** What AE2's own status object says, or {@code n/a} where that build's status object says nothing. */
    static String statusSuspended(CraftingStatus status) {
        if (STATUS_IS_SUSPENDED == null) {
            return "n/a";
        }
        try {
            return String.valueOf(STATUS_IS_SUSPENDED.invoke(status));
        } catch (ReflectiveOperationException e) {
            return "?";
        }
    }

    /** A missing member is an answer here ("this AE2 has no suspend"), so it is not an error. */
    private static Method lookup(Class<?> owner, String name, Class<?>... parameters) {
        try {
            return owner.getMethod(name, parameters);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
