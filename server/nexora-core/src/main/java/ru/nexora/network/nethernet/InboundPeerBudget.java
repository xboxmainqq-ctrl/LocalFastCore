package ru.nexora.network.nethernet;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bounded fixed-window admission budget for inbound NetherNet frames.
 *
 * The defaults deliberately allow substantial Bedrock burst traffic. This is a
 * transport safety ceiling, not a gameplay tick-rate limiter. Per-peer windows
 * are synchronized independently so unrelated players never contend on one lock.
 */
public final class InboundPeerBudget {
    public static final long DEFAULT_WINDOW_NANOS = 5_000_000_000L;
    public static final int DEFAULT_MAX_FRAMES_PER_WINDOW = 4_096;
    public static final long DEFAULT_MAX_BYTES_PER_WINDOW = 16L * 1024L * 1024L;
    public static final int DEFAULT_MAX_TRACKED_PEERS = 1_024;

    private final long windowNanos;
    private final int maxFramesPerWindow;
    private final long maxBytesPerWindow;
    private final int maxTrackedPeers;
    private final ConcurrentHashMap<String, Window> peers = new ConcurrentHashMap<>();
    private final AtomicInteger trackedPeers = new AtomicInteger();
    private final LongAdder admittedFrames = new LongAdder();
    private final LongAdder admittedBytes = new LongAdder();
    private final LongAdder rejectedFrames = new LongAdder();
    private final LongAdder rejectedBytes = new LongAdder();

    public InboundPeerBudget() {
        this(DEFAULT_WINDOW_NANOS, DEFAULT_MAX_FRAMES_PER_WINDOW,
                DEFAULT_MAX_BYTES_PER_WINDOW, DEFAULT_MAX_TRACKED_PEERS);
    }

    public InboundPeerBudget(long windowNanos, int maxFramesPerWindow,
                             long maxBytesPerWindow, int maxTrackedPeers) {
        if (windowNanos < 1 || maxFramesPerWindow < 1 || maxBytesPerWindow < 1
                || maxTrackedPeers < 1) {
            throw new IllegalArgumentException("Inbound peer budget limits must be positive");
        }
        this.windowNanos = windowNanos;
        this.maxFramesPerWindow = maxFramesPerWindow;
        this.maxBytesPerWindow = maxBytesPerWindow;
        this.maxTrackedPeers = maxTrackedPeers;
    }

    /** Returns false without mutating accepted-window counters when over budget. */
    public boolean tryAcquire(String peerId, int frameBytes, long nowNanos) {
        Objects.requireNonNull(peerId, "peerId");
        if (peerId.isBlank() || frameBytes < 0) {
            throw new IllegalArgumentException("Invalid inbound peer frame");
        }
        while (true) {
            Window window = windowFor(peerId, nowNanos);
            if (window == null) {
                rejectedFrames.increment();
                rejectedBytes.add(frameBytes);
                return false;
            }
            synchronized (window) {
                // Disconnect may have removed this exact window while we waited.
                // Retry against a new entry instead of admitting into detached state.
                if (peers.get(peerId) != window) continue;
                if (nowNanos < window.startedAtNanos
                        || nowNanos - window.startedAtNanos >= windowNanos) {
                    window.reset(nowNanos);
                }
                if (window.frames >= maxFramesPerWindow
                        || frameBytes > maxBytesPerWindow - window.bytes) {
                    rejectedFrames.increment();
                    rejectedBytes.add(frameBytes);
                    return false;
                }
                window.frames++;
                window.bytes += frameBytes;
                admittedFrames.increment();
                admittedBytes.add(frameBytes);
                return true;
            }
        }
    }

    private Window windowFor(String peerId, long nowNanos) {
        Window existing = peers.get(peerId);
        if (existing != null) return existing;

        while (true) {
            int current = trackedPeers.get();
            if (current >= maxTrackedPeers) return null;
            if (trackedPeers.compareAndSet(current, current + 1)) break;
        }
        Window created = new Window(nowNanos);
        existing = peers.putIfAbsent(peerId, created);
        if (existing == null) return created;
        trackedPeers.decrementAndGet();
        return existing;
    }

    public void onDisconnected(String peerId) {
        if (peerId == null) return;
        Window window = peers.get(peerId);
        if (window == null) return;
        synchronized (window) {
            if (peers.remove(peerId, window)) trackedPeers.decrementAndGet();
        }
    }

    public Stats stats() {
        return new Stats(admittedFrames.sum(), admittedBytes.sum(), rejectedFrames.sum(),
                rejectedBytes.sum(), trackedPeers.get());
    }

    public record Stats(long admittedFrames, long admittedBytes, long rejectedFrames,
                        long rejectedBytes, int trackedPeers) { }

    private static final class Window {
        private long startedAtNanos;
        private int frames;
        private long bytes;

        private Window(long nowNanos) {
            reset(nowNanos);
        }

        private void reset(long nowNanos) {
            startedAtNanos = nowNanos;
            frames = 0;
            bytes = 0;
        }
    }
}
