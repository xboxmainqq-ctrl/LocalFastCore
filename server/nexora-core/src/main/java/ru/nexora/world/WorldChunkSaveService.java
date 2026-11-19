package ru.nexora.world;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded asynchronous chunk writer.
 *
 * <p>At most one write task exists per chunk. Concurrent save requests coalesce,
 * while the worker repeats a write if the chunk changed during disk I/O. The
 * worker only consumes detached chunk snapshots; it never mutates world state.</p>
 */
public final class WorldChunkSaveService implements AutoCloseable {
    private static final int DEFAULT_WORKERS = 2;
    private static final int DEFAULT_QUEUE_CAPACITY = 128;

    private final WorldStorage storage;
    private final ThreadPoolExecutor executor;
    private final ConcurrentMap<Long, SaveState> inFlight = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public WorldChunkSaveService(WorldStorage storage) {
        this(storage, DEFAULT_WORKERS, DEFAULT_QUEUE_CAPACITY);
    }

    WorldChunkSaveService(WorldStorage storage, int workers, int queueCapacity) {
        if (workers < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("Chunk save workers and queue capacity must be positive");
        }
        this.storage = java.util.Objects.requireNonNull(storage, "storage");
        AtomicInteger number = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "nexora-chunk-save-" + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        executor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Schedules a chunk write or joins the existing write for that coordinate.
     * Queue saturation is explicit: callers must retry or use a synchronous save.
     */
    public synchronized CompletableFuture<Void> request(WorldChunk chunk) {
        java.util.Objects.requireNonNull(chunk, "chunk");
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Chunk save service is closed"));
        }
        if (!chunk.isDirty()) return CompletableFuture.completedFuture(null);
        long key = key(chunk.chunkX(), chunk.chunkZ());
        SaveState existing = inFlight.get(key);
        if (existing != null) return existing.completion;

        SaveState state = new SaveState(chunk);
        inFlight.put(key, state);
        try {
            executor.execute(() -> persist(key, state));
        } catch (RejectedExecutionException saturated) {
            inFlight.remove(key, state);
            state.completion.completeExceptionally(saturated);
        }
        return state.completion;
    }

    private void persist(long key, SaveState state) {
        try {
            while (true) {
                WorldChunk.PersistenceSnapshot snapshot = state.chunk.persistenceSnapshot();
                storage.saveChunkSnapshot(state.chunk.chunkX(), state.chunk.chunkZ(), snapshot.sections());
                boolean complete;
                synchronized (this) {
                    synchronized (state.chunk) {
                        state.chunk.markPersisted(snapshot.revision());
                        complete = !state.chunk.isDirty();
                        if (complete) inFlight.remove(key, state);
                    }
                }
                if (complete) {
                    state.completion.complete(null);
                    return;
                }
            }
        } catch (IOException | RuntimeException failure) {
            synchronized (this) {
                inFlight.remove(key, state);
            }
            state.completion.completeExceptionally(failure);
        }
    }

    public int activeWorkers() {
        return executor.getActiveCount();
    }

    public int queuedSaves() {
        return executor.getQueue().size();
    }

    public int inFlightSaves() {
        return inFlight.size();
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffff_ffffL);
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("NEXORA chunk save workers did not stop");
                }
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw new CompletionException("Interrupted while stopping NEXORA chunk save workers", interrupted);
        }
    }

    private static final class SaveState {
        private final WorldChunk chunk;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();

        private SaveState(WorldChunk chunk) {
            this.chunk = chunk;
        }
    }
}
