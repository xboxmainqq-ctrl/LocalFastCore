package ru.nexora.network.nethernet;

import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Bounded FIFO for frames waiting behind a congested WebRTC data channel.
 *
 * The queue owns copies of offered frames so callers may safely reuse their
 * buffers after enqueueing. It intentionally does not block network callbacks.
 */
public final class NetherNetOutboundQueue {
    public static final long DEFAULT_MAX_QUEUED_BYTES = 4L * 1024L * 1024L;
    public static final int DEFAULT_MAX_QUEUED_FRAMES = 2_048;

    private final ArrayDeque<byte[]> frames = new ArrayDeque<>();
    private final long maxQueuedBytes;
    private final int maxQueuedFrames;
    private long queuedBytes;

    public NetherNetOutboundQueue() {
        this(DEFAULT_MAX_QUEUED_BYTES, DEFAULT_MAX_QUEUED_FRAMES);
    }

    public NetherNetOutboundQueue(long maxQueuedBytes, int maxQueuedFrames) {
        if (maxQueuedBytes < 1 || maxQueuedFrames < 1) {
            throw new IllegalArgumentException("Outbound queue limits must be positive");
        }
        this.maxQueuedBytes = maxQueuedBytes;
        this.maxQueuedFrames = maxQueuedFrames;
    }

    /** Adds a frame to the tail, returning false rather than exceeding either bound. */
    public synchronized boolean offer(byte[] frame) {
        Objects.requireNonNull(frame, "frame");
        if (frame.length == 0 || frames.size() >= maxQueuedFrames
                || frame.length > maxQueuedBytes - queuedBytes) {
            return false;
        }
        byte[] owned = frame.clone();
        frames.addLast(owned);
        queuedBytes += owned.length;
        return true;
    }

    /** Removes the oldest frame, or returns null when empty. */
    public synchronized byte[] poll() {
        byte[] frame = frames.pollFirst();
        if (frame != null) queuedBytes -= frame.length;
        return frame;
    }

    /** Restores a polled frame to the head after a transient send failure. */
    public synchronized void offerFirst(byte[] frame) {
        Objects.requireNonNull(frame, "frame");
        if (frame.length == 0 || frames.size() >= maxQueuedFrames
                || frame.length > maxQueuedBytes - queuedBytes) {
            throw new IllegalStateException("Unable to restore outbound frame within queue bounds");
        }
        byte[] owned = frame.clone();
        frames.addFirst(owned);
        queuedBytes += owned.length;
    }

    public synchronized boolean isEmpty() {
        return frames.isEmpty();
    }

    public synchronized int frameCount() {
        return frames.size();
    }

    public synchronized long queuedBytes() {
        return queuedBytes;
    }

    public synchronized void clear() {
        frames.clear();
        queuedBytes = 0;
    }
}
