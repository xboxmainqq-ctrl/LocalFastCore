package ru.nexora.ai;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Read-only runtime diagnostics for NexoraAI.
 *
 * This service deliberately observes the JVM only. It never changes server state,
 * restarts services, edits files, or attempts automatic repairs.
 */
public final class NexoraAiDiagnostics {
    private static final int MAX_FAILURE_DESCRIPTION_LENGTH = 240;
    private static final int MAX_EVENTS = 12;
    private static final int MAX_EVIDENCE_LENGTH = 1_800;

    private final long startedAtNanos = System.nanoTime();
    private final AtomicLong recordedFailures = new AtomicLong();
    private final AtomicLong slowOperationCount = new AtomicLong();
    private final ConcurrentLinkedDeque<DiagnosticEvent> events = new ConcurrentLinkedDeque<>();
    private volatile String lastFailure = "none";
    private volatile String lastSlowOperation = "none";

    /** Records a failure reported by a subsystem without retaining its Throwable or stack trace. */
    public void recordFailure(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        recordedFailures.incrementAndGet();
        String message = failure.getMessage();
        String description = failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        lastFailure = bound(sanitize(description), MAX_FAILURE_DESCRIPTION_LENGTH);
        appendEvent("FAILURE", lastFailure);
    }

    /** Records an operation that exceeded its expected execution budget. */
    public void recordSlowOperation(String subsystem, long elapsedNanos, long thresholdNanos) {
        Objects.requireNonNull(subsystem, "subsystem");
        if (subsystem.isBlank() || subsystem.length() > 64 || elapsedNanos < 0 || thresholdNanos < 1) {
            throw new IllegalArgumentException("Invalid slow operation measurement");
        }
        if (elapsedNanos <= thresholdNanos) return;
        slowOperationCount.incrementAndGet();
        lastSlowOperation = subsystem + " took " + (elapsedNanos / 1_000_000L) + " ms";
        appendEvent("SLOW", lastSlowOperation);
    }

    /**
     * Returns a small newest-first evidence block for an operator-requested repair plan.
     * Entries contain only bounded diagnostic summaries, never stack traces or player data.
     */
    public String recentEvidence() {
        StringBuilder result = new StringBuilder("Наблюдаемые диагностические события (новые сверху):");
        int count = 0;
        for (DiagnosticEvent event : events) {
            if (count++ >= MAX_EVENTS) break;
            result.append("\n- ").append(event.timestamp()).append(" [")
                    .append(event.type()).append("] ").append(event.description());
            if (result.length() >= MAX_EVIDENCE_LENGTH) {
                result.setLength(MAX_EVIDENCE_LENGTH);
                break;
            }
        }
        if (count == 0) result.append("\n- Нет зарегистрированных событий.");
        return result.toString();
    }

    private void recordEvent(String type, String description) {
        String safeDescription = bound(sanitize(description), 240);
    }

    private static String sanitize(String value) {
        return value
                .replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+=*", "$1[REDACTED]")
                .replaceAll("(?i)((?:api[_-]?key|access[_-]?token|refresh[_-]?token)\\s*[=:]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ");
    }

    private void appendEvent(String type, String description) {
        String safeDescription = bound(description, 240);
        events.addFirst(new DiagnosticEvent(Instant.now().toString(), type, safeDescription));
        while (events.size() > MAX_EVENTS) events.pollLast();
    }

    private static String bound(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /** Returns a point-in-time, read-only view of JVM health. */
    public Snapshot inspect() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long used = Math.max(0L, heap.getUsed());
        long max = heap.getMax();
        double heapUsage = max > 0 ? (double) used / max : -1.0;
        int threadCount = ManagementFactory.getThreadMXBean().getThreadCount();
        int processors = Runtime.getRuntime().availableProcessors();
        double systemLoad = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        long uptimeMillis = Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);

        Health health = Health.HEALTHY;
        String reason = "Runtime metrics are within the basic safety thresholds.";
        if (heapUsage >= 0.0 && heapUsage >= 0.90) {
            health = Health.WARNING;
            reason = "JVM heap usage is at or above 90%.";
        } else if (systemLoad >= 0.0 && systemLoad > processors * 2.0) {
            health = Health.WARNING;
            reason = "System load average is more than twice the available processor count.";
        } else if (recordedFailures.get() > 0) {
            health = Health.WARNING;
            reason = "One or more subsystem failures have been reported.";
        } else if (slowOperationCount.get() > 0) {
            health = Health.WARNING;
            reason = "One or more monitored operations exceeded their execution budget.";
        }

        return new Snapshot(health, reason, uptimeMillis, used, max, heapUsage,
                threadCount, processors, systemLoad, recordedFailures.get(), lastFailure,
                slowOperationCount.get(), lastSlowOperation);
    }

    private record DiagnosticEvent(String timestamp, String type, String description) {
    }

    public enum Health {
        HEALTHY,
        WARNING
    }

    public record Snapshot(
            Health health,
            String reason,
            long uptimeMillis,
            long heapUsedBytes,
            long heapMaxBytes,
            double heapUsageRatio,
            int liveThreadCount,
            int availableProcessors,
            double systemLoadAverage,
            long recordedFailures,
            String lastFailure,
            long slowOperationCount,
            String lastSlowOperation
    ) {
        public Snapshot {
            Objects.requireNonNull(health, "health");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(lastFailure, "lastFailure");
            Objects.requireNonNull(lastSlowOperation, "lastSlowOperation");
            if (uptimeMillis < 0 || heapUsedBytes < 0 || liveThreadCount < 0
                    || availableProcessors < 1 || recordedFailures < 0 || slowOperationCount < 0
                    || !Double.isFinite(heapUsageRatio) && heapUsageRatio != -1.0
                    || !Double.isFinite(systemLoadAverage) && systemLoadAverage != -1.0) {
                throw new IllegalArgumentException("Invalid runtime diagnostic snapshot");
            }
        }
    }
}
