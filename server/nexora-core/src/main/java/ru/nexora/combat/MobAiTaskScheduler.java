package ru.nexora.combat;

import java.util.EnumMap;
import java.util.Map;

public final class MobAiTaskScheduler {
    public enum Task {
        TARGETING(1), NAVIGATION(3), MOVEMENT(2), COMBAT(4), IDLE(1);
        private final int costWeight;
        Task(int costWeight) { this.costWeight = costWeight; }
        public int costWeight() { return costWeight; }
    }

    public enum Admission { RUN, DEFER }

    private final EnumMap<Task, Long> runs = new EnumMap<>(Task.class);
    private final EnumMap<Task, Long> deferrals = new EnumMap<>(Task.class);

    public MobAiTaskScheduler() {
        for (Task task : Task.values()) {
            runs.put(task, 0L);
            deferrals.put(task, 0L);
        }
    }

    public Admission admit(Task task, MobAiLoadController.Priority priority,
                           boolean required, long consumedNanos, long budgetNanos) {
        if (task == null || priority == null || consumedNanos < 0 || budgetNanos < 0) {
            throw new IllegalArgumentException("Invalid AI task admission");
        }
        if (required || priority == MobAiLoadController.Priority.HIGH) {
            recordRun(task);
            return Admission.RUN;
        }
        long weightedBudget = Math.max(1L, budgetNanos / Math.max(1, task.costWeight()));
        if (consumedNanos < weightedBudget) {
            recordRun(task);
            return Admission.RUN;
        }
        recordDeferral(task);
        return Admission.DEFER;
    }

    public synchronized void recordRun(Task task) {
        runs.merge(task, 1L, Long::sum);
    }

    public synchronized void recordDeferral(Task task) {
        deferrals.merge(task, 1L, Long::sum);
    }

    public synchronized Stats stats() {
        return new Stats(Map.copyOf(runs), Map.copyOf(deferrals));
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
