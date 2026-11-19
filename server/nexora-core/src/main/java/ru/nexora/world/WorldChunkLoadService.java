package ru.nexora.world;

import java.io.IOException;
import java.util.Optional;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
    private final Map<String, ArrayDeque<Runnable>> pendingBySession = new HashMap<>();
    private final ArrayDeque<String> sessionRotation = new ArrayDeque<>();
    private final Set<String> activeSessions = new HashSet<>();
    private final Set<CompletableFuture<Optional<WorldChunk>>> queuedFutures = new HashSet<>();
    private final int workers;
    private final int queueCapacity;
    private int queuedLoads;
    private int dispatchedLoads;
    private boolean closed;

    public WorldChunkLoadService(WorldStorage storage) {
        this(storage::loadChunk, DEFAULT_WORKERS, DEFAULT_QUEUE_CAPACITY);
    }

    WorldChunkLoadService(ChunkLoader loader, int workers, int queueCapacity) {
        if (workers < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("Chunk I/O workers and queue capacity must be positive");
        }
        this.loader = java.util.Objects.requireNonNull(loader, "loader");
        this.workers = workers;
        this.queueCapacity = queueCapacity;
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "nexora-chunk-io-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    /** Starts or joins one storage read, scheduling at most one active read per session. */
    public CompletableFuture<Optional<WorldChunk>> request(int chunkX, int chunkZ) {
        return request("default", chunkX, chunkZ);
    }

    /**
     * Per-session round-robin admission prevents one player's view from occupying
     * every worker and queued slot. Duplicate coordinates still share one future.
     */
    public CompletableFuture<Optional<WorldChunk>> request(String sessionKey, int chunkX, int chunkZ) {
        java.util.Objects.requireNonNull(sessionKey, "sessionKey");
        if (sessionKey.isBlank()) throw new IllegalArgumentException("sessionKey must not be blank");
        long key = ((long) chunkX << 32) | (chunkZ & 0xffff_ffffL);
        synchronized (this) {
            if (closed) {
                return CompletableFuture.failedFuture(new IllegalStateException("Chunk load service is closed"));
            }
            CompletableFuture<Optional<WorldChunk>> existing = inFlight.get(key);
            if (existing != null) return existing;
            if (queuedLoads >= queueCapacity) {
                return CompletableFuture.failedFuture(new RejectedExecutionException(
                        "NEXORA chunk I/O fair queue is full"));
            }
            CompletableFuture<Optional<WorldChunk>> result = new CompletableFuture<>();
            existing = inFlight.putIfAbsent(key, result);
            if (existing != null) return existing;
            ArrayDeque<Runnable> sessionQueue = pendingBySession.computeIfAbsent(sessionKey, ignored -> {
                sessionRotation.addLast(sessionKey);
                return new ArrayDeque<>();
            });
            queuedFutures.add(result);
            sessionQueue.addLast(() -> executeLoad(key, chunkX, chunkZ, result, sessionKey));
            queuedLoads++;
            dispatchFairly();
            return result;
        }
    }

    private void executeLoad(long key, int chunkX, int chunkZ,
                             CompletableFuture<Optional<WorldChunk>> result, String sessionKey) {
        synchronized (this) {
            queuedFutures.remove(result);
        }
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
                    synchronized (WorldChunkLoadService.this) {
                        activeSessions.remove(sessionKey);
                        dispatchedLoads--;
                        dispatchFairly();
                    }
                }
            });
        } catch (RejectedExecutionException saturated) {
            result.completeExceptionally(saturated);
            inFlight.remove(key, result);
            synchronized (this) {
                activeSessions.remove(sessionKey);
                dispatchedLoads--;
                dispatchFairly();
            }
        }
    }

    /** Called only while holding this service's monitor. */
    private void dispatchFairly() {
        if (closed) return;
        int dispatchLimit = workers;
        while (dispatchedLoads < dispatchLimit && !sessionRotation.isEmpty()) {
            int candidates = sessionRotation.size();
            boolean dispatched = false;
            for (int i = 0; i < candidates; i++) {
                String sessionKey = sessionRotation.removeFirst();
                ArrayDeque<Runnable> queue = pendingBySession.get(sessionKey);
                if (queue == null || queue.isEmpty()) {
                    pendingBySession.remove(sessionKey);
                    continue;
                }
                if (activeSessions.contains(sessionKey)) {
                    sessionRotation.addLast(sessionKey);
                    continue;
                }
                Runnable task = queue.removeFirst();
                queuedLoads--;
                activeSessions.add(sessionKey);
                dispatchedLoads++;
                if (queue.isEmpty()) {
                    pendingBySession.remove(sessionKey);
                } else {
                    sessionRotation.addLast(sessionKey);
                }
                task.run();
                dispatched = true;
                break;
            }
            if (!dispatched) break;
        }
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

    public synchronized int queuedLoads() {
        return queuedLoads + executor.getQueue().size();
    }

    public int inFlightLoads() {
        return inFlight.size();
    }

    public synchronized int remainingQueueCapacity() {
        return Math.max(0, queueCapacity - queuedLoads);
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            pendingBySession.clear();
            sessionRotation.clear();
            queuedLoads = 0;
            for (CompletableFuture<Optional<WorldChunk>> future : queuedFutures) {
                future.completeExceptionally(new IllegalStateException("Chunk load service is closed"));
                inFlight.values().remove(future);
            }
            queuedFutures.clear();
        }
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
