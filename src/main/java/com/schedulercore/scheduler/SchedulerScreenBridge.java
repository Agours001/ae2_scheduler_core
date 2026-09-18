package com.schedulercore.scheduler;

/**
 * The narrow seam between the crafting screen's mixins and the crafting logic's mixin.
 *
 * <p>The two live in different classes and neither can call the other's private methods, so what they need
 * from each other is declared here: the screen can ask the logic to end one order or to refresh what it is
 * showing, and the logic answers.
 *
 * <p>Both methods exist because of the same fact about AE2's screen: it is an <b>incremental</b> view. Rows
 * are only re-sent when a key is reported as changed, so anything the scheduler does that alters what a row
 * should say - selecting a different order, cancelling one - is invisible to the client until something marks
 * those keys. Both methods failing is what produced the two symptoms reported from a real machine: every
 * page showing the same mixed table, and a cancelled order's rows lingering until the screen was reopened.
 */
public interface SchedulerScreenBridge {

    /**
     * Ends one scheduled order.
     *
     * @param slotId the order's id, as shown by {@code /schedulercore status} and by its list row
     * @return true when the order existed and was cancelled, false when the caller should fall back to
     *         vanilla's behaviour (the order is already gone)
     */
    boolean schedulercore$cancelOrder(long slotId);

    /**
     * Tells every open crafting screen to re-read the item rows it is showing.
     *
     * <p>Reports a change for every key the table can contain - each order's output and expected items, the
     * shared inventory, and any crafted-but-undelivered product - because a row that should now read zero
     * (or disappear) has to be re-sent just as much as one whose number went up.
     */
    void schedulercore$refreshStatusRows();

    /**
     * The CPU's own job status for the screen's list: the Scheduler Core icon, the aggregate progress over
     * every order and the oldest order's elapsed time, or null when this CPU holds no scheduled order.
     *
     * <p>Asked for by the cluster's {@code getJobStatus()} hook. That hook exists because a row is not a page:
     * the details pane follows the selected row, while the CPU's own row has to keep describing the machine
     * even while one of its orders is selected - which is exactly what {@code getFinalJobOutput()} and
     * {@code getElapsedTimeTracker()} cannot do, since the pane reads those too.
     */
    appeng.api.networking.crafting.CraftingJobStatus schedulercore$cpuStatus();
}
