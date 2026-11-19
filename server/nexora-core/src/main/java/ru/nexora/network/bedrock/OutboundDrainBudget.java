package ru.nexora.network.bedrock;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Global, fair egress budget for queued Bedrock frames.
 *
 * <p>Frame admission rotates between sessions and is bounded by both frame
 * count and encoded bytes. A session that has drained its queue is paused
 * without losing its quota for the current time window.</p>
 */
final class OutboundDrainBudget {
    static final long DEFAULT_WINDOW_NANOS = 50_000_000L;
    static final int DEFAULT_GLOBAL_FRAMES = 256;
    static final long DEFAULT_GLOBAL_BYTES = 4L * 1024L * 1024L;
    static final int DEFAULT_SESSION_FRAMES = 256;
    static final long DEFAULT_SESSION_BYTES = 4L * 1024L * 1024L;
    static final long DEFAULT_IDLE_SESSION_NANOS = 250_000_000L;

    private static final class Usage {
        private int frames;
        private long bytes;
    }

    private final long windowNanos;
    private final int globalFrameLimit;
    private final long globalByteLimit;
    private final int sessionFrameLimit;
    private final long sessionByteLimit;
    private final long idleSessionNanos;
    private final ArrayDeque<String> rotation = new ArrayDeque<>();
    private final Set<String> registered = new HashSet<>();
    private final Map<String, Long> lastSeen = new HashMap<>();
    private final Map<String, Usage> sessionUsage = new HashMap<>();
    private long windowStartedAt = Long.MIN_VALUE;
    private int globalFrames;
    private long globalBytes;

    OutboundDrainBudget() {
        this(DEFAULT_WINDOW_NANOS, DEFAULT_GLOBAL_FRAMES, DEFAULT_GLOBAL_BYTES,
                DEFAULT_SESSION_FRAMES, DEFAULT_SESSION_BYTES, DEFAULT_IDLE_SESSION_NANOS);
    }

    OutboundDrainBudget(long windowNanos, int globalFrameLimit, long globalByteLimit,
                        int sessionFrameLimit, long sessionByteLimit, long idleSessionNanos) {
        if (windowNanos < 1 || globalFrameLimit < 1 || globalByteLimit < 1
                || sessionFrameLimit < 1 || sessionByteLimit < 1 || idleSessionNanos < windowNanos) {
            throw new IllegalArgumentException("Outbound drain budget limits must be positive and idle timeout >= window");
        }
        this.windowNanos = windowNanos;
        this.globalFrameLimit = globalFrameLimit;
        this.globalByteLimit = globalByteLimit;
        this.sessionFrameLimit = sessionFrameLimit;
        this.sessionByteLimit = sessionByteLimit;
        this.idleSessionNanos = idleSessionNanos;
    }

    synchronized boolean tryAcquire(String sessionKey, int frameBytes, long nowNanos) {
        if (sessionKey == null || sessionKey.isBlank()) {
            throw new IllegalArgumentException("sessionKey must not be blank");
        }
        if (frameBytes < 1) throw new IllegalArgumentException("frameBytes must be positive");
        advanceWindow(nowNanos);
        evictIdleSessions(nowNanos);
        lastSeen.put(sessionKey, nowNanos);
        if (registered.add(sessionKey)) rotation.addLast(sessionKey);
        if (!sessionKey.equals(rotation.peekFirst())) return false;

        if (globalFrames >= globalFrameLimit || frameBytes > globalByteLimit - globalBytes) return false;
        Usage usage = sessionUsage.computeIfAbsent(sessionKey, ignored -> new Usage());
        if (usage.frames >= sessionFrameLimit || frameBytes > sessionByteLimit - usage.bytes) {
            rotateHead();
            return false;
        }
        globalFrames++;
        globalBytes += frameBytes;
        usage.frames++;
        usage.bytes += frameBytes;
        rotateHead();
        return true;
    }

    /** Removes an empty session from rotation but preserves its current-window quota. */
    synchronized void pause(String sessionKey) {
        registered.remove(sessionKey);
        rotation.remove(sessionKey);
    }

    synchronized void unregister(String sessionKey) {
        registered.remove(sessionKey);
        rotation.remove(sessionKey);
        lastSeen.remove(sessionKey);
        sessionUsage.remove(sessionKey);
    }

    synchronized int globalFrames(long nowNanos) {
        advanceWindow(nowNanos);
        evictIdleSessions(nowNanos);
        return globalFrames;
    }

    synchronized long globalBytes(long nowNanos) {
        advanceWindow(nowNanos);
        evictIdleSessions(nowNanos);
        return globalBytes;
    }

    synchronized int queuedSessions() {
        return rotation.size();
    }

    private void advanceWindow(long nowNanos) {
        if (windowStartedAt == Long.MIN_VALUE || nowNanos < windowStartedAt
                || nowNanos - windowStartedAt >= windowNanos) {
            windowStartedAt = nowNanos;
            globalFrames = 0;
            globalBytes = 0L;
            sessionUsage.clear();
        }
    }

    private void evictIdleSessions(long nowNanos) {
        Iterator<Map.Entry<String, Long>> iterator = lastSeen.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            long seen = entry.getValue();
            if (nowNanos < seen || nowNanos - seen > idleSessionNanos) {
                String sessionKey = entry.getKey();
                iterator.remove();
                registered.remove(sessionKey);
                rotation.remove(sessionKey);
                sessionUsage.remove(sessionKey);
            }
        }
    }

    private void rotateHead() {
        String head = rotation.pollFirst();
        if (head != null) rotation.addLast(head);
    }
}
