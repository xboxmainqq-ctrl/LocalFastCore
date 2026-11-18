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
import java.util.function.LongSupplier;

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
    private static final long LATENCY_WINDOW_NANOS = 60_000_000_000L;
    private static final long LATENCY_BUCKET_NANOS = 60_000_000_000L;
    private static final int MAX_LATENCY_BUCKETS = 60;

    private final LongSupplier nanoClock;
    private final long startedAtNanos;
    private final AtomicLong recordedFailures = new AtomicLong();
    private final AtomicLong incidentSequence = new AtomicLong();
    private final AtomicLong slowOperationCount = new AtomicLong();
    private final ConcurrentLinkedDeque<DiagnosticEvent> events = new ConcurrentLinkedDeque<>();
    private final ConcurrentHashMap<String, AtomicLong> repeatedSignatures = new ConcurrentHashMap<>();
    private final Map<String, ArrayDeque<LatencySample>> latencySamples = new java.util.LinkedHashMap<>();
    private final Map<String, java.util.TreeMap<Long, LatencyBucket>> latencyBuckets = new java.util.LinkedHashMap<>();
    private final java.util.TreeMap<Long, ActivityBucket> activityBuckets = new java.util.TreeMap<>();
    private volatile String lastFailure = "none";
    private volatile String lastSlowOperation = "none";

    public NexoraAiDiagnostics() { this(System::nanoTime); }

    NexoraAiDiagnostics(LongSupplier nanoClock) {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.startedAtNanos = nanoClock.getAsLong();
    }

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
        recordFailureActivity();
        String message = failure.getMessage();
        String description = (safeSubsystem.equals("unknown") ? "" : safeSubsystem + ": ")
                + failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        lastFailure = bound(sanitize(description), MAX_FAILURE_DESCRIPTION_LENGTH);
        incrementSignature("FAILURE:" + safeSubsystem + ":" + failure.getClass().getSimpleName());
        appendEvent("FAILURE", lastFailure);
    }

    private synchronized void recordFailureActivity() {
        long minute = Math.floorDiv(nanoClock.getAsLong(), LATENCY_BUCKET_NANOS);
        activityBuckets.computeIfAbsent(minute, ignored -> new ActivityBucket()).failures++;
        pruneActivityBuckets(minute);
    }

    /** Records a JVM resource sample; pressure is diagnostic only, never an automatic action. */
    public synchronized void recordResourceSnapshot(JvmResourceSnapshot resources) {
        Objects.requireNonNull(resources, "resources");
        long minute = Math.floorDiv(nanoClock.getAsLong(), LATENCY_BUCKET_NANOS);
        ActivityBucket bucket = activityBuckets.computeIfAbsent(minute, ignored -> new ActivityBucket());
        bucket.resourceSamples++;
        double heapRatio = resources.heapUsageRatio();
        if (heapRatio >= 0.0) {
            bucket.heapRatioTotal += heapRatio;
            bucket.heapRatioSamples++;
            bucket.maxHeapRatio = Math.max(bucket.maxHeapRatio, heapRatio);
        }
        double load = resources.systemLoadAverage();
        if (load >= 0.0) {
            bucket.loadRatioSamples++;
            bucket.maxLoadRatio = Math.max(bucket.maxLoadRatio, load / resources.availableProcessors());
        }
        if (heapRatio >= 0.90 || (load >= 0.0 && load > resources.availableProcessors() * 2.0)) {
            bucket.pressureSamples++;
        }
        bucket.threadSamples++;
        bucket.threadCountTotal = saturatedAdd(bucket.threadCountTotal, resources.liveThreadCount());
        bucket.minThreadCount = Math.min(bucket.minThreadCount, resources.liveThreadCount());
        bucket.maxThreadCount = Math.max(bucket.maxThreadCount, resources.liveThreadCount());
        pruneActivityBuckets(minute);
    }

    /**
     * Evaluates three consecutive minute buckets. A trend is only reported when every
     * minute has at least two JVM samples, avoiding conclusions from one-off snapshots.
     */
    public synchronized ResourceTrendSummary resourceTrendSummary() {
        long currentMinute = Math.floorDiv(nanoClock.getAsLong(), LATENCY_BUCKET_NANOS);
        long firstMinute = currentMinute - 2;
        long firstAverage = -1, latestAverage = -1;
        int observedMinutes = 0, pressureMinutes = 0;
        boolean enoughSamples = true;
        for (long minute = firstMinute; minute <= currentMinute; minute++) {
            ActivityBucket bucket = activityBuckets.get(minute);
            if (bucket == null || bucket.resourceSamples == 0) {
                enoughSamples = false;
                continue;
            }
            observedMinutes++;
            if (bucket.threadSamples < 2) enoughSamples = false;
            long averageThreads = bucket.threadCountTotal / bucket.threadSamples;
            if (minute == firstMinute) firstAverage = averageThreads;
            if (minute == currentMinute) latestAverage = averageThreads;
            if (bucket.resourceSamples >= 2 && bucket.pressureSamples * 2 >= bucket.resourceSamples) {
                pressureMinutes++;
            }
        }
        long growth = firstAverage < 0 || latestAverage < 0 ? 0 : latestAverage - firstAverage;
        long growthThreshold = firstAverage < 0 ? Long.MAX_VALUE
                : Math.max(4L, (long) Math.ceil(firstAverage * 0.20));
        boolean complete = enoughSamples && observedMinutes == 3
                && firstAverage >= 0 && latestAverage >= 0;
        return new ResourceTrendSummary(observedMinutes, firstAverage, latestAverage, growth,
                pressureMinutes, complete,
                complete && growth >= growthThreshold, complete && pressureMinutes == 3);
    }

    private void pruneActivityBuckets(long currentMinute) {
        activityBuckets.headMap(currentMinute - MAX_LATENCY_BUCKETS + 1, false).clear();
    }

    /**
     * Aligns Bedrock inbound and mob gameplay measurements by shared monotonic minute buckets.
     * This reports co-occurrence only; it does not claim that one subsystem caused another's latency.
     */
    public synchronized List<LatencyAlignmentSummary> latencyAlignmentSummaries() {
        long currentMinute = Math.floorDiv(nanoClock.getAsLong(), LATENCY_BUCKET_NANOS);
        java.util.TreeMap<Long, LatencyBucket> network = latencyBuckets.get("bedrock-inbound");
        java.util.TreeMap<Long, LatencyBucket> mobAi = latencyBuckets.get("mob-ai");
        java.util.TreeMap<Long, LatencyBucket> pathfinding = latencyBuckets.get("mob-pathfinding");
        List<LatencyAlignmentSummary> summaries = new ArrayList<>();
        for (int windowMinutes : new int[]{5, 60}) {
            long firstMinute = currentMinute - windowMinutes + 1;
            long overlap = 0, networkCount = 0, gameplayCount = 0;
            long networkTotal = 0, gameplayTotal = 0, networkMax = 0, gameplayMax = 0;
            if (network != null) {
                for (Map.Entry<Long, LatencyBucket> entry : network.tailMap(firstMinute, true).entrySet()) {
                    LatencyBucket ai = mobAi == null ? null : mobAi.get(entry.getKey());
                    LatencyBucket path = pathfinding == null ? null : pathfinding.get(entry.getKey());
                    if (ai == null && path == null) continue;
                    overlap++;
                    LatencyBucket net = entry.getValue();
                    networkCount = saturatedAdd(networkCount, net.count);
                    networkTotal = saturatedAdd(networkTotal, net.totalNanos);
                    networkMax = Math.max(networkMax, net.maxNanos);
                    if (ai != null) {
                        gameplayCount = saturatedAdd(gameplayCount, ai.count);
                        gameplayTotal = saturatedAdd(gameplayTotal, ai.totalNanos);
                        gameplayMax = Math.max(gameplayMax, ai.maxNanos);
                    }
                    if (path != null) {
                        gameplayCount = saturatedAdd(gameplayCount, path.count);
                        gameplayTotal = saturatedAdd(gameplayTotal, path.totalNanos);
                        gameplayMax = Math.max(gameplayMax, path.maxNanos);
                    }
                }
            }
            summaries.add(new LatencyAlignmentSummary(windowMinutes, overlap, networkCount, gameplayCount,
                    networkCount == 0 ? 0 : networkTotal / networkCount,
                    gameplayCount == 0 ? 0 : gameplayTotal / gameplayCount, networkMax, gameplayMax));
        }
        return List.copyOf(summaries);
    }

    /** Returns bounded failure and JVM-pressure aggregates for the last 5 and 60 minutes. */
    public synchronized List<ActivityWindowSummary> activityWindowSummaries() {
        long currentMinute = Math.floorDiv(nanoClock.getAsLong(), LATENCY_BUCKET_NANOS);
        List<ActivityWindowSummary> summaries = new ArrayList<>();
        for (int windowMinutes : new int[]{5, 60}) {
            long failures = 0, resourceSamples = 0, pressureSamples = 0, heapSamples = 0, loadSamples = 0;
            double heapTotal = 0.0, maxHeap = 0.0, maxLoadRatio = 0.0;
            long firstObservedMinute = Long.MAX_VALUE;
            for (Map.Entry<Long, ActivityBucket> entry
                    : activityBuckets.tailMap(currentMinute - windowMinutes + 1, true).entrySet()) {
                ActivityBucket bucket = entry.getValue();
                firstObservedMinute = Math.min(firstObservedMinute, entry.getKey());
                failures = saturatedAdd(failures, bucket.failures);
                resourceSamples = saturatedAdd(resourceSamples, bucket.resourceSamples);
                pressureSamples = saturatedAdd(pressureSamples, bucket.pressureSamples);
                heapSamples = saturatedAdd(heapSamples, bucket.heapRatioSamples);
                loadSamples = saturatedAdd(loadSamples, bucket.loadRatioSamples);
                heapTotal += bucket.heapRatioTotal;
                maxHeap = Math.max(maxHeap, bucket.maxHeapRatio);
                maxLoadRatio = Math.max(maxLoadRatio, bucket.maxLoadRatio);
            }
            long observedMinutes = firstObservedMinute == Long.MAX_VALUE ? 0
                    : Math.min(windowMinutes, currentMinute - firstObservedMinute + 1);
            summaries.add(new ActivityWindowSummary(windowMinutes, observedMinutes, failures,
                    observedMinutes == 0 ? 0.0 : failures / (double) observedMinutes,
                    resourceSamples, pressureSamples,
                    heapSamples == 0 ? -1.0 : heapTotal / heapSamples,
                    heapSamples == 0 ? -1.0 : maxHeap,
                    loadSamples == 0 ? -1.0 : maxLoadRatio));
        }
        return List.copyOf(summaries);
    }

    private static final class ActivityBucket {
        private long failures, resourceSamples, pressureSamples, heapRatioSamples, loadRatioSamples;
        private long threadSamples, threadCountTotal, minThreadCount = Long.MAX_VALUE, maxThreadCount;
        private double heapRatioTotal, maxHeapRatio, maxLoadRatio;
    }

    /** Returns a bounded newest-first structured timeline of observed diagnostic incidents. */
    public synchronized List<Incident> incidentTimeline() {
        List<Incident> timeline = new ArrayList<>();
        for (DiagnosticEvent event : events) {
            if (timeline.size() >= MAX_EVENTS) break;
            timeline.add(new Incident(event.sequence(), event.timestamp(), event.uptimeMillis(),
                    event.type(), event.description()));
        }
        return List.copyOf(timeline);
    }

    /** Records a bounded, identifier-free security signal from NexoraAI Guard. */
    public void recordGuardSignal(String description) {
        Objects.requireNonNull(description, "description");
        if (description.isBlank()) throw new IllegalArgumentException("description must not be blank");
        appendEvent("GUARD", bound(sanitize(description), MAX_FAILURE_DESCRIPTION_LENGTH));
    }

    /** Records every subsystem duration and marks samples that exceeded their execution budget. */
    public void recordSlowOperation(String subsystem, long elapsedNanos, long thresholdNanos) {
        Objects.requireNonNull(subsystem, "subsystem");
        if (subsystem.isBlank() || subsystem.length() > 64 || elapsedNanos < 0 || thresholdNanos < 1) {
            throw new IllegalArgumentException("Invalid slow operation measurement");
        }
        String safeSubsystem = bound(sanitize(subsystem), 64);
        recordLatencySample(safeSubsystem, elapsedNanos, elapsedNanos > thresholdNanos);
        if (elapsedNanos <= thresholdNanos) return;
        slowOperationCount.incrementAndGet();
        incrementSignature("SLOW:" + safeSubsystem);
        lastSlowOperation = safeSubsystem + " took " + (elapsedNanos / 1_000_000L) + " ms";
        appendEvent("SLOW", lastSlowOperation);
    }

    private synchronized void recordLatencySample(String subsystem, long elapsedNanos, boolean exceededBudget) {
        long now = nanoClock.getAsLong();
        long minute = Math.floorDiv(now, LATENCY_BUCKET_NANOS);
        java.util.TreeMap<Long, LatencyBucket> buckets = latencyBuckets.computeIfAbsent(subsystem, ignored -> new java.util.TreeMap<>());
        buckets.computeIfAbsent(minute, ignored -> new LatencyBucket()).record(elapsedNanos, exceededBudget);
        buckets.headMap(minute - MAX_LATENCY_BUCKETS + 1, false).clear();
        ArrayDeque<LatencySample> samples = latencySamples.get(subsystem);
        if (samples == null) {
            if (latencySamples.size() >= MAX_LATENCY_SUBSYSTEMS) {
                String evictedSubsystem = latencySamples.keySet().iterator().next();
                latencySamples.remove(evictedSubsystem);
                latencyBuckets.remove(evictedSubsystem);
            }
            samples = new ArrayDeque<>(LATENCY_SAMPLES_PER_SUBSYSTEM);
            latencySamples.put(subsystem, samples);
        }
        pruneExpiredSamples(samples, now);
        if (samples.size() == LATENCY_SAMPLES_PER_SUBSYSTEM) samples.removeFirst();
        samples.addLast(new LatencySample(now, elapsedNanos, exceededBudget));
    }

    /** Returns bounded rolling latency summaries; values are subsystem execution times, not server TPS. */
    public synchronized List<LatencySummary> latencySummaries() {
        List<LatencySummary> summaries = new ArrayList<>();
        long now = nanoClock.getAsLong();
        latencySamples.forEach((subsystem, samples) -> {
            pruneExpiredSamples(samples, now);
            if (samples.isEmpty()) return;
            List<Long> sorted = new ArrayList<>(samples.size());
            long total = 0L;
            long slow = 0L;
            for (LatencySample sample : samples) {
                sorted.add(sample.elapsedNanos());
                total = total > Long.MAX_VALUE - sample.elapsedNanos()
                        ? Long.MAX_VALUE : total + sample.elapsedNanos();
                if (sample.exceededBudget()) slow++;
            }
            Collections.sort(sorted);
            long average = total / samples.size();
            long p95 = sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * 0.95) - 1));
            long spanNanos = Math.max(0L, now - samples.getFirst().recordedAtNanos());
            long windowMillis = Math.min(60_000L, Math.max(1L, spanNanos / 1_000_000L));
            summaries.add(new LatencySummary(subsystem, samples.size(), slow, windowMillis,
                    average, p95, sorted.get(sorted.size() - 1)));
        });
        return List.copyOf(summaries);
    }

    /** Returns minute-bucketed 5-minute and 1-hour subsystem aggregates (not TPS). */
    public synchronized List<LatencyWindowSummary> latencyWindowSummaries() {
        long currentMinute = Math.floorDiv(nanoClock.getAsLong(), LATENCY_BUCKET_NANOS);
        List<LatencyWindowSummary> result = new ArrayList<>();
        latencyBuckets.forEach((subsystem, buckets) -> {
            for (int windowMinutes : new int[]{5, 60}) {
                long firstMinute = currentMinute - windowMinutes + 1;
                long count = 0, slow = 0, total = 0, max = 0;
                for (LatencyBucket bucket : buckets.tailMap(firstMinute, true).values()) {
                    count = saturatedAdd(count, bucket.count);
                    slow = saturatedAdd(slow, bucket.slow);
                    total = saturatedAdd(total, bucket.totalNanos);
                    max = Math.max(max, bucket.maxNanos);
                }
                if (count > 0) result.add(new LatencyWindowSummary(subsystem, windowMinutes,
                        count, slow, total / count, max));
            }
        });
        return List.copyOf(result);
    }

    /**
     * Evaluates each instrumented subsystem from its 5-minute minute buckets.
     * Sparse observations remain UNKNOWN; thresholds are diagnostic heuristics, not automatic actions.
     */
    public synchronized List<SubsystemHealth> subsystemHealth() {
        long now = nanoClock.getAsLong();
        long currentMinute = Math.floorDiv(now, LATENCY_BUCKET_NANOS);
        String observedAt = Instant.now().toString();
        long uptimeMillis = Math.max(0L, (now - startedAtNanos) / 1_000_000L);
        List<SubsystemHealth> result = new ArrayList<>();
        latencyBuckets.forEach((subsystem, buckets) -> {
            long count = 0, slow = 0, total = 0, max = 0;
            for (LatencyBucket bucket : buckets.tailMap(currentMinute - 4, true).values()) {
                count = saturatedAdd(count, bucket.count);
                slow = saturatedAdd(slow, bucket.slow);
                total = saturatedAdd(total, bucket.totalNanos);
                max = Math.max(max, bucket.maxNanos);
            }
            double breachRatio = count == 0 ? 0.0 : slow / (double) count;
            HealthConfidence confidence = count >= 20 ? HealthConfidence.HIGH
                    : count >= 5 ? HealthConfidence.MEDIUM : HealthConfidence.LOW;
            SubsystemHealthStatus status = count < 5 ? SubsystemHealthStatus.UNKNOWN
                    : count >= 10 && breachRatio >= 0.50 ? SubsystemHealthStatus.CRITICAL
                    : breachRatio >= 0.25 ? SubsystemHealthStatus.DEGRADED
                    : SubsystemHealthStatus.HEALTHY;
            String evidence = count == 0 ? "No measurements in the last 5 minutes"
                    : count + " samples; " + slow + " over budget (" 
                    + Math.round(breachRatio * 100.0) + "%); average "
                    + (total / count / 1_000_000L) + " ms; max " + (max / 1_000_000L) + " ms";
            result.add(new SubsystemHealth(subsystem, status, confidence, count, slow,
                    breachRatio, count == 0 ? 0 : total / count, max,
                    observedAt, uptimeMillis, evidence));
        });
        return List.copyOf(result);
    }

    private static long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static final class LatencyBucket {
        private long count, slow, totalNanos, maxNanos;
        private void record(long elapsedNanos, boolean exceededBudget) {
            count++;
            if (exceededBudget) slow++;
            totalNanos = saturatedAdd(totalNanos, elapsedNanos);
            maxNanos = Math.max(maxNanos, elapsedNanos);
        }
    }

    /**
     * Returns a small newest-first evidence block for an operator-requested repair plan.
     * Entries contain only bounded diagnostic summaries, never stack traces or player data.
     */
    public String recentEvidence() {
        StringBuilder result = new StringBuilder("Состояние подсистем за 5 минут (эвристика, не TPS):");
        for (SubsystemHealth item : subsystemHealth()) {
            result.append("\n- ").append(item.subsystem()).append(": ")
                    .append(item.status()).append(" / ").append(item.confidence())
                    .append(" — ").append(item.evidence());
            if (result.length() >= 900) {
                result.setLength(900);
                break;
            }
        }
        result.append("\nНаблюдаемые диагностические события (новые сверху):");
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
                        .append(summary.sampleCount()).append(", window=")
                        .append(summary.windowMillis()).append(" ms, overBudget=")
                        .append(summary.slowOperationCount()).append(", avg=")
                        .append(summary.averageNanos() / 1_000_000L).append(" ms, p95=")
                        .append(summary.p95Nanos() / 1_000_000L).append(" ms, max=")
                        .append(summary.maxNanos() / 1_000_000L).append(" ms");
            }
        }
        List<LatencyWindowSummary> longerWindows = latencyWindowSummaries();
        if (!longerWindows.isEmpty() && result.length() < MAX_EVIDENCE_LENGTH) {
            result.append("\nДлительные замеры подсистем (минутные корзины, не TPS):");
            for (LatencyWindowSummary summary : longerWindows) {
                result.append("\n- ").append(summary.subsystem()).append(" / ")
                        .append(summary.windowMinutes()).append("m: n=").append(summary.sampleCount())
                        .append(", overBudget=").append(summary.slowOperationCount())
                        .append(", avg=").append(summary.averageNanos() / 1_000_000L)
                        .append(" ms, max=").append(summary.maxNanos() / 1_000_000L).append(" ms");
            }
        }
        List<LatencyAlignmentSummary> aligned = latencyAlignmentSummaries();
        if (result.length() < MAX_EVIDENCE_LENGTH) {
            result.append("\nСопоставление сетевых и игровых задержек по общим минутам (не причинность):");
            for (LatencyAlignmentSummary summary : aligned) {
                result.append("\n- ").append(summary.windowMinutes()).append("m: commonMinutes=")
                        .append(summary.overlappingMinuteBuckets()).append(", networkN=")
                        .append(summary.networkSampleCount()).append(", gameplayN=")
                        .append(summary.gameplaySampleCount()).append(", networkAvg=")
                        .append(summary.networkAverageNanos() / 1_000_000L).append(" ms, gameplayAvg=")
                        .append(summary.gameplayAverageNanos() / 1_000_000L).append(" ms");
            }
        }
        List<ActivityWindowSummary> activity = activityWindowSummaries();
        if (result.length() < MAX_EVIDENCE_LENGTH) {
            result.append("\nОшибки и давление ресурсов (5m/60m):");
            for (ActivityWindowSummary summary : activity) {
                result.append("\n- ").append(summary.windowMinutes()).append("m: failures=")
                        .append(summary.failureCount()).append(" (")
                        .append(summary.observedMinutes()).append("m observed, ")
                        .append(String.format(java.util.Locale.ROOT, "%.2f", summary.failuresPerMinute()))
                        .append("/min), resourceSamples=").append(summary.resourceSampleCount())
                        .append(", pressureSamples=").append(summary.pressureSampleCount())
                        .append(", heapAvg=").append(summary.averageHeapUsageRatio() < 0 ? "n/a"
                                : String.format(java.util.Locale.ROOT, "%.1f%%", summary.averageHeapUsageRatio() * 100))
                        .append(", heapMax=").append(summary.maxHeapUsageRatio() < 0 ? "n/a"
                                : String.format(java.util.Locale.ROOT, "%.1f%%", summary.maxHeapUsageRatio() * 100))
                        .append(", maxLoad/core=").append(summary.maxSystemLoadPerProcessor() < 0 ? "n/a"
                                : String.format(java.util.Locale.ROOT, "%.2f", summary.maxSystemLoadPerProcessor()));
            }
        }
        ResourceTrendSummary trend = resourceTrendSummary();
        if (result.length() < MAX_EVIDENCE_LENGTH) {
            result.append("\\nТренды JVM за 3 последовательные минуты: ");
            if (!trend.enoughSamples()) {
                result.append("недостаточно замеров (").append(trend.observedConsecutiveMinutes()).append("/3 минут)");
            } else {
                result.append("потоки ").append(trend.firstMinuteAverageThreadCount()).append("→")
                        .append(trend.latestMinuteAverageThreadCount()).append(" (рост ")
                        .append(trend.threadGrowth()).append("), давление памяти ")
                        .append(trend.sustainedHeapPressureMinutes()).append("/3 минут");
                if (trend.threadGrowthDetected()) result.append("; обнаружен устойчивый рост потоков");
                if (trend.sustainedHeapPressure()) result.append("; устойчивое давление ресурсов");
            }
        }
        if (result.length() > MAX_EVIDENCE_LENGTH) {
            result.setLength(MAX_EVIDENCE_LENGTH);
        }
        return result.toString();
    }

    private static void pruneExpiredSamples(ArrayDeque<LatencySample> samples, long nowNanos) {
        while (!samples.isEmpty()
                && nowNanos - samples.getFirst().recordedAtNanos() > LATENCY_WINDOW_NANOS) {
            samples.removeFirst();
        }
    }

    public record Incident(long sequence, String occurredAt, long uptimeMillis, String type, String description) {
        public Incident {
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(description, "description");
            if (sequence < 1 || uptimeMillis < 0 || type.isBlank() || description.isBlank()) {
                throw new IllegalArgumentException("Invalid diagnostic incident");
            }
        }
    }

    public record LatencyAlignmentSummary(int windowMinutes, long overlappingMinuteBuckets,
                                          long networkSampleCount, long gameplaySampleCount,
                                          long networkAverageNanos, long gameplayAverageNanos,
                                          long networkMaxNanos, long gameplayMaxNanos) {
        public LatencyAlignmentSummary {
            if ((windowMinutes != 5 && windowMinutes != 60) || overlappingMinuteBuckets < 0
                    || networkSampleCount < 0 || gameplaySampleCount < 0
                    || networkAverageNanos < 0 || gameplayAverageNanos < 0
                    || networkMaxNanos < 0 || gameplayMaxNanos < 0
                    || (overlappingMinuteBuckets == 0 && (networkSampleCount != 0 || gameplaySampleCount != 0))) {
                throw new IllegalArgumentException("Invalid latency alignment summary");
            }
        }
    }

    public record ResourceTrendSummary(int observedConsecutiveMinutes, long firstMinuteAverageThreadCount,
                                       long latestMinuteAverageThreadCount, long threadGrowth,
                                       int sustainedHeapPressureMinutes, boolean enoughSamples,
                                       boolean threadGrowthDetected, boolean sustainedHeapPressure) {
        public ResourceTrendSummary {
            if (observedConsecutiveMinutes < 0 || observedConsecutiveMinutes > 3
                    || firstMinuteAverageThreadCount < -1 || latestMinuteAverageThreadCount < -1
                    || sustainedHeapPressureMinutes < 0 || sustainedHeapPressureMinutes > 3
                    || (enoughSamples && (observedConsecutiveMinutes != 3
                    || firstMinuteAverageThreadCount < 0 || latestMinuteAverageThreadCount < 0))
                    || (!enoughSamples && (threadGrowthDetected || sustainedHeapPressure))) {
                throw new IllegalArgumentException("Invalid resource trend summary");
            }
        }
    }

    public record ActivityWindowSummary(int windowMinutes, long observedMinutes, long failureCount,
                                        double failuresPerMinute, long resourceSampleCount,
                                        long pressureSampleCount, double averageHeapUsageRatio,
                                        double maxHeapUsageRatio, double maxSystemLoadPerProcessor) {
        public ActivityWindowSummary {
            if ((windowMinutes != 5 && windowMinutes != 60) || observedMinutes < 0 || observedMinutes > windowMinutes || failureCount < 0
                    || !Double.isFinite(failuresPerMinute) || failuresPerMinute < 0 || (observedMinutes == 0 && (failureCount != 0 || failuresPerMinute != 0.0))
                    || resourceSampleCount < 0 || pressureSampleCount < 0
                    || pressureSampleCount > resourceSampleCount
                    || (!Double.isFinite(averageHeapUsageRatio) && averageHeapUsageRatio != -1.0)
                    || (!Double.isFinite(maxHeapUsageRatio) && maxHeapUsageRatio != -1.0)
                    || (!Double.isFinite(maxSystemLoadPerProcessor) && maxSystemLoadPerProcessor != -1.0)
                    || maxSystemLoadPerProcessor < -1.0) {
                throw new IllegalArgumentException("Invalid activity window summary");
            }
        }
    }

    public enum SubsystemHealthStatus { UNKNOWN, HEALTHY, DEGRADED, CRITICAL }
    public enum HealthConfidence { LOW, MEDIUM, HIGH }

    public record SubsystemHealth(String subsystem, SubsystemHealthStatus status,
                                  HealthConfidence confidence, long sampleCount,
                                  long overBudgetCount, double overBudgetRatio,
                                  long averageNanos, long maxNanos,
                                  String observedAt, long uptimeMillis, String evidence) {
        public SubsystemHealth {
            Objects.requireNonNull(subsystem, "subsystem");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(confidence, "confidence");
            Objects.requireNonNull(observedAt, "observedAt");
            Objects.requireNonNull(evidence, "evidence");
            if (subsystem.isBlank() || sampleCount < 0 || overBudgetCount < 0
                    || overBudgetCount > sampleCount || !Double.isFinite(overBudgetRatio)
                    || overBudgetRatio < 0.0 || overBudgetRatio > 1.0
                    || averageNanos < 0 || maxNanos < 0 || uptimeMillis < 0
                    || (sampleCount == 0 && (overBudgetCount != 0 || overBudgetRatio != 0.0
                    || averageNanos != 0 || maxNanos != 0))) {
                throw new IllegalArgumentException("Invalid subsystem health");
            }
        }
    }

    public record LatencyWindowSummary(String subsystem, int windowMinutes, long sampleCount,
                                       long slowOperationCount, long averageNanos, long maxNanos) {
        public LatencyWindowSummary {
            Objects.requireNonNull(subsystem, "subsystem");
            if (subsystem.isBlank() || (windowMinutes != 5 && windowMinutes != 60)
                    || sampleCount < 1 || slowOperationCount < 0 || slowOperationCount > sampleCount
                    || averageNanos < 0 || maxNanos < 0) {
                throw new IllegalArgumentException("Invalid latency window summary");
            }
        }
    }

    private record LatencySample(long recordedAtNanos, long elapsedNanos, boolean exceededBudget) {
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

    private synchronized void appendEvent(String type, String description) {
        String safeDescription = bound(description, 240);
        long uptimeMillis = Math.max(0L, (nanoClock.getAsLong() - startedAtNanos) / 1_000_000L);
        events.addFirst(new DiagnosticEvent(incidentSequence.incrementAndGet(), Instant.now().toString(),
                uptimeMillis, type, safeDescription));
        while (events.size() > MAX_EVENTS) events.pollLast();
    }

    private static String bound(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /** Returns detailed heap, non-heap, and thread counters for operator diagnostics. */
    public JvmResourceSnapshot resourceSnapshot() {
        JvmResourceSnapshot resources = JvmResourceSnapshot.capture();
        recordResourceSnapshot(resources);
        return resources;
    }

    /** Returns a point-in-time, read-only view of JVM health. */
    public Snapshot inspect() {
        JvmResourceSnapshot resources = resourceSnapshot();
        long used = resources.heapUsedBytes();
        long max = resources.heapMaxBytes();
        double heapUsage = resources.heapUsageRatio();
        int threadCount = resources.liveThreadCount();
        int processors = resources.availableProcessors();
        double systemLoad = resources.systemLoadAverage();
        long uptimeMillis = Math.max(0L, (nanoClock.getAsLong() - startedAtNanos) / 1_000_000L);

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

    private record DiagnosticEvent(long sequence, String timestamp, long uptimeMillis, String type, String description) {
    }

    public record LatencySummary(String subsystem, int sampleCount, long slowOperationCount,
                                  long windowMillis, long averageNanos, long p95Nanos, long maxNanos) {
        public LatencySummary {
            Objects.requireNonNull(subsystem, "subsystem");
            if (subsystem.isBlank() || sampleCount < 1 || slowOperationCount < 0
                    || slowOperationCount > sampleCount || windowMillis < 1 || windowMillis > 60_000
                    || averageNanos < 0 || p95Nanos < 0 || maxNanos < 0) {
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
