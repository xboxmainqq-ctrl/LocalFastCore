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
        WORLD_ENTITY_TICK,
        CHUNK_RESIDENCY,
        BEDROCK_INBOUND
    }

    private static final int DEFAULT_WINDOW = 256;

    private final int windowSize;
    private final EnumMap<Path, Window> windows = new EnumMap<>(Path.class);

    public HotPathProfiler() {
        this(DEFAULT_WINDOW);
    }

    public HotPathProfiler(int windowSize) {
        if (windowSize < 1 || windowSize > 4096) {
            throw new IllegalArgumentException("windowSize must be between 1 and 4096");
        }
        this.windowSize = windowSize;
        for (Path path : Path.values()) {
            windows.put(path, new Window(windowSize));
        }
    }

    public void record(Path path, long elapsedNanos) {
        Objects.requireNonNull(path, "path");
        if (elapsedNanos < 0) throw new IllegalArgumentException("elapsedNanos must be non-negative");
        synchronized (windows) {
            windows.get(path).record(elapsedNanos);
        }
    }

    public Snapshot snapshot(Path path) {
        Objects.requireNonNull(path, "path");
        synchronized (windows) {
            return windows.get(path).snapshot();
        }
    }

    public Map<Path, Snapshot> snapshots() {
        synchronized (windows) {
            EnumMap<Path, Snapshot> result = new EnumMap<>(Path.class);
            for (Map.Entry<Path, Window> entry : windows.entrySet()) {
                result.put(entry.getKey(), entry.getValue().snapshot());
            }
            return Map.copyOf(result);
        }
    }

    private static final class Window {
        private final long[] samples;
        private int next;
        private int count;

        private Window(int size) {
            samples = new long[size];
        }

        private void record(long nanos) {
            samples[next] = nanos;
            next = (next + 1) % samples.length;
            if (count < samples.length) count++;
        }

        private Snapshot snapshot() {
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
