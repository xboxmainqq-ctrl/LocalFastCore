package ru.nexora.world;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Periodic world persistence with a final synchronous save during shutdown. */
public final class WorldAutosave implements AutoCloseable {
    private final World world;
    private final Consumer<IOException> errorHandler;
    private final SaveHook additionalSave;
    private final ScheduledExecutorService executor;
    private final Object saveLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();

    public WorldAutosave(World world, Duration interval, Consumer<IOException> errorHandler) {
        this(world, interval, errorHandler, () -> {});
    }

    public WorldAutosave(
            World world, Duration interval, Consumer<IOException> errorHandler, SaveHook additionalSave
    ) {
        this.world = Objects.requireNonNull(world, "world");
        Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative() || interval.toMillis() < 1) {
            throw new IllegalArgumentException("Autosave interval must be at least one millisecond");
        }
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
        this.additionalSave = Objects.requireNonNull(additionalSave, "additionalSave");
        this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "nexora-world-autosave");
            thread.setDaemon(true);
            return thread;
        });
        long intervalMillis = interval.toMillis();
        executor.scheduleWithFixedDelay(this::saveSafely,
                intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    public void saveNow() throws IOException {
        if (closed.get()) throw new IllegalStateException("World autosave is closed");
        synchronized (saveLock) {
            world.saveAll();
            additionalSave.save();
        }
    }

    private void saveSafely() {
        try {
            synchronized (saveLock) {
                if (!closed.get()) {
                    world.saveAll();
                    additionalSave.save();
                }
            }
        } catch (IOException | RuntimeException exception) {
            IOException failure = exception instanceof IOException io
                    ? io : new IOException("Unexpected NEXORA autosave failure", exception);
            try {
                errorHandler.accept(failure);
            } catch (RuntimeException ignored) {
                // A logging callback must not terminate the scheduled autosave task.
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting for NEXORA autosave to stop");
                }
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while stopping NEXORA autosave", exception);
        }
        synchronized (saveLock) {
            world.saveAll();
            additionalSave.save();
        }
    }

    @FunctionalInterface
    public interface SaveHook {
        void save() throws IOException;
    }
}
