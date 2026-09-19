package com.schedulercore.scheduler;

import java.lang.reflect.Constructor;

import net.minecraft.server.level.ServerPlayer;

import appeng.api.features.IPlayerRegistry;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.security.IActionSource;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;

/**
 * Creates the per-job objects the scheduler needs.
 *
 * <p><b>Why reflection instead of a direct call.</b> {@code ExecutingCraftingJob}'s constructor takes a
 * {@code CraftingDifferenceListener}, which is a <i>package-private nested interface</i>. Java will not
 * let code outside {@code appeng.crafting.execution} so much as name that parameter, so the call cannot be
 * written in source no matter how it is arranged. Three other routes were tried and all fail:
 *
 * <ul>
 *   <li>a helper class <i>inside</i> {@code appeng.crafting.execution} - compiles, but shipping a second
 *       copy of an AE2 package is a JPMS split package and the game refuses to start
 *       ({@code ResolutionException: Module schedulercore contains package appeng.crafting.execution});</li>
 *   <li>{@code @Invoker} - does not support constructors;</li>
 *   <li>declaring the listener parameter as {@code Consumer<AEKey>} - the compiler then reports
 *       "no suitable constructor found".</li>
 * </ul>
 *
 * <p>Reflection is legitimate here rather than a hack: AE2's {@code mods.toml} declares
 * {@code exports appeng.crafting.execution to schedulercore}, i.e. that package is exported to this module
 * on purpose, and the constructor is not final or hidden. Lookups are resolved once and cached.
 */
public final class CraftingJobFactory {

    private static Constructor<ExecutingCraftingJob> constructor;
    /** The constructor's listener parameter type, resolved once out of {@link #constructor}. */
    private static Class<?> listenerType;

    private CraftingJobFactory() {
    }

    @SuppressWarnings("unchecked")
    private static Constructor<ExecutingCraftingJob> constructor() throws ReflectiveOperationException {
        if (constructor == null) {
            for (var candidate : ExecutingCraftingJob.class.getDeclaredConstructors()) {
                var params = candidate.getParameterTypes();
                if (params.length == 4 && params[0] == ICraftingPlan.class) {
                    candidate.setAccessible(true);
                    constructor = (Constructor<ExecutingCraftingJob>) candidate;
                    listenerType = params[1];
                    break;
                }
            }
            if (constructor == null) {
                throw new NoSuchMethodException("ExecutingCraftingJob(ICraftingPlan, listener, link, playerId)");
            }
        }
        return constructor;
    }

    /** Minimal functional shape the listener argument must satisfy at runtime. */
    public interface DifferenceSink {
        void onCraftingDifference(appeng.api.stacks.AEKey key);
    }

    /**
     * Wraps a {@link DifferenceSink} in the constructor's real listener interface.
     *
     * <p><b>The sink is captured by the handler's closure, not parked in a ThreadLocal.</b> The first
     * version stored it in a {@code ThreadLocal} and cleared it immediately after construction. But AE2
     * invokes {@code onCraftingDifference} <i>later</i> - every time items move in or out of the job - so
     * each of those callbacks dereferenced a null and threw. On a real machine that produced 10289 NPEs and
     * visibly broke the job's item accounting (the crafting status parallelism wobbled). The sink lives
     * exactly as long as the job, so the proxy simply holds a reference to it.
     */
    private static Object listenerProxy(DifferenceSink sink) throws ReflectiveOperationException {
        var type = listenerTypeClass();
        return java.lang.reflect.Proxy.newProxyInstance(
                CraftingJobFactory.class.getClassLoader(),
                new Class<?>[] { type },
                (proxy, method, args) -> {
                    // Only onCraftingDifference(AEKey) is ever called; Object methods are answered inline.
                    if ("onCraftingDifference".equals(method.getName()) && args != null && args.length == 1) {
                        sink.onCraftingDifference((appeng.api.stacks.AEKey) args[0]);
                        return null;
                    }
                    switch (method.getName()) {
                        case "toString":
                            return "schedulercore:DifferenceSink";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            return null;
                    }
                });
    }

