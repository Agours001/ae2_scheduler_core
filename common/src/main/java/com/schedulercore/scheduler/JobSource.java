package com.schedulercore.scheduler;

/**
 * The pool of jobs a scheduler picks from, in the order they should be considered.
 *
 * <p>This is the only thing the pure scheduling logic needs to know about a crafting CPU, which is what
 * makes the logic testable without Minecraft on the classpath (see {@code src/test/java}).
 *
 * <p><b>Slot count is authoritative.</b> A job that finishes, is cancelled, or is released must be
 * removed from the source, not merely marked unusable. The scheduler only distinguishes "present" from
 * "present but currently unable to accept a push" - it does not second-guess whether a job has remaining
 * work, because that is the host's business (the host is the thing holding the AE2 job object).
 */
public interface JobSource {

    /** How the host currently sees one job. */
    enum Status {
        /** The job can accept a push right now (it may still push zero patterns; that is reported back). */
        READY,
        /** The job is present but cannot be served this tick (suspended, no remaining work, ...). */
        BLOCKED
    }

    /** Number of jobs currently present. */
    int size();

    /** Stable identifier of the job at {@code index}, or -1 if the index is out of range. */
    long jobIdAt(int index);

    /** Current status of the job at {@code index}. */
    Status statusOf(int index);

    /** Index of the job with this id, or -1 if it is no longer present. */
    int indexOf(long jobId);
}
