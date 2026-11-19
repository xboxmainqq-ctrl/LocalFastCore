package ru.nexora.world;

import ru.nexora.lifecycle.RuntimeTaskScheduler;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Owns world background services and their bounded scheduler.
 *
 * <p>Ticking and persistence use isolated scheduler lanes, so a slow save
 * cannot stall furnace updates. Shutdown cancels both jobs, waits for any
 * in-flight work, then performs the final world/player save.</p>
 */
public final class WorldRuntimeModule implements AutoCloseable {
    private final World world;
    private final WorldAutosave autosave;
    private final WorldFurnaceTicker furnaceTicker;
    private final RuntimeTaskScheduler scheduler;
    private final RuntimeTaskScheduler.TaskHandle leaseHeartbeat;
    private final Object heartbeatLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();

    public WorldRuntimeModule(
            World world,
            Duration autosaveInterval,
            Consumer<IOException> autosaveErrorHandler,
            WorldAutosave.SaveHook additionalSave,
            LongConsumer autosaveObserver,
            Consumer<RuntimeException> furnaceErrorHandler,
            LongConsumer furnaceObserver
    ) {
        this(world, autosaveInterval, autosaveErrorHandler, additionalSave,
                null, null, autosaveObserver, furnaceErrorHandler, furnaceObserver);
    }

    /**
     * Creates the world runtime with an independent monitoring-lane player-lease heartbeat.
     * The heartbeat is deliberately isolated from the furnace tick and persistence lanes.
     */
    public WorldRuntimeModule(
            World world,
            Duration autosaveInterval,
            Consumer<IOException> autosaveErrorHandler,
            WorldAutosave.SaveHook additionalSave,
            Runnable playerLeaseHeartbeat,
            Consumer<RuntimeException> leaseHeartbeatErrorHandler,
            LongConsumer autosaveObserver,
            Consumer<RuntimeException> furnaceErrorHandler,
            LongConsumer furnaceObserver
    ) {
        this.world = Objects.requireNonNull(world, "world");
        RuntimeTaskScheduler taskScheduler = new RuntimeTaskScheduler(8, Duration.ofSeconds(10));
        WorldFurnaceTicker ticker;
        try {
            ticker = new WorldFurnaceTicker(world, furnaceErrorHandler, furnaceObserver, taskScheduler);
        } catch (RuntimeException exception) {
            try {
                taskScheduler.close();
            } catch (IOException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
        WorldAutosave saveService;
        try {
            saveService = new WorldAutosave(world, autosaveInterval, autosaveErrorHandler,
                    additionalSave, autosaveObserver, taskScheduler);
        } catch (RuntimeException exception) {
            try {
                ticker.close();
            } catch (IOException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            try {
                taskScheduler.close();
            } catch (IOException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
        RuntimeTaskScheduler.TaskHandle heartbeat = null;
        if (playerLeaseHeartbeat != null) {
            Objects.requireNonNull(leaseHeartbeatErrorHandler, "leaseHeartbeatErrorHandler");
            try {
                heartbeat = taskScheduler.scheduleWithFixedDelay(
                        RuntimeTaskScheduler.Lane.MONITORING,
                        () -> {
                            synchronized (heartbeatLock) {
                                if (closed.get()) return;
                                try {
                                    playerLeaseHeartbeat.run();
                                } catch (RuntimeException failure) {
                                    try { leaseHeartbeatErrorHandler.accept(failure); }
                                    catch (RuntimeException ignored) {
                                        // Diagnostics must not terminate the heartbeat task.
                                    }
                                }
                            }
                        },
                        1, 1, TimeUnit.SECONDS);
            } catch (RuntimeException exception) {
                try { saveService.close(); } catch (IOException closeFailure) { exception.addSuppressed(closeFailure); }
                try { ticker.close(); } catch (IOException closeFailure) { exception.addSuppressed(closeFailure); }
                try { taskScheduler.close(); } catch (IOException closeFailure) { exception.addSuppressed(closeFailure); }
                throw exception;
            }
        }
        this.scheduler = taskScheduler;
        this.furnaceTicker = ticker;
        this.autosave = saveService;
        this.leaseHeartbeat = heartbeat;
    }

    public void saveNow() throws IOException {
        if (closed.get()) throw new IllegalStateException("World runtime module is closed");
        autosave.saveNow();
    }

    public boolean isClosed() {
        return closed.get();
    }

    public int registeredTickTasks() {
        return scheduler.registeredTasks(RuntimeTaskScheduler.Lane.TICK);
    }

    public int registeredPersistenceTasks() {
        return scheduler.registeredTasks(RuntimeTaskScheduler.Lane.PERSISTENCE);
    }

    public int registeredLeaseHeartbeatTasks() {
        return scheduler.registeredTasks(RuntimeTaskScheduler.Lane.MONITORING);
    }

    /** Aggregate persistence flush measurements, including the final shutdown save. */
    public WorldAutosave.SaveStats autosaveStats() {
        return autosave.saveStats();
    }

    /** Execution-time baseline for furnace/tick work; does not represent global server TPS. */
    public RuntimeTaskScheduler.LaneStats tickStats() {
        return scheduler.stats(RuntimeTaskScheduler.Lane.TICK);
    }

    /** Execution-time baseline for world/player persistence work. */
    public RuntimeTaskScheduler.LaneStats persistenceStats() {
        return scheduler.stats(RuntimeTaskScheduler.Lane.PERSISTENCE);
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        IOException failure = null;
        if (leaseHeartbeat != null) {
            leaseHeartbeat.cancel();
            synchronized (heartbeatLock) {
                // Wait for an in-flight lease check before final player persistence.
            }
        }
        try {
            furnaceTicker.close();
        } catch (IOException exception) {
            failure = exception;
        }
        try {
            autosave.close();
        } catch (IOException exception) {
            if (failure == null) failure = exception;
            else failure.addSuppressed(exception);
        }
        try {
            scheduler.close();
        } catch (IOException exception) {
            if (failure == null) failure = exception;
            else failure.addSuppressed(exception);
        }
        try {
            world.closeChunkLoadService();
        } catch (RuntimeException exception) {
            IOException closeFailure = new IOException("Unable to stop NEXORA chunk I/O workers", exception);
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        if (failure != null) throw failure;
    }
}
