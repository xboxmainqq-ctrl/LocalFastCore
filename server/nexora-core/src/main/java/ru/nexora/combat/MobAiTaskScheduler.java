package ru.nexora.combat;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongArray;

public final class MobAiTaskScheduler {
    public enum Task {
        TARGETING(1), NAVIGATION(3), MOVEMENT(2), COMBAT(4), IDLE(1);
        private final int costWeight;
        Task(int costWeight) { this.costWeight = costWeight; }
        public int costWeight() { return costWeight; }
    }

    public enum Admission { RUN, DEFER }

    private static final Task[] TASKS = Task.values();
    private final AtomicLongArray runs = new AtomicLongArray(TASKS.length);
    private final AtomicLongArray deferrals = new AtomicLongArray(TASKS.length);

    public Admission admit(Task task, MobAiLoadController.Priority priority,
                           boolean required, long consumedNanos, long budgetNanos) {
        if (task == null || priority == null || consumedNanos < 0 || budgetNanos < 0) {
            throw new IllegalArgumentException("Invalid AI task admission");
        }
        if (required || priority == MobAiLoadController.Priority.HIGH) {
            recordRun(task);
            return Admission.RUN;
        }
        // A zero budget means no optional work is admitted, even when no time
        // has yet been consumed in this tick. Required/high-priority work bypasses it.
        if (budgetNanos == 0) {
            recordDeferral(task);
            return Admission.DEFER;
        }
        long weightedBudget = Math.max(1L, budgetNanos / Math.max(1, task.costWeight()));
        if (consumedNanos < weightedBudget) {
            recordRun(task);
            return Admission.RUN;
        }
        recordDeferral(task);
        return Admission.DEFER;
    }

    public void recordRun(Task task) {
        if (task == null) throw new IllegalArgumentException("task must not be null");
        incrementSaturated(runs, task.ordinal());
    }

    public void recordDeferral(Task task) {
        if (task == null) throw new IllegalArgumentException("task must not be null");
        incrementSaturated(deferrals, task.ordinal());
    }

    private static void incrementSaturated(AtomicLongArray counters, int index) {
        while (true) {
            long current = counters.get(index);
            long next = saturatedAdd(current, 1L);
            if (next == current || counters.compareAndSet(index, current, next)) return;
        }
    }

    static long saturatedAdd(long current, long increment) {
        if (current < 0 || increment < 0) {
            throw new IllegalArgumentException("AI task counters must be non-negative");
        }
        return current > Long.MAX_VALUE - increment ? Long.MAX_VALUE : current + increment;
    }

    public Stats stats() {
        EnumMap<Task, Long> runSnapshot = new EnumMap<>(Task.class);
        EnumMap<Task, Long> deferralSnapshot = new EnumMap<>(Task.class);
        for (Task task : TASKS) {
            runSnapshot.put(task, runs.get(task.ordinal()));
            deferralSnapshot.put(task, deferrals.get(task.ordinal()));
        }
        return new Stats(runSnapshot, deferralSnapshot);
    }

    public record Stats(Map<Task, Long> runs, Map<Task, Long> deferrals) {
        public Stats {
            runs = Map.copyOf(runs);
            deferrals = Map.copyOf(deferrals);
        }

        public String summary() {
            return "runs=" + runs + ", deferred=" + deferrals;
        }
    }
}
