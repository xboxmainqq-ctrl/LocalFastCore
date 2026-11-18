package ru.nexora.ai;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Owns the diagnostic event store and its periodic JVM health observer.
 *
 * <p>Other runtime modules may publish measurements through {@link #diagnostics()},
 * while this module alone controls the monitoring thread lifecycle.</p>
 */
public final class DiagnosticsRuntimeModule implements AutoCloseable {
    private final NexoraAiDiagnostics diagnostics;
    private final NexoraAiMonitor monitor;
    private final NexoraAiRepairProposals repairProposals;
    private final NexoraAiRepairAdvisor repairAdvisor;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public DiagnosticsRuntimeModule(Consumer<NexoraAiDiagnostics.Snapshot> observer) {
        this.diagnostics = new NexoraAiDiagnostics();
        this.repairProposals = new NexoraAiRepairProposals();
        this.repairAdvisor = new NexoraAiRepairAdvisor(repairProposals);
        this.monitor = new NexoraAiMonitor(diagnostics, snapshot -> {
            repairAdvisor.analyze(diagnostics);
            Objects.requireNonNull(observer, "observer").accept(snapshot);
        });
    }

    public NexoraAiDiagnostics diagnostics() {
        return diagnostics;
    }

    public void start() {
        start(Duration.ofSeconds(30));
    }

    public void start(Duration interval) {
        if (closed.get()) throw new IllegalStateException("Diagnostics runtime module is closed");
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Diagnostics runtime module has already been started");
        }
        try {
            monitor.start(Objects.requireNonNull(interval, "interval"));
        } catch (RuntimeException failure) {
            started.set(false);
            throw failure;
        }
    }

    public NexoraAiRepairProposals repairProposals() {
        return repairProposals;
    }

    public NexoraAiRepairAdvisor repairAdvisor() {
        return repairAdvisor;
    }

    public boolean isStarted() {
        return started.get();
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        monitor.close();
    }
}
