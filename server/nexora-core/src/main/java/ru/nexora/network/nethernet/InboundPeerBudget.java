package ru.nexora.network.nethernet;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded fixed-window admission budget for inbound NetherNet frames.
 *
 * The defaults deliberately allow substantial Bedrock burst traffic. This is a
 * transport safety ceiling, not a gameplay tick-rate limiter. State is released
 * only when the peer disconnects; unknown peers are rejected when the table is full.
 */
public final class InboundPeerBudget {
    public static final long DEFAULT_WINDOW_NANOS = 5_000_000_000L;
    public static final int DEFAULT_MAX_FRAMES_PER_WINDOW = 1_200;
    public static final long DEFAULT_MAX_BYTES_PER_WINDOW = 8L * 1024L * 1024L;
    public static final int DEFAULT_MAX_TRACKED_PEERS = 1_024;

    private final long windowNanos;
    private final int maxFramesPerWindow;
    private final long maxBytesPerWindow;
    private final int maxTrackedPeers;
    private final Map<String, Window> peers = new LinkedHashMap<>();
    private long admittedFrames;
    private long admittedBytes;
    private long rejectedFrames;
    private long rejectedBytes;

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

    /** Returns false without mutating the peer's accepted-window counters when over budget. */
    public synchronized boolean tryAcquire(String peerId, int frameBytes, long nowNanos) {
        Objects.requireNonNull(peerId, "peerId");
        if (peerId.isBlank() || frameBytes < 0) {
            throw new IllegalArgumentException("Invalid inbound peer frame");
        }
        Window window = peers.get(peerId);
        if (window == null) {
            if (peers.size() >= maxTrackedPeers) {
                rejectedFrames++;
                rejectedBytes += frameBytes;
                return false;
            }
            window = new Window(nowNanos);
            peers.put(peerId, window);
        } else if (nowNanos < window.startedAtNanos
                || nowNanos - window.startedAtNanos >= windowNanos) {
            window.reset(nowNanos);
        }

        if (window.frames >= maxFramesPerWindow
                || frameBytes > maxBytesPerWindow - window.bytes) {
            rejectedFrames++;
            rejectedBytes += frameBytes;
            return false;
        }
        window.frames++;
        window.bytes += frameBytes;
        admittedFrames++;
        admittedBytes += frameBytes;
        return true;
    }

    public synchronized void onDisconnected(String peerId) {
        if (peerId != null) peers.remove(peerId);
    }

    public synchronized Stats stats() {
        return new Stats(admittedFrames, admittedBytes, rejectedFrames, rejectedBytes, peers.size());
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