    /** Resolves (and caches) the listener parameter type from the constructor. */
    private static Class<?> listenerTypeClass() throws ReflectiveOperationException {
        if (listenerType == null) {
            constructor();
        }
        return listenerType;
    }

    /**
     * Builds one job.
     *
     * @param link the {@code CraftingLink}, built by the caller because it too is only constructible from
     *             NBT link data ({@code CraftingCpuHelper.generateLinkData})
     */
    public static ExecutingCraftingJob create(ICraftingPlan plan, DifferenceSink sink,
            Object link, CraftingCpuLogic cpu, IActionSource src) {
        try {
            var playerId = src == null ? null : src.player()
                    .map(p -> p instanceof ServerPlayer sp ? IPlayerRegistry.getPlayerId(sp) : null)
                    .orElse(null);
            return constructor().newInstance(plan, listenerProxy(sink), link, playerId);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("schedulercore: cannot construct ExecutingCraftingJob", e);
        }
    }

    /**
     * Rebuilds one job from its saved NBT.
     *
     * <p>Same reflection problem as {@link #create}, and the same solution: 19.2.17's NBT constructor is
     * {@code ExecutingCraftingJob(CompoundTag, HolderLookup.Provider, CraftingDifferenceListener,
     * CraftingCpuLogic)}, and one of those parameter types cannot be named from outside AE2's package.
     *
     * <p>The registry lookup arrives as an {@code Object} because the generation that needs it is not the only
     * one this file must compile against: 1.20.1's reader takes no registry argument at all. The constructor's
     * own arity decides the argument list, so neither case has to be named here.
     *
     * <p><b>Why not deserialise the job by hand.</b> A job's NBT holds its link, final output, remaining
     * amount, waiting-for ledger, elapsed-time tracker and task table - reimplementing that would duplicate
     * AE2's format and go silently wrong the first time the format changes. Vanilla's own constructor is the
     * only reader guaranteed to stay in step with vanilla's writer, and it also registers the link with the
     * crafting service, which a hand-written route would forget.
     */
    public static ExecutingCraftingJob restore(net.minecraft.nbt.CompoundTag data, Object nbtContext,
            DifferenceSink sink, CraftingCpuLogic cpu) {
        try {
            var constructor = nbtConstructor();
            return constructor.getParameterCount() == 4
                    ? constructor.newInstance(data, nbtContext, listenerProxy(sink), cpu)
                    : constructor.newInstance(data, listenerProxy(sink), cpu);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("schedulercore: cannot restore ExecutingCraftingJob", e);
        }
    }

    private static Constructor<ExecutingCraftingJob> nbtConstructor;

    @SuppressWarnings("unchecked")
    private static Constructor<ExecutingCraftingJob> nbtConstructor() throws ReflectiveOperationException {
        if (nbtConstructor == null) {
            for (var candidate : ExecutingCraftingJob.class.getDeclaredConstructors()) {
                var params = candidate.getParameterTypes();
                // Four parameters where the reader takes a registry lookup, three where it does not. Both
                // begin with the tag and end with (listener, CraftingCpuLogic), so the listener is the
                // second-to-last argument in either shape.
                if ((params.length == 4 || params.length == 3)
                        && params[0] == net.minecraft.nbt.CompoundTag.class) {
                    candidate.setAccessible(true);
                    nbtConstructor = (Constructor<ExecutingCraftingJob>) candidate;
                    if (listenerType == null) {
                        // The package-private listener - the same type the plan constructor takes. Recorded
                        // here too so restore() works even if it is the first one reached.
                        listenerType = params[params.length - 2];
                    }
                    break;
                }
            }
            if (nbtConstructor == null) {
                throw new NoSuchMethodException(
                        "ExecutingCraftingJob(CompoundTag[, registry lookup], listener, CraftingCpuLogic)");
            }
        }
        return nbtConstructor;
    }
}
