package ru.nexora.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Connects background chunk generation to the game tick scheduler.
 *
 * <p>Register this module before {@link GameLoop}. Its scheduled handoff then
 * runs on the game-loop thread, where generated chunks are committed to the
 * world. Do not separately register its owned generation service.</p>
 */
public final class WorldChunkPipeline implements RuntimeModule {
    private final World world;
    private final TickScheduler scheduler;
    private final AsyncChunkGenerationService generation;
    private final int maximumLoadsPerTick;
    private final Consumer<RuntimeException> failureHandler;
    private final AtomicLong loadedChunks = new AtomicLong();
    private volatile boolean started;
    private TickScheduler.TaskHandle drainTask;

    public WorldChunkPipeline(
            World world,
            TickScheduler scheduler,
            AsyncChunkGenerationService generation,
            int maximumLoadsPerTick,
            Consumer<RuntimeException> failureHandler
    ) {
        this.world = Objects.requireNonNull(world, "world");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.generation = Objects.requireNonNull(generation, "generation");
        if (maximumLoadsPerTick < 1 || maximumLoadsPerTick > 4096) {
            throw new IllegalArgumentException("Maximum chunk loads per tick must be between 1 and 4096");
        }
        this.maximumLoadsPerTick = maximumLoadsPerTick;
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
    }

    @Override
    public String id() {
        return "world-chunk-pipeline";
    }

    @Override
    public synchronized void start() throws Exception {
        if (started) {
            throw new IllegalStateException("World chunk pipeline is already running");
        }
        generation.start();
        try {
            drainTask = scheduler.scheduleRepeating(1, 1, this::drainTick);
            started = true;
        } catch (RuntimeException | Error failure) {
            try {
                generation.stop();
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /** Requests a chunk without blocking the calling thread. */
    public synchronized RequestStatus request(ChunkPosition position) {
        Objects.requireNonNull(position, "position");
        if (!started) {
            return RequestStatus.STOPPED;
        }
        if (world.isChunkLoaded(position)) {
            return RequestStatus.ALREADY_LOADED;
        }
        return switch (generation.request(position)) {
            case ACCEPTED -> RequestStatus.ACCEPTED;
            case ALREADY_PENDING -> RequestStatus.ALREADY_PENDING;
            case QUEUE_FULL -> RequestStatus.QUEUE_FULL;
            case STOPPED -> RequestStatus.STOPPED;
        };
    }

    public synchronized boolean cancel(ChunkPosition position) {
        Objects.requireNonNull(position, "position");
        return started && generation.cancel(position);
    }

    public long loadedChunkCount() {
        return loadedChunks.get();
    }

    public int pendingCount() {
        return generation.pendingCount();
    }

    public int completedCount() {
        return generation.completedCount();
    }

    @Override
    public synchronized void stop() throws Exception {
        if (drainTask != null) {
            drainTask.cancel();
            drainTask = null;
        }
        started = false;
        generation.stop();
    }

    private void drainTick() {
        if (!started) {
            return;
        }
        generation.drainCompleted(maximumLoadsPerTick, result -> {
            if (!result.succeeded()) {
                failureHandler.accept(result.failure());
                return;
            }
            try {
                if (world.loadChunk(result.chunk())) {
                    loadedChunks.incrementAndGet();
                }
            } catch (RuntimeException failure) {
                failureHandler.accept(failure);
            }
        });
    }

    public enum RequestStatus {
        ACCEPTED,
        ALREADY_LOADED,
        ALREADY_PENDING,
        QUEUE_FULL,
        STOPPED
    }
}
