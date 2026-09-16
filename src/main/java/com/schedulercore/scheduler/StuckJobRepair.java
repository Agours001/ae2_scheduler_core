package com.schedulercore.scheduler;

import java.lang.reflect.Field;

import appeng.crafting.execution.CraftingCpuLogic;

/**
 * Emergency repair for a crafting CPU that is permanently busy.
 *
 * <p><b>The condition this exists for.</b> Vanilla's {@code CraftingCpuLogic.readFromNBT} restores whatever
 * job it finds in the CPU's saved NBT into its single-job field. An older build of this mod wrote such a job
 * and had no read path, so a save written then comes back with an <i>untracked</i> job: the scheduler does not
 * know about it, the vanilla tick does not run it either (the scheduler took over the tick), and yet it makes
 * the CPU report itself busy forever. The visible symptom is that the auto-craft button lights up and then
 * immediately greys out again - {@code CPU_BUSY} - with no way for the player to clear it.
 *
 * <p>This class clears that field and hands whatever the CPU still holds back to the network, so an affected
 * save can be recovered without editing NBT by hand.
 */
public final class StuckJobRepair {

    private static Field jobField;

    private StuckJobRepair() {
    }

    private static Field jobField() throws ReflectiveOperationException {
        if (jobField == null) {
            var f = CraftingCpuLogic.class.getDeclaredField("job");
            f.setAccessible(true);
            jobField = f;
        }
        return jobField;
    }

    /**
     * @return a human-readable report of what was found and cleared
     */
    public static String clear(CraftingCpuLogic logic) {
        try {
            var field = jobField();
            var job = field.get(logic);

            // Return anything the CPU is holding to the network first, using vanilla's own dump. Its
            // precondition is that the job field is empty, so clear the field before calling it.
            field.set(logic, null);
            logic.storeItems();

            if (job == null) {
                return "no stuck job (CPU inventory drained)";
            }
            return "cleared 1 untracked job; CPU inventory returned to the network";
        } catch (ReflectiveOperationException e) {
            return "repair failed: " + e;
        } catch (Throwable t) {
            return "repair failed: " + t;
        }
    }

    /** True when the CPU currently has a vanilla job in its single-job field. */
    public static boolean hasUntrackedJob(CraftingCpuLogic logic) {
        try {
            return jobField().get(logic) != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
