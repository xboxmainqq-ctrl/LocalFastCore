package ru.nexora.ai;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.time.Instant;
import java.util.Objects;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.Map;
import java.util.TreeMap;
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
    private static final int MAX_SIGNATURES = 16;
    private static final int MAX_LATENCY_SUBSYSTEMS = 16;
    private static final int LATENCY_SAMPLES_PER_SUBSYSTEM = 60;

    private final long startedAtNanos = System.nanoTime();
    private final AtomicLong recordedFailures = new AtomicLong();
    private final AtomicLong slowOperationCount = new AtomicLong();
    private final ConcurrentLinkedDeque<DiagnosticEvent> events = new ConcurrentLinkedDeque<>();
    private final ConcurrentHashMap<String, AtomicLong> repeatedSignatures = new ConcurrentHashMap<>();
    private final Map<String, ArrayDeque<Long>> latencySamples = new java.util.LinkedHashMap<>();
    private volatile String lastFailure = "none";
    private volatile String lastSlowOperation = "none";

    /** Records a failure reported by a subsystem without retaining its Throwable or stack trace. */
    public void recordFailure(Throwable failure) {
        recordFailure("unknown", failure);
    }

    /** Records a failure with its originating subsystem for repeat detection. */
    public void recordFailure(String subsystem, Throwable failure) {
        Objects.requireNonNull(subsystem, "subsystem");
        Objects.requireNonNull(failure, "failure");
        if (subsystem.isBlank() || subsystem.length() > 64) {
            throw new IllegalArgumentException("Invalid diagnostic subsystem");
        }
        String safeSubsystem = bound(sanitize(subsystem), 64);
        recordedFailures.incrementAndGet();
        String message = failure.getMessage();
        String description = (safeSubsystem.equals("unknown") ? "" : safeSubsystem + ": ")
                + failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        lastFailure = bound(sanitize(description), MAX_FAILURE_DESCRIPTION_LENGTH);
        incrementSignature("FAILURE:" + safeSubsystem + ":" + failure.getClass().getSimpleName());
        appendEvent("FAILURE", lastFailure);
    }

    /** Records a bounded, identifier-free security signal from NexoraAI Guard. */
    public void recordGuardSignal(String description) {
        Objects.requireNonNull(description, "description");
        if (description.isBlank()) throw new IllegalArgumentException("description must not be blank");
        appendEvent("GUARD", bound(sanitize(description), MAX_FAILURE_DESCRIPTION_LENGTH));
    }

    /** Records an operation that exceeded its expected execution budget. */
    public void recordSlowOperation(String subsystem, long elapsedNanos, long thresholdNanos) {
        Objects.requireNonNull(subsystem, "subsystem");
        if (subsystem.isBlank() || subsystem.length() > 64 || elapsedNanos < 0 || thresholdNanos < 1) {
            throw new IllegalArgumentException("Invalid slow operation measurement");
        }
        String safeSubsystem = bound(sanitize(subsystem), 64);
        recordLatencySample(safeSubsystem, elapsedNanos);
        if (elapsedNanos <= thresholdNanos) return;
        slowOperationCount.incrementAndGet();
        incrementSignature("SLOW:" + safeSubsystem);
        lastSlowOperation = safeSubsystem + " took " + (elapsedNanos / 1_000_000L) + " ms";
        appendEvent("SLOW", lastSlowOperation);
    }

    private synchronized void recordLatencySample(String subsystem, long elapsedNanos) {
        ArrayDeque<Long> samples = latencySamples.get(subsystem);
        if (samples == null) {
            if (latencySamples.size() >= MAX_LATENCY_SUBSYSTEMS) {
                latencySamples.remove(latencySamples.keySet().iterator().next());
            }
            samples = new ArrayDeque<>(LATENCY_SAMPLES_PER_SUBSYSTEM);
            latencySamples.put(subsystem, samples);
        }
        if (samples.size() == LATENCY_SAMPLES_PER_SUBSYSTEM) samples.removeFirst();
        samples.addLast(elapsedNanos);
    }

    /** Returns bounded rolling latency summaries; values are subsystem execution times, not server TPS. */
    public synchronized List<LatencySummary> latencySummaries() {
        List<LatencySummary> summaries = new ArrayList<>();
        latencySamples.forEach((subsystem, samples) -> {
            if (samples.isEmpty()) return;
            List<Long> sorted = new ArrayList<>(samples);
            Collections.sort(sorted);
            long total = 0L;
            for (long sample : samples) total = total > Long.MAX_VALUE - sample
                    ? Long.MAX_VALUE : total + sample;
            long average = total / samples.size();
            long p95 = sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * 0.95) - 1));
            summaries.add(new LatencySummary(subsystem, samples.size(), average,
                    p95, sorted.get(sorted.size() - 1)));
        });
        return List.copyOf(summaries);
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
        String repeats = repeatedEvidence();
        if (!repeats.isEmpty() && result.length() < MAX_EVIDENCE_LENGTH) {
            result.append("\n").append(repeats);
        }
        List<LatencySummary> latency = latencySummaries();
        if (!latency.isEmpty() && result.length() < MAX_EVIDENCE_LENGTH) {
            result.append("\nПоследние замеры длительности подсистем (не TPS):");
            for (LatencySummary summary : latency) {
                result.append("\n- ").append(summary.subsystem()).append(": n=")
                        .append(summary.sampleCount()).append(", avg=")
                        .append(summary.averageNanos() / 1_000_000L).append(" ms, p95=")
                        .append(summary.p95Nanos() / 1_000_000L).append(" ms, max=")
                        .append(summary.maxNanos() / 1_000_000L).append(" ms");
            }
        }
        if (result.length() > MAX_EVIDENCE_LENGTH) {
            result.setLength(MAX_EVIDENCE_LENGTH);
        }
        return result.toString();
    }

    private synchronized void incrementSignature(String signature) {
        AtomicLong counter = repeatedSignatures.get(signature);
        if (counter == null) {
            if (repeatedSignatures.size() >= MAX_SIGNATURES) return;
            AtomicLong candidate = new AtomicLong();
            AtomicLong existing = repeatedSignatures.putIfAbsent(signature, candidate);
            counter = existing == null ? candidate : existing;
        }
        counter.incrementAndGet();
    }

    /** Repetition counts are process-lifetime totals, not a time-window or rate estimate. */
    private String repeatedEvidence() {
        Map<String, Long> repeated = new TreeMap<>();
        repeatedSignatures.forEach((signature, count) -> {
            long occurrences = count.get();
            if (occurrences > 1) repeated.put(signature, occurrences);
        });
        if (repeated.isEmpty()) return "";
        StringBuilder result = new StringBuilder("Повторяющиеся события с момента запуска:");
        repeated.forEach((signature, count) -> result.append("\n- ")
                .append(signature).append(" — ").append(count).append(" раз"));
        return result.toString();
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

    /** Returns detailed heap, non-heap, and thread counters for operator diagnostics. */
    public JvmResourceSnapshot resourceSnapshot() {
        return JvmResourceSnapshot.capture();
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

    public record LatencySummary(String subsystem, int sampleCount,
                                  long averageNanos, long p95Nanos, long maxNanos) {
        public LatencySummary {
            Objects.requireNonNull(subsystem, "subsystem");
            if (subsystem.isBlank() || sampleCount < 1 || averageNanos < 0
                    || p95Nanos < 0 || maxNanos < 0) {
                throw new IllegalArgumentException("Invalid latency summary");
            }
        }
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
