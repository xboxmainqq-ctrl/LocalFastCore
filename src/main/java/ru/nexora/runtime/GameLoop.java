package ru.nexora.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.concurrent.locks.LockSupport;

/**
 * Dedicated, monotonic-clock-driven game tick loop.
 *
 * <p>The loop owns one daemon thread and advances the supplied scheduler at
 * the configured rate. It limits catch-up work after stalls; excess backlog
 * is discarded rather than allowing an unbounded spiral of catch-up ticks.</p>
 */
public final class GameLoop implements RuntimeModule {
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long STOP_JOIN_MILLIS = 5_000L;

    private final TickScheduler scheduler;
    private final long tickPeriodNanos;
    private final int maxCatchUpTicks;
    private final Consumer<RuntimeException> failureHandler;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong executedTicks = new AtomicLong();
    private final TickMetrics tickMetrics = new TickMetrics();
    private final AtomicLong droppedBacklogs = new AtomicLong();
    private final AtomicReference<RuntimeException> lastFailure = new AtomicReference<>();

    private volatile Thread worker;

    public GameLoop(int tickRate, int maxCatchUpTicks, TickScheduler scheduler) {
        this(tickRate, maxCatchUpTicks, scheduler, failure -> { });
    }

    public GameLoop(
            int tickRate,
            int maxCatchUpTicks,
            TickScheduler scheduler,
            Consumer<RuntimeException> failureHandler
    ) {
        if (tickRate < 1 || tickRate > 120) {
            throw new IllegalArgumentException("Tick rate must be between 1 and 120");
        }
        if (maxCatchUpTicks < 1 || maxCatchUpTicks > 100) {
            throw new IllegalArgumentException("Max catch-up ticks must be between 1 and 100");
        }
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
        this.tickPeriodNanos = NANOS_PER_SECOND / tickRate;
        this.maxCatchUpTicks = maxCatchUpTicks;
    }

    @Override
    public String id() {
        return "game-loop";
    }

    @Override
    public synchronized void start() {
        if (running.get()) {
            throw new IllegalStateException("Game loop is already running");
        }
        if (worker != null && worker.isAlive()) {
            throw new IllegalStateException("Previous game-loop thread is still stopping");
        }

        lastFailure.set(null);
        running.set(true);
        Thread thread = new Thread(this::runLoop, "nexora-game-loop");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    @Override
    public synchronized void stop() throws InterruptedException {
        Thread thread = worker;
        if (thread == null) {
            running.set(false);
            return;
        }

        running.set(false);
        thread.interrupt();
        // A task may request shutdown from the game-loop thread itself.
        // It cannot join itself; the runLoop finally block completes shutdown.
        if (thread == Thread.currentThread()) {
            return;
        }
        thread.join(STOP_JOIN_MILLIS);
        if (thread.isAlive()) {
            throw new IllegalStateException(
                    "Game loop did not stop within " + STOP_JOIN_MILLIS + " ms"
            );
        }
        worker = null;
    }

    public boolean isRunning() {
        return running.get();
    }

    /** Configured target tick rate. */
    public int tickRate() {
        return (int) (NANOS_PER_SECOND / tickPeriodNanos);
    }

    public long executedTicks() {
        return executedTicks.get();
    }

    /** Immutable snapshot of low-overhead tick timing counters. */
    public TickMetrics.Snapshot metrics() {
        return tickMetrics.snapshot();
    }

    /** Number of times excessive accumulated lag was discarded. */
    public long droppedBacklogs() {
        return droppedBacklogs.get();
    }

    /** Most recent scheduler or failure-handler exception, if any. */
    public RuntimeException lastFailure() {
        return lastFailure.get();
    }

    private void runLoop() {
        long nextTick = System.nanoTime() + tickPeriodNanos;
        try {
            while (running.get()) {
                long now = System.nanoTime();
                long remaining = nextTick - now;
                if (remaining > 0) {
                    LockSupport.parkNanos(this, remaining);
                    continue;
                }

                int catchUp = 0;
                do {
                    if (!running.get()) {
                        break;
                    }
                    executeTick();
                    catchUp++;
                    nextTick += tickPeriodNanos;
                    now = System.nanoTime();
                } while (now >= nextTick && catchUp < maxCatchUpTicks);

                if (running.get() && now >= nextTick) {
                    // Drop the unbounded backlog and resume from a fresh deadline.
                    droppedBacklogs.incrementAndGet();
                    nextTick = now + tickPeriodNanos;
                }
            }
        } finally {
            running.set(false);
        }
    }

    private void executeTick() {
        long startedAt = System.nanoTime();
        try {
            scheduler.tick();
        } catch (RuntimeException failure) {
            lastFailure.set(failure);
            try {
                failureHandler.accept(failure);
            } catch (RuntimeException handlerFailure) {
                failure.addSuppressed(handlerFailure);
            }
        } finally {
            long duration = Math.max(0L, System.nanoTime() - startedAt);
            tickMetrics.record(duration, tickPeriodNanos);
            executedTicks.incrementAndGet();
        }
    }
}
