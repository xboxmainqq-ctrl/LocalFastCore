package ru.nexora.runtime;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Bounded asynchronous chunk generation.
 *
 * <p>Workers only create immutable snapshots. They never mutate a world or
 * perform network or storage operations. The game thread should call
 * {@link #drainCompleted(int, Consumer)} and apply successful results.</p>
 */
public final class AsyncChunkGenerationService implements RuntimeModule {
    private static final long STOP_TIMEOUT_SECONDS = 5L;

    private final ChunkGenerator generator;
    private final WorldHeight height;
    private final long seed;
    private final int workerCount;
    private final int queueCapacity;
    private final int completionCapacity;
    private final ConcurrentMap<ChunkPosition, Request> inFlight = new ConcurrentHashMap<>();
    private final ArrayBlockingQueue<Completed> completed;
    private final AtomicLong acceptedRequests = new AtomicLong();
    private final AtomicLong deduplicatedRequests = new AtomicLong();
    private final AtomicLong rejectedRequests = new AtomicLong();
    private final AtomicLong cancelledRequests = new AtomicLong();
    private final AtomicLong generatedCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();
    private final AtomicLong droppedResults = new AtomicLong();
    private volatile ThreadPoolExecutor executor;
    private volatile boolean running;

    public AsyncChunkGenerationService(
            ChunkGenerator generator,
            WorldHeight height,
            long seed,
            int workerCount,
            int queueCapacity,
            int completionCapacity
    ) {
        this.generator = Objects.requireNonNull(generator, "generator");
        this.height = Objects.requireNonNull(height, "height");
        if (workerCount < 1 || workerCount > 32) {
            throw new IllegalArgumentException("Worker count must be between 1 and 32");
        }
        if (queueCapacity < 1 || queueCapacity > 65_536) {
            throw new IllegalArgumentException("Queue capacity must be between 1 and 65536");
        }
        if (completionCapacity < 1 || completionCapacity > 65_536) {
            throw new IllegalArgumentException("Completion capacity must be between 1 and 65536");
        }
        this.seed = seed;
        this.workerCount = workerCount;
        this.queueCapacity = queueCapacity;
        this.completionCapacity = completionCapacity;
        this.completed = new ArrayBlockingQueue<>(completionCapacity);
    }

    @Override
    public String id() {
        return "chunk-generation";
    }

    @Override
    public synchronized void start() {
        if (running) {
            throw new IllegalStateException("Chunk generation service is already running");
        }
        if (executor != null && !executor.isTerminated()) {
            throw new IllegalStateException("Previous chunk workers are still stopping");
        }
        ThreadFactory factory = new ThreadFactory() {
            private int sequence;

            @Override
            public synchronized Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "nexora-chunk-worker-" + (++sequence));
                thread.setDaemon(true);
                return thread;
            }
        };
        executor = new ThreadPoolExecutor(
                workerCount,
                workerCount,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                factory,
                new ThreadPoolExecutor.AbortPolicy()
        );
        running = true;
    }

    /**
     * Schedules a chunk unless it is already pending or the bounded worker
     * queue is full. A pending request remains reserved until its result is
     * consumed or cancelled.
     */
    public synchronized RequestStatus request(ChunkPosition position) {
        Objects.requireNonNull(position, "position");
        if (!running || executor == null) {
            return RequestStatus.STOPPED;
        }
        Request request = new Request(position);
        Request existing = inFlight.putIfAbsent(position, request);
        if (existing != null) {
            deduplicatedRequests.incrementAndGet();
            return RequestStatus.ALREADY_PENDING;
        }
        request.task = new FutureTask<>(() -> {
            generate(request);
            return null;
        });
        try {
            executor.execute(request.task);
            acceptedRequests.incrementAndGet();
            return RequestStatus.ACCEPTED;
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            inFlight.remove(position, request);
            rejectedRequests.incrementAndGet();
            return RequestStatus.QUEUE_FULL;
        }
    }

    /** Cancels queued work or interrupts a running generator when possible. */
    public synchronized boolean cancel(ChunkPosition position) {
        Objects.requireNonNull(position, "position");
        Request request = inFlight.remove(position);
        if (request == null) {
            return false;
        }
        request.cancelled = true;
        cancelledRequests.incrementAndGet();
        FutureTask<Void> task = request.task;
        if (task != null) {
            task.cancel(true);
            ThreadPoolExecutor current = executor;
            if (current != null) {
                current.remove(task);
            }
        }
        completed.removeIf(item -> item.request == request);
        return true;
    }

    /**
     * Delivers at most {@code maximum} results to the caller. Call this from
     * the game thread; apply successful chunks to the world inside the handler.
     */
    public int drainCompleted(int maximum, Consumer<ChunkGenerationResult> handler) {
        if (maximum < 1) {
            throw new IllegalArgumentException("Maximum results must be positive");
        }
        Objects.requireNonNull(handler, "handler");
        int delivered = 0;
        while (delivered < maximum) {
            Completed item = completed.poll();
            if (item == null) {
                break;
            }
            try {
                if (!item.request.cancelled
                        && inFlight.get(item.request.position) == item.request) {
                    handler.accept(item.result);
                    delivered++;
                }
            } finally {
                inFlight.remove(item.request.position, item.request);
            }
        }
        return delivered;
    }

    public int pendingCount() {
        return inFlight.size();
    }

    /** Returns whether this exact chunk still has a queued, running, or completed request. */
    public boolean isPending(ChunkPosition position) {
        return inFlight.containsKey(Objects.requireNonNull(position, "position"));
    }

    public int queuedWorkerCount() {
        ThreadPoolExecutor current = executor;
        return current == null ? 0 : current.getQueue().size();
    }

    public int completedCount() {
        return completed.size();
    }

    /** Cumulative request counters; queue sizes and pending count remain live gauges. */
    public Metrics metrics() {
        ThreadPoolExecutor current = executor;
        return new Metrics(
                acceptedRequests.get(), deduplicatedRequests.get(), rejectedRequests.get(),
                cancelledRequests.get(), generatedCount.get(), failedCount.get(), droppedResults.get(),
                pendingCount(), current == null ? 0 : current.getQueue().size(), completed.size()
        );
    }

    public long generatedCount() {
        return generatedCount.get();
    }

    public long failedCount() {
        return failedCount.get();
    }

    public long droppedResults() {
        return droppedResults.get();
    }

    @Override
    public synchronized void stop() throws InterruptedException {
        ThreadPoolExecutor current = executor;
        if (current == null) {
            running = false;
            return;
        }
        running = false;
        current.shutdownNow();
        if (!current.awaitTermination(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Chunk workers did not stop within "
                    + STOP_TIMEOUT_SECONDS + " seconds");
        }
        inFlight.clear();
        completed.clear();
        executor = null;
    }

    private void generate(Request request) {
        try {
            GeneratedChunk chunk = generator.generate(request.position, height, seed);
            if (request.cancelled || inFlight.get(request.position) != request) {
                return;
            }
            generatedCount.incrementAndGet();
            publish(request, new ChunkGenerationResult(request.position, chunk, null));
        } catch (RuntimeException failure) {
            if (request.cancelled || inFlight.get(request.position) != request) {
                return;
            }
            failedCount.incrementAndGet();
            publish(request, new ChunkGenerationResult(request.position, null, failure));
        } catch (Error fatal) {
            // Surface fatal generator failures through the bounded completion
            // channel before rethrowing. This lets the game-thread pipeline
            // resolve async waiters instead of leaving them incomplete forever.
            failedCount.incrementAndGet();
            publish(request, new ChunkGenerationResult(
                    request.position,
                    null,
                    new IllegalStateException("Chunk generator failed with a fatal error", fatal)
            ));
            throw fatal;
        }
    }

    private void publish(Request request, ChunkGenerationResult result) {
        Completed item = new Completed(request, result);
        try {
            // Keep the completion channel bounded without silently losing a
            // generated chunk. Workers apply backpressure until the game thread
            // drains a slot, or the request/service is cancelled or stopped.
            while (running && !request.cancelled
                    && inFlight.get(request.position) == request) {
                if (completed.offer(item, 100L, TimeUnit.MILLISECONDS)) {
                    return;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        inFlight.remove(request.position, request);
    }

    public record Metrics(
            long acceptedRequests,
            long deduplicatedRequests,
            long rejectedRequests,
            long cancelledRequests,
            long generatedCount,
            long failedCount,
            long droppedResults,
            int pendingCount,
            int queuedWorkerCount,
            int completedCount
    ) {
    }

    public enum RequestStatus {
        ACCEPTED,
        ALREADY_PENDING,
        QUEUE_FULL,
        STOPPED
    }

    private static final class Request {
        private final ChunkPosition position;
        private volatile FutureTask<Void> task;
        private volatile boolean cancelled;

        private Request(ChunkPosition position) {
            this.position = position;
        }
    }

    private record Completed(Request request, ChunkGenerationResult result) {
    }
}
