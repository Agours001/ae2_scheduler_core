package com.schedulercore.measure;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * The two constants and the one line of plumbing that {@link RigMeasure} and {@link RigCompare} share.
 *
 * <p>Both are tick-driven state machines that wait on AE2's asynchronous craft planner, and both report
 * through the same chat broadcast. Keeping the timeout in one place matters more than it looks: the two
 * would otherwise be free to drift apart, and a plan that is fast enough for one command but not the other
 * is exactly the kind of thing that sends a debugging session down the wrong path.
 */
final class RigTiming {

    /** Ticks to wait for AE2's asynchronous planner before abandoning a step / a leg. */
    static final int PLAN_TIMEOUT_TICKS = 200;

    /** How many consecutive zero-progress ticks counts as a stall. */
    static final int STALL_TICKS = 100;

    private RigTiming() {
    }

    /** Reports one line to every player, the way both rig commands' diagnostics are meant to be read. */
    static void broadcast(MinecraftServer server, String message) {
        server.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
    }
}
