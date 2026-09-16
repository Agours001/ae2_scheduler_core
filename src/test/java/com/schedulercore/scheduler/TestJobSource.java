package com.schedulercore.scheduler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal in-memory {@link JobSource} shared by the L1 tests: jobs are ids {@code 0..n-1} in insertion
 * order.
 *
 * <p>Both test classes used to carry their own byte-identical copy of this, which is the kind of
 * duplication that only shows up when one copy is fixed and the other is not. The blocking and removal
 * helpers live here too: {@link RoundRobinPolicyTest} needs them and {@link SchedulerThroughputTest}
 * simply never calls them, which is cheaper than two divergent fixtures.
 */
final class TestJobSource implements JobSource {

    private final List<Long> ids = new ArrayList<>();
    private final Map<Long, Status> statuses = new LinkedHashMap<>();

    TestJobSource(int count) {
        for (long i = 0; i < count; i++) {
            ids.add(i);
            statuses.put(i, Status.READY);
        }
    }

    void setBlocked(long id, boolean blocked) {
        statuses.put(id, blocked ? Status.BLOCKED : Status.READY);
    }

    void setAllBlocked(boolean blocked) {
        statuses.replaceAll((id, status) -> blocked ? Status.BLOCKED : Status.READY);
    }

    void removeJob(long id) {
        ids.remove(id);
        statuses.remove(id);
    }

    @Override
    public int size() {
        return ids.size();
    }

    @Override
    public long jobIdAt(int index) {
        return index >= 0 && index < ids.size() ? ids.get(index) : -1L;
    }

    @Override
    public Status statusOf(int index) {
        Long id = jobIdAt(index);
        return id < 0 ? Status.BLOCKED : statuses.getOrDefault(id, Status.BLOCKED);
    }

    @Override
    public int indexOf(long jobId) {
        return ids.indexOf(jobId);
    }
}
