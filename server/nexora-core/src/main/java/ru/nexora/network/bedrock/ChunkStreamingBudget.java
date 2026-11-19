package ru.nexora.network.bedrock;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Global time-window budget for chunk packet encoding/publication.
 *
 * <p>Admission rotates between active sessions. A session can encode only a
 * bounded number of chunks per window, and an idle session is evicted from the
 * rotation so it cannot block players that are actively streaming.</p>
 */
final class ChunkStreamingBudget {
    static final long DEFAULT_WINDOW_NANOS = 50_000_000L;
    static final int DEFAULT_GLOBAL_CHUNKS_PER_WINDOW = 64;
    static final int DEFAULT_SESSION_CHUNKS_PER_WINDOW = 4;
    static final long DEFAULT_IDLE_SESSION_NANOS = 250_000_000L;

    private final long windowNanos;
    private final int globalLimit;
    private final int sessionLimit;
    private final long idleSessionNanos;
    private final ArrayDeque<String> rotation = new ArrayDeque<>();
    private final Set<String> registered = new HashSet<>();
    private final Map<String, Long> lastSeen = new HashMap<>();
    private final Map<String, Integer> sessionUsage = new HashMap<>();
    private long windowStartedAt = Long.MIN_VALUE;
    private int globalUsage;

    ChunkStreamingBudget() {
        this(DEFAULT_WINDOW_NANOS, DEFAULT_GLOBAL_CHUNKS_PER_WINDOW,
                DEFAULT_SESSION_CHUNKS_PER_WINDOW, DEFAULT_IDLE_SESSION_NANOS);
    }

    ChunkStreamingBudget(long windowNanos, int globalLimit, int sessionLimit, long idleSessionNanos) {
        if (windowNanos < 1 || globalLimit < 1 || sessionLimit < 1 || idleSessionNanos < windowNanos) {
            throw new IllegalArgumentException("Chunk streaming budget limits must be positive and idle timeout >= window");
        }
        this.windowNanos = windowNanos;
        this.globalLimit = globalLimit;
        this.sessionLimit = sessionLimit;
        this.idleSessionNanos = idleSessionNanos;
    }

    synchronized boolean tryAcquire(String sessionKey, long nowNanos) {
        if (sessionKey == null || sessionKey.isBlank()) {
            throw new IllegalArgumentException("sessionKey must not be blank");
        }
        advanceWindow(nowNanos);
        evictIdleSessions(nowNanos);
        lastSeen.put(sessionKey, nowNanos);
        if (registered.add(sessionKey)) rotation.addLast(sessionKey);
        if (!sessionKey.equals(rotation.peekFirst())) return false;

        int usedBySession = sessionUsage.getOrDefault(sessionKey, 0);
        if (globalUsage >= globalLimit || usedBySession >= sessionLimit) {
            rotateHead();
            return false;
        }
        globalUsage++;
        sessionUsage.put(sessionKey, usedBySession + 1);
        rotateHead();
        return true;
    }

    /** Moves a session that is waiting on storage I/O behind sessions with ready chunks. */
    synchronized void yield(String sessionKey, long nowNanos) {
        advanceWindow(nowNanos);
        evictIdleSessions(nowNanos);
        if (registered.contains(sessionKey)) {
            lastSeen.put(sessionKey, nowNanos);
            rotation.remove(sessionKey);
            rotation.addLast(sessionKey);
        }
    }

    /** Removes a completed stream from rotation without resetting this window's quota. */
    synchronized void pause(String sessionKey) {
        registered.remove(sessionKey);
        rotation.remove(sessionKey);
    }

    synchronized void unregister(String sessionKey) {
        if (!registered.remove(sessionKey)) return;
        rotation.remove(sessionKey);
        lastSeen.remove(sessionKey);
        sessionUsage.remove(sessionKey);
    }

    synchronized int queuedSessions() {
        return rotation.size();
    }

    synchronized int globalUsage() {
        return globalUsage;
    }

    private void advanceWindow(long nowNanos) {
        if (windowStartedAt == Long.MIN_VALUE || nowNanos < windowStartedAt
                || nowNanos - windowStartedAt >= windowNanos) {
            windowStartedAt = nowNanos;
            globalUsage = 0;
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
