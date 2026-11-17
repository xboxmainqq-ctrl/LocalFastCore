package ru.nexora.ai;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Periodically publishes read-only JVM health snapshots on a daemon thread. */
public final class NexoraAiMonitor implements AutoCloseable {
    private final NexoraAiDiagnostics diagnostics;
    private final Consumer<NexoraAiDiagnostics.Snapshot> observer;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean started = new AtomicBoolean();

    public NexoraAiMonitor(
            NexoraAiDiagnostics diagnostics,
            Consumer<NexoraAiDiagnostics.Snapshot> observer
    ) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "nexora-ai-monitor");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Starts monitoring immediately, then repeats at the supplied interval. */
    public void start(Duration interval) {
        Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("NexoraAI monitor has already been started");
        }
        long millis = Math.max(1L, interval.toMillis());
        executor.scheduleWithFixedDelay(this::inspectSafely, 0L, millis, TimeUnit.MILLISECONDS);
    }

    /** Starts with the default 30-second interval. */
    public void start() {
        start(Duration.ofSeconds(30));
    }

    private void inspectSafely() {
        try {
            observer.accept(diagnostics.inspect());
        } catch (RuntimeException failure) {
            diagnostics.recordFailure(failure);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
