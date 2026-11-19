package ru.nexora.world;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded asynchronous disk reads for persisted chunks.
 *
 * <p>Only storage I/O runs on worker threads. World generation and installation
 * remain on the caller thread so generators and live world state need not be
 * thread-safe.</p>
 */
public final class WorldChunkLoadService implements AutoCloseable {
    private static final int DEFAULT_WORKERS = 2;
    private static final int DEFAULT_QUEUE_CAPACITY = 128;

    @FunctionalInterface
    interface ChunkLoader {
        Optional<WorldChunk> load(int chunkX, int chunkZ) throws IOException;
    }

    private final ChunkLoader loader;
    private final ThreadPoolExecutor executor;
    private final ConcurrentMap<Long, CompletableFuture<Optional<WorldChunk>>> inFlight =
            new ConcurrentHashMap<>();

    public WorldChunkLoadService(WorldStorage storage) {
        this(storage::loadChunk, DEFAULT_WORKERS, DEFAULT_QUEUE_CAPACITY);
    }

    WorldChunkLoadService(ChunkLoader loader, int workers, int queueCapacity) {
        if (workers < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("Chunk I/O workers and queue capacity must be positive");
        }
        this.loader = java.util.Objects.requireNonNull(loader, "loader");
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "nexora-chunk-io-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    /** Starts or joins one storage read. Identical coordinates share the same future while in flight. */
    public CompletableFuture<Optional<WorldChunk>> request(int chunkX, int chunkZ) {
        if (executor.isShutdown()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Chunk load service is closed"));
        }
        long key = ((long) chunkX << 32) | (chunkZ & 0xffff_ffffL);
        CompletableFuture<Optional<WorldChunk>> existing = inFlight.get(key);
        if (existing != null) return existing;
        CompletableFuture<Optional<WorldChunk>> result = new CompletableFuture<>();
        existing = inFlight.putIfAbsent(key, result);
        if (existing != null) return existing;
        try {
            executor.execute(() -> {
                try {
                    result.complete(loader.load(chunkX, chunkZ));
                } catch (IOException failure) {
                    result.completeExceptionally(new java.io.UncheckedIOException(
                            "Unable to asynchronously load NEXORA chunk " + chunkX + "," + chunkZ, failure));
                } catch (RuntimeException failure) {
                    result.completeExceptionally(failure);
                } finally {
                    inFlight.remove(key, result);
                }
            });
        } catch (RejectedExecutionException saturated) {
            result.completeExceptionally(saturated);
            inFlight.remove(key, result);
        }
        return result;
    }

    /** Synchronous correctness fallback used when the bounded worker queue is saturated. */
    public Optional<WorldChunk> loadSynchronously(int chunkX, int chunkZ) {
        try {
            return loader.load(chunkX, chunkZ);
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(
                    "Unable to load NEXORA chunk " + chunkX + "," + chunkZ, failure);
        }
    }

    public int activeWorkers() {
        return executor.getActiveCount();
    }

    public int queuedLoads() {
        return executor.getQueue().size();
    }

    public int remainingQueueCapacity() {
        return executor.getQueue().remainingCapacity();
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("NEXORA chunk I/O workers did not stop");
                }
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw new CompletionException("Interrupted while stopping NEXORA chunk I/O workers", interrupted);
        }
    }
}
