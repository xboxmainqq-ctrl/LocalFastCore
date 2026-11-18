package ru.nexora.lifecycle;

import java.io.IOException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded scheduler with isolated single-thread lanes.
 *
 * <p>Each lane has its own worker and task-registration budget, so slow
 * persistence work cannot delay tick work. Only periodic tasks are accepted;
 * registrations are bounded before they enter the executor's delay queue.</p>
 */
public final class RuntimeTaskScheduler implements AutoCloseable {
    public enum Lane { TICK, PERSISTENCE, MONITORING }

    private final Map<Lane, LaneRuntime> lanes = new EnumMap<>(Lane.class);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Duration shutdownTimeout;

    public RuntimeTaskScheduler(int maxTasksPerLane, Duration shutdownTimeout) {
        if (maxTasksPerLane < 1) throw new IllegalArgumentException("maxTasksPerLane must be positive");
        this.shutdownTimeout = Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        if (shutdownTimeout.isNegative() || shutdownTimeout.isZero()) {
            throw new IllegalArgumentException("shutdownTimeout must be positive");
        }
        for (Lane lane : Lane.values()) {
            lanes.put(lane, new LaneRuntime(lane, maxTasksPerLane));
        }
    }

    public RuntimeTaskScheduler() {
        this(8, Duration.ofSeconds(5));
    }

    public TaskHandle scheduleAtFixedRate(
            Lane lane, Runnable task, long initialDelay, long period, TimeUnit unit
    ) {
        if (period <= 0) throw new IllegalArgumentException("period must be positive");
        return schedule(lane, task, initialDelay, period, unit, true);
    }

    public TaskHandle scheduleWithFixedDelay(
            Lane lane, Runnable task, long initialDelay, long delay, TimeUnit unit
    ) {
        if (delay <= 0) throw new IllegalArgumentException("delay must be positive");
        return schedule(lane, task, initialDelay, delay, unit, false);
    }

    private TaskHandle schedule(
            Lane lane, Runnable task, long initialDelay, long interval, TimeUnit unit, boolean fixedRate
    ) {
        Objects.requireNonNull(lane, "lane");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(unit, "unit");
        if (initialDelay < 0) throw new IllegalArgumentException("initialDelay must not be negative");
        if (closed.get()) throw new IllegalStateException("Task scheduler is closed");
        LaneRuntime runtime = lanes.get(lane);
        if (!runtime.reserve()) throw new IllegalStateException("Task limit reached for lane " + lane);
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) runtime.release();
        };
        try {
            Runnable guarded = () -> {
                if (!closed.get()) task.run();
            };
            ScheduledFuture<?> future = fixedRate
                    ? runtime.executor.scheduleAtFixedRate(guarded, initialDelay, interval, unit)
                    : runtime.executor.scheduleWithFixedDelay(guarded, initialDelay, interval, unit);
            return new TaskHandle(future, release);
        } catch (RuntimeException failure) {
            release.run();
            throw failure;
        }
    }

    public int registeredTasks(Lane lane) {
        return lanes.get(Objects.requireNonNull(lane, "lane")).registered.get();
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        lanes.values().forEach(lane -> lane.executor.shutdown());
        IOException failure = null;
        for (Map.Entry<Lane, LaneRuntime> entry : lanes.entrySet()) {
            try {
                if (!entry.getValue().executor.awaitTermination(
                        shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    entry.getValue().executor.shutdownNow();
                    if (!entry.getValue().executor.awaitTermination(
                            shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                        IOException timeout = new IOException(
                                "Timed out stopping " + entry.getKey() + " task lane");
                        if (failure == null) failure = timeout;
                        else failure.addSuppressed(timeout);
                    }
                }
            } catch (InterruptedException exception) {
                entry.getValue().executor.shutdownNow();
                Thread.currentThread().interrupt();
                IOException interrupted = new IOException(
                        "Interrupted while stopping " + entry.getKey() + " task lane", exception);
                if (failure == null) failure = interrupted;
                else failure.addSuppressed(interrupted);
                break;
            }
        }
        if (failure != null) throw failure;
    }

    public static final class TaskHandle implements AutoCloseable {
        private final ScheduledFuture<?> future;
        private final Runnable release;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private TaskHandle(ScheduledFuture<?> future, Runnable release) {
            this.future = future;
            this.release = release;
        }

        public boolean cancel() {
            if (!cancelled.compareAndSet(false, true)) return false;
            boolean result = future.cancel(false);
            release.run();
            return result;
        }

        public boolean isCancelled() {
            return future.isCancelled();
        }

        @Override
        public void close() {
            cancel();
        }
    }

    private static final class LaneRuntime {
        private final ScheduledThreadPoolExecutor executor;
        private final int maxTasks;
        private final AtomicInteger registered = new AtomicInteger();

        private LaneRuntime(Lane lane, int maxTasks) {
            this.maxTasks = maxTasks;
            executor = new ScheduledThreadPoolExecutor(1, task -> {
                Thread thread = new Thread(task, "nexora-" + lane.name().toLowerCase() + "-runtime");
                thread.setDaemon(true);
                return thread;
            });
            executor.setRemoveOnCancelPolicy(true);
            executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        }

        private boolean reserve() {
            while (true) {
                int current = registered.get();
                if (current >= maxTasks) return false;
                if (registered.compareAndSet(current, current + 1)) return true;
            }
        }

        private void release() {
            registered.updateAndGet(current -> Math.max(0, current - 1));
        }
    }
}
