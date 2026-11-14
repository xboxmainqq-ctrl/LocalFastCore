package ru.nexora.core.profile;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded asynchronous facade for RPG profile operations.
 *
 * All queued work uses UUIDs and plain values; no live Nukkit objects are
 * captured or accessed by the worker thread. A single worker preserves the
 * order of profile mutations submitted through this facade.
 */
public final class AsyncPlayerProgressionService implements AutoCloseable {
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private final PlayerProgressionService progression;
    private final ThreadPoolExecutor executor;
    private volatile boolean closed;

    public AsyncPlayerProgressionService(PlayerProgressionService progression, int queueCapacity) {
        this.progression = Objects.requireNonNull(progression, "progression");
        if (queueCapacity < 1 || queueCapacity > 100_000) {
            throw new IllegalArgumentException("queueCapacity must be between 1 and 100000");
        }
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "NEXORA-RPG-IO-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), factory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    public CompletableFuture<PlayerProfile> loadOrCreate(UUID playerId, String currentName) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(currentName, "currentName");
        return submit(() -> progression.loadOrCreate(playerId, currentName));
    }

    public CompletableFuture<PlayerProgressionService.ProgressResult> addExperience(
            UUID playerId, long amount) {
        Objects.requireNonNull(playerId, "playerId");
        return submit(() -> progression.addExperience(playerId, amount));
    }

    public CompletableFuture<PlayerProfile> chooseClass(UUID playerId, String classId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(classId, "classId");
        return submit(() -> progression.chooseClass(playerId, classId));
    }

    public CompletableFuture<PlayerProfile> spendAttributePoint(UUID playerId, String attribute) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(attribute, "attribute");
        return submit(() -> progression.spendAttributePoint(playerId, attribute));
    }

    public int queuedTaskCount() {
        return executor.getQueue().size();
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * Stops accepting new work and waits for queued operations to finish.
     * If the timeout expires, queued tasks are cancelled and reported through
     * their returned futures; an already-running JDBC call is allowed to exit.
     */
    public boolean closeGracefully(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout cannot be negative");
        }
        closed = true;
        executor.shutdown();
        try {
            if (executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return true;
            }
            List<Runnable> pending = executor.shutdownNow();
            CancellationException cancellation =
                    new CancellationException("NEXORA RPG operation cancelled during shutdown");
            for (Runnable task : pending) {
                if (task instanceof QueuedTask<?> queuedTask) {
                    queuedTask.cancelBeforeRun(cancellation);
                }
            }
            return executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            List<Runnable> pending = executor.shutdownNow();
            CancellationException cancellation =
                    new CancellationException("NEXORA RPG shutdown was interrupted");
            for (Runnable task : pending) {
                if (task instanceof QueuedTask<?> queuedTask) {
                    queuedTask.cancelBeforeRun(cancellation);
                }
            }
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void close() {
        closeGracefully(DEFAULT_SHUTDOWN_TIMEOUT);
    }

    private <T> CompletableFuture<T> submit(Callable<T> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (closed) {
            result.completeExceptionally(new RejectedExecutionException(
                    "NEXORA RPG async service is closed"));
            return result;
        }
        QueuedTask<T> task = new QueuedTask<>(operation, result);
        try {
            executor.execute(task);
        } catch (RejectedExecutionException rejected) {
            result.completeExceptionally(rejected);
        }
        return result;
    }

    private static final class QueuedTask<T> implements Runnable {
        private final Callable<T> operation;
        private final CompletableFuture<T> result;

        private QueuedTask(Callable<T> operation, CompletableFuture<T> result) {
            this.operation = operation;
            this.result = result;
        }

        @Override
        public void run() {
            if (result.isDone()) {
                return;
            }
            try {
                result.complete(operation.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }

        private void cancelBeforeRun(CancellationException cancellation) {
            result.completeExceptionally(cancellation);
        }
    }
}
