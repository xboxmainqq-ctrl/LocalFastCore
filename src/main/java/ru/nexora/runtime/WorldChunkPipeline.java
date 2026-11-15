package ru.nexora.runtime;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
    private final ConcurrentMap<ChunkPosition, CompletableFuture<Boolean>> asyncWaiters =
            new ConcurrentHashMap<>();
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

    /**
     * Requests a chunk and completes when the game tick has committed it to
     * the world. Repeated callers share one future and never trigger duplicate
     * generation. This API is safe to call from network callbacks.
     */
    public synchronized CompletableFuture<Boolean> requestAsync(ChunkPosition position) {
        Objects.requireNonNull(position, "position");
        if (!started) return CompletableFuture.completedFuture(false);
        if (world.isChunkLoaded(position)) return CompletableFuture.completedFuture(true);

        CompletableFuture<Boolean> existing = asyncWaiters.get(position);
        if (existing != null) return existing;

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        asyncWaiters.put(position, future);
        RequestStatus status = request(position);
        if (status == RequestStatus.ALREADY_LOADED) {
            asyncWaiters.remove(position, future);
            future.complete(true);
        } else if (status != RequestStatus.ACCEPTED && status != RequestStatus.ALREADY_PENDING) {
            asyncWaiters.remove(position, future);
            future.complete(false);
        }
        return future;
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
        asyncWaiters.forEach((position, future) -> future.complete(false));
        asyncWaiters.clear();
    }

    private void drainTick() {
        if (!started) {
            return;
        }
        generation.drainCompleted(maximumLoadsPerTick, result -> {
            boolean available = false;
            if (!result.succeeded()) {
                failureHandler.accept(result.failure());
            } else {
                try {
                    available = world.isChunkLoaded(result.position()) || world.loadChunk(result.chunk());
                    if (available) loadedChunks.incrementAndGet();
                } catch (RuntimeException failure) {
                    failureHandler.accept(failure);
                }
            }
            CompletableFuture<Boolean> waiter = asyncWaiters.remove(result.position());
            if (waiter != null) waiter.complete(available);
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
