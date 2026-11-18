package ru.nexora.world;

import ru.nexora.lifecycle.RuntimeTaskScheduler;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
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
    private final WorldAutosave autosave;
    private final WorldFurnaceTicker furnaceTicker;
    private final RuntimeTaskScheduler scheduler;
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
        Objects.requireNonNull(world, "world");
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
        this.scheduler = taskScheduler;
        this.furnaceTicker = ticker;
        this.autosave = saveService;
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

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        IOException failure = null;
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
        if (failure != null) throw failure;
    }
}
