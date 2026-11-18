package ru.nexora.world;

import ru.nexora.lifecycle.RuntimeTaskScheduler;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/** Advances loaded furnaces at 20 server ticks per second. */
public final class WorldFurnaceTicker implements AutoCloseable {
    private final World world;
    private final Consumer<RuntimeException> errorHandler;
    private final LongConsumer executionObserver;
    private final RuntimeTaskScheduler scheduler;
    private final boolean ownsScheduler;
    private final RuntimeTaskScheduler.TaskHandle task;
    private final Object tickLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();

    public WorldFurnaceTicker(World world, Consumer<RuntimeException> errorHandler) {
        this(world, errorHandler, ignored -> {});
    }

    public WorldFurnaceTicker(
            World world, Consumer<RuntimeException> errorHandler, LongConsumer executionObserver
    ) {
        this(world, errorHandler, executionObserver, new RuntimeTaskScheduler(), true);
    }

    public WorldFurnaceTicker(
            World world,
            Consumer<RuntimeException> errorHandler,
            LongConsumer executionObserver,
            RuntimeTaskScheduler scheduler
    ) {
        this(world, errorHandler, executionObserver, scheduler, false);
    }

    private WorldFurnaceTicker(
            World world,
            Consumer<RuntimeException> errorHandler,
            LongConsumer executionObserver,
            RuntimeTaskScheduler scheduler,
            boolean ownsScheduler
    ) {
        this.world = Objects.requireNonNull(world, "world");
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
        this.executionObserver = Objects.requireNonNull(executionObserver, "executionObserver");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = ownsScheduler;
        this.task = scheduler.scheduleAtFixedRate(RuntimeTaskScheduler.Lane.TICK,
                this::tickSafely, 50, 50, TimeUnit.MILLISECONDS);
    }

    private void tickSafely() {
        synchronized (tickLock) {
            if (closed.get()) return;
            long started = System.nanoTime();
            try {
                world.furnaces().ifPresent(manager -> manager.tickAll(1));
            } catch (RuntimeException exception) {
                try { errorHandler.accept(exception); }
                catch (RuntimeException ignored) {
                    // A logging callback must not terminate the furnace ticker.
                }
            } finally {
                try { executionObserver.accept(Math.max(0L, System.nanoTime() - started)); }
                catch (RuntimeException ignored) {
                    // Diagnostics must not terminate the furnace ticker.
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        task.cancel();
        synchronized (tickLock) {
            // Wait for an in-flight tick before world persistence begins.
        }
        if (ownsScheduler) scheduler.close();
    }
}
