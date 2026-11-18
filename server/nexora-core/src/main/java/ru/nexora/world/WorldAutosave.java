package ru.nexora.world;

import ru.nexora.lifecycle.RuntimeTaskScheduler;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/** Periodic world persistence with a final synchronous save during shutdown. */
public final class WorldAutosave implements AutoCloseable {
    private final World world;
    private final Consumer<IOException> errorHandler;
    private final SaveHook additionalSave;
    private final LongConsumer executionObserver;
    private final RuntimeTaskScheduler scheduler;
    private final boolean ownsScheduler;
    private final RuntimeTaskScheduler.TaskHandle task;
    private final Object saveLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final LongAdder saveAttempts = new LongAdder();
    private final LongAdder successfulSaves = new LongAdder();
    private final LongAdder failedSaves = new LongAdder();
    private final LongAdder totalSaveNanos = new LongAdder();
    private final AtomicLong maxSaveNanos = new AtomicLong();
    private final AtomicLong lastSaveNanos = new AtomicLong();

    public WorldAutosave(World world, Duration interval, Consumer<IOException> errorHandler) {
        this(world, interval, errorHandler, () -> {}, ignored -> {});
    }

    public WorldAutosave(
            World world, Duration interval, Consumer<IOException> errorHandler, SaveHook additionalSave
    ) {
        this(world, interval, errorHandler, additionalSave, ignored -> {});
    }

    public WorldAutosave(
            World world, Duration interval, Consumer<IOException> errorHandler,
            SaveHook additionalSave, LongConsumer executionObserver
    ) {
        this(world, interval, errorHandler, additionalSave, executionObserver,
                new RuntimeTaskScheduler(), true);
    }

    public WorldAutosave(
            World world, Duration interval, Consumer<IOException> errorHandler,
            SaveHook additionalSave, LongConsumer executionObserver, RuntimeTaskScheduler scheduler
    ) {
        this(world, interval, errorHandler, additionalSave, executionObserver, scheduler, false);
    }

    private WorldAutosave(
            World world, Duration interval, Consumer<IOException> errorHandler,
            SaveHook additionalSave, LongConsumer executionObserver,
            RuntimeTaskScheduler scheduler, boolean ownsScheduler
    ) {
        this.world = Objects.requireNonNull(world, "world");
        Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative() || interval.toMillis() < 1) {
            throw new IllegalArgumentException("Autosave interval must be at least one millisecond");
        }
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
        this.additionalSave = Objects.requireNonNull(additionalSave, "additionalSave");
        this.executionObserver = Objects.requireNonNull(executionObserver, "executionObserver");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = ownsScheduler;
        long intervalMillis = interval.toMillis();
        this.task = scheduler.scheduleWithFixedDelay(RuntimeTaskScheduler.Lane.PERSISTENCE,
                this::saveSafely, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    public void saveNow() throws IOException {
        if (closed.get()) throw new IllegalStateException("World autosave is closed");
        synchronized (saveLock) {
            flushAllMeasured();
        }
    }

    private void saveSafely() {
        long started = System.nanoTime();
        try {
            synchronized (saveLock) {
                if (!closed.get()) flushAllMeasured();
            }
        } catch (IOException | RuntimeException exception) {
            IOException failure = exception instanceof IOException io
                    ? io : new IOException("Unexpected NEXORA autosave failure", exception);
            try {
                errorHandler.accept(failure);
            } catch (RuntimeException ignored) {
                // A logging callback must not terminate the scheduled autosave task.
            }
        } finally {
            try { executionObserver.accept(Math.max(0L, System.nanoTime() - started)); }
            catch (RuntimeException ignored) {
                // Diagnostics must not terminate the scheduled autosave task.
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        task.cancel();
        synchronized (saveLock) {
            // Wait for any in-flight periodic save before the final flush.
        }
        if (ownsScheduler) scheduler.close();
        synchronized (saveLock) {
            flushAllMeasured();
        }
    }

    /** Returns aggregate save measurements, including manual and final shutdown flushes. */
    public SaveStats saveStats() {
        long attempts = saveAttempts.sum();
        long total = totalSaveNanos.sum();
        return new SaveStats(attempts, successfulSaves.sum(), failedSaves.sum(),
                total, maxSaveNanos.get(), lastSaveNanos.get(),
                attempts == 0 ? 0.0 : total / (double) attempts / 1_000_000.0);
    }

    private void flushAllMeasured() throws IOException {
        long started = System.nanoTime();
        boolean failed = false;
        saveAttempts.increment();
        try {
            flushAll();
        } catch (IOException | RuntimeException exception) {
            failed = true;
            throw exception;
        } finally {
            long elapsed = Math.max(0L, System.nanoTime() - started);
            if (failed) failedSaves.increment();
            else successfulSaves.increment();
            totalSaveNanos.add(elapsed);
            maxSaveNanos.accumulateAndGet(elapsed, Math::max);
            lastSaveNanos.set(elapsed);
        }
    }

    private void flushAll() throws IOException {
        IOException failure = null;
        try {
            world.saveAll();
        } catch (IOException | RuntimeException exception) {
            failure = exception instanceof IOException io
                    ? io : new IOException("Unexpected world save failure", exception);
        }
        try {
            additionalSave.save();
        } catch (IOException | RuntimeException exception) {
            IOException playerFailure = exception instanceof IOException io
                    ? io : new IOException("Unexpected additional save failure", exception);
            if (failure == null) failure = playerFailure;
            else failure.addSuppressed(playerFailure);
        }
        if (failure != null) throw failure;
    }

    public record SaveStats(long attempts, long successfulSaves, long failedSaves,
                            long totalExecutionNanos, long maxExecutionNanos,
                            long lastExecutionNanos, double averageExecutionMillis) {
        public SaveStats {
            if (attempts < 0 || successfulSaves < 0 || failedSaves < 0
                    || successfulSaves + failedSaves > attempts
                    || totalExecutionNanos < 0 || maxExecutionNanos < 0
                    || lastExecutionNanos < 0 || !Double.isFinite(averageExecutionMillis)
                    || averageExecutionMillis < 0) {
                throw new IllegalArgumentException("Invalid world autosave statistics");
            }
        }
    }

    @FunctionalInterface
    public interface SaveHook {
        void save() throws IOException;
    }
}
