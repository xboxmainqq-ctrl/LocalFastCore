package ru.nexora.ai;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded hot-path profiler for repeatable subsystem baselines.
 *
 * <p>This is diagnostic instrumentation, not a TPS meter. Each path keeps only the
 * most recent bounded sample window so profiling cannot grow without limit.</p>
 */
public final class HotPathProfiler {
    public enum Path {
        MOB_TARGETING,
        MOB_AI_DECISION,
        MOB_PATHFINDING,
        WORLD_ENTITY_TICK,
        CHUNK_RESIDENCY,
        BEDROCK_INBOUND,
        BEDROCK_CHUNK_STREAMING,
        BEDROCK_OUTBOUND_DRAIN
    }

    private static final int DEFAULT_WINDOW = 256;
    private static final Path[] PATHS = Path.values();

    private final Window[] windows;

    public HotPathProfiler() {
        this(DEFAULT_WINDOW);
    }

    public HotPathProfiler(int windowSize) {
        if (windowSize < 1 || windowSize > 4096) {
            throw new IllegalArgumentException("windowSize must be between 1 and 4096");
        }
        windows = new Window[PATHS.length];
        for (Path path : PATHS) {
            windows[path.ordinal()] = new Window(windowSize);
        }
    }

    public void record(Path path, long elapsedNanos) {
        Objects.requireNonNull(path, "path");
        if (elapsedNanos < 0) throw new IllegalArgumentException("elapsedNanos must be non-negative");
        windows[path.ordinal()].record(elapsedNanos);
    }

    public long estimateNanos(Path path, long fallbackNanos) {
        Objects.requireNonNull(path, "path");
        if (fallbackNanos < 0) throw new IllegalArgumentException("fallbackNanos < 0");
        Snapshot snapshot = windows[path.ordinal()].snapshot();
        if (snapshot.sampleCount() == 0) return fallbackNanos;
        long estimate = snapshot.p95Nanos();
        return estimate > 0 ? estimate : fallbackNanos;
    }

    public Snapshot snapshot(Path path) {
        Objects.requireNonNull(path, "path");
        return windows[path.ordinal()].snapshot();
    }

    public Map<Path, Snapshot> snapshots() {
        EnumMap<Path, Snapshot> result = new EnumMap<>(Path.class);
        for (Path path : PATHS) {
            result.put(path, windows[path.ordinal()].snapshot());
        }
        return Map.copyOf(result);
    }

    private static final class Window {
        private final long[] samples;
        private int next;
        private int count;

        private Window(int size) {
            samples = new long[size];
        }

        private synchronized void record(long nanos) {
            samples[next] = nanos;
            next = (next + 1) % samples.length;
            if (count < samples.length) count++;
        }

        private synchronized Snapshot snapshot() {
            if (count == 0) return new Snapshot(0, 0, 0, 0, 0, samples.length);
            long[] ordered = new long[count];
            int start = (next - count + samples.length) % samples.length;
            for (int i = 0; i < count; i++) {
                ordered[i] = samples[(start + i) % samples.length];
            }
            java.util.Arrays.sort(ordered);
            long total = 0;
            for (long sample : ordered) {
                total = total > Long.MAX_VALUE - sample ? Long.MAX_VALUE : total + sample;
            }
            long average = total / count;
            long p50 = ordered[Math.max(0, (int) Math.ceil(count * 0.50) - 1)];
            long p95 = ordered[Math.max(0, (int) Math.ceil(count * 0.95) - 1)];
            return new Snapshot(count, average, p50, p95, ordered[count - 1], samples.length);
        }
    }

    public record Snapshot(
            int sampleCount,
            long averageNanos,
            long p50Nanos,
            long p95Nanos,
            long maxNanos,
            int capacity
    ) {
        public Snapshot {
            if (sampleCount < 0 || averageNanos < 0 || p50Nanos < 0
                    || p95Nanos < 0 || maxNanos < 0 || capacity < 1
                    || sampleCount > capacity) {
                throw new IllegalArgumentException("Invalid profiler snapshot");
            }
        }

        public String summary() {
            return "samples=" + sampleCount
                    + ", avg=" + averageNanos + " ns"
                    + ", p50=" + p50Nanos + " ns"
                    + ", p95=" + p95Nanos + " ns"
                    + ", max=" + maxNanos + " ns";
        }
    }
}
