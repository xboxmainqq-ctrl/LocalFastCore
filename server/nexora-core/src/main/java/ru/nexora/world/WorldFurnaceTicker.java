package ru.nexora.world;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Advances loaded furnaces at 20 server ticks per second. */
public final class WorldFurnaceTicker implements AutoCloseable {
    private final World world;
    private final Consumer<RuntimeException> errorHandler;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    public WorldFurnaceTicker(World world, Consumer<RuntimeException> errorHandler) {
        this.world = Objects.requireNonNull(world, "world");
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "nexora-furnace-ticker");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(this::tickSafely, 50, 50, TimeUnit.MILLISECONDS);
    }

    private void tickSafely() {
        if (closed.get()) return;
        try {
            world.furnaces().ifPresent(manager -> manager.tickAll(1));
        } catch (RuntimeException exception) {
            try { errorHandler.accept(exception); }
            catch (RuntimeException ignored) {
                // A logging callback must not terminate the furnace ticker.
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting for furnace ticker to stop");
                }
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while stopping furnace ticker", exception);
        }
    }
}
