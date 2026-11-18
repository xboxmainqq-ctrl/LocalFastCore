package ru.nexora.world;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Owns the background runtime services attached to a world.
 *
 * <p>The furnace ticker is stopped before the final autosave, ensuring that
 * world state is no longer being advanced while shutdown persistence runs.</p>
 */
public final class WorldRuntimeModule implements AutoCloseable {
    private final WorldAutosave autosave;
    private final WorldFurnaceTicker furnaceTicker;
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
        WorldFurnaceTicker ticker = new WorldFurnaceTicker(
                world, furnaceErrorHandler, furnaceObserver);
        WorldAutosave saveService;
        try {
            saveService = new WorldAutosave(world, autosaveInterval, autosaveErrorHandler,
                    additionalSave, autosaveObserver);
        } catch (RuntimeException exception) {
            try {
                ticker.close();
            } catch (IOException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
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
        if (failure != null) throw failure;
    }
}
