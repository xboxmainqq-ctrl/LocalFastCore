package ru.nexora.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Deterministic, thread-safe scheduler driven by the server's game ticks.
 *
 * <p>The scheduler does not create its own thread or clock. The future game
 * loop calls {@link #tick()} exactly once for each completed server tick.
 * Tasks are ordered by due tick and then by registration order.</p>
 */
public final class TickScheduler {
    private final PriorityQueue<ScheduledTask> pending = new PriorityQueue<>(
            Comparator.comparingLong((ScheduledTask task) -> task.dueTick)
                    .thenComparingLong(task -> task.sequence)
    );

    private long currentTick;
    private long nextSequence;

    /** Returns the number of ticks processed by this scheduler. */
    public synchronized long currentTick() {
        return currentTick;
    }

    /**
     * Schedules a one-shot task. A delay of one means the next call to tick().
     */
    public synchronized TaskHandle schedule(long delayTicks, Runnable action) {
        validateDelay(delayTicks);
        return enqueue(delayTicks, 0, action);
    }

    /**
     * Schedules a repeating task. The first run occurs after initialDelayTicks;
     * subsequent runs are separated by periodTicks.
     */
    public synchronized TaskHandle scheduleRepeating(
            long initialDelayTicks,
            long periodTicks,
            Runnable action
    ) {
        validateDelay(initialDelayTicks);
        validateDelay(periodTicks);
        return enqueue(initialDelayTicks, periodTicks, action);
    }

    /**
     * Advances the scheduler by one tick and executes all tasks due on it.
     *
     * <p>All due tasks are attempted even if one throws a RuntimeException.
     * The first failure is rethrown after execution, with later failures
     * attached as suppressed exceptions.</p>
     */
    public synchronized void tick() {
        currentTick = Math.incrementExact(currentTick);
        List<ScheduledTask> due = new ArrayList<>();

        while (!pending.isEmpty() && pending.peek().dueTick <= currentTick) {
            due.add(pending.remove());
        }

        RuntimeException failure = null;
        for (ScheduledTask task : due) {
            if (task.cancelled) {
                continue;
            }
            try {
                task.action.run();
            } catch (RuntimeException taskFailure) {
                if (failure == null) {
                    failure = taskFailure;
                } else {
                    failure.addSuppressed(taskFailure);
                }
            } finally {
                if (!task.cancelled && task.periodTicks > 0) {
                    task.dueTick = Math.addExact(currentTick, task.periodTicks);
                    pending.add(task);
                } else {
                    task.finished = true;
                }
            }
        }

        if (failure != null) {
            throw failure;
        }
    }

    private TaskHandle enqueue(long delayTicks, long periodTicks, Runnable action) {
        Objects.requireNonNull(action, "action");
        long dueTick = Math.addExact(currentTick, delayTicks);
        ScheduledTask task = new ScheduledTask(dueTick, periodTicks, nextSequence++, action);
        pending.add(task);
        return new TaskHandle(task);
    }

    private static void validateDelay(long ticks) {
        if (ticks < 1) {
            throw new IllegalArgumentException("Tick delay and period must be at least 1");
        }
    }

    private static final class ScheduledTask {
        private long dueTick;
        private final long periodTicks;
        private final long sequence;
        private final Runnable action;
        private boolean cancelled;
        private boolean finished;

        private ScheduledTask(long dueTick, long periodTicks, long sequence, Runnable action) {
            this.dueTick = dueTick;
            this.periodTicks = periodTicks;
            this.sequence = sequence;
            this.action = action;
        }
    }

    /** Handle used to cancel a task or inspect its state. */
    public final class TaskHandle {
        private final ScheduledTask task;

        private TaskHandle(ScheduledTask task) {
            this.task = task;
        }

        /** Cancels the task; returns false if it has already finished or was cancelled. */
        public boolean cancel() {
            synchronized (TickScheduler.this) {
                if (task.cancelled || task.finished) {
                    return false;
                }
                task.cancelled = true;
                return true;
            }
        }

        public boolean isCancelled() {
            synchronized (TickScheduler.this) {
                return task.cancelled;
            }
        }

        public boolean isDone() {
            synchronized (TickScheduler.this) {
                return task.cancelled || task.finished;
            }
        }
    }
}
