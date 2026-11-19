package ru.nexora.network;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded in-memory duplicate-message guard. It protects one running process
 * within the configured retention window; it is deliberately not advertised as
 * durable replay protection across restarts.
 */
public final class ClusterReplayGuard implements ClusterReplayFilter {
    private final int maximumEntries;
    private final long retentionMillis;
    private final java.util.HashMap<UUID, Long> seen = new java.util.HashMap<>();

    public ClusterReplayGuard(int maximumEntries, long retentionMillis) {
        if (maximumEntries < 1) throw new IllegalArgumentException("maximumEntries must be positive");
        if (retentionMillis < 1) throw new IllegalArgumentException("retentionMillis must be positive");
        this.maximumEntries = maximumEntries;
        this.retentionMillis = retentionMillis;
    }

    /** Returns false for a duplicate ID still inside the retention window. */
    public synchronized boolean accept(UUID messageId, long nowEpochMillis) {
        Objects.requireNonNull(messageId, "messageId");
        if (nowEpochMillis < 0) throw new IllegalArgumentException("Negative current time");
        Iterator<Map.Entry<UUID, Long>> iterator = seen.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            if (entry.getValue() <= nowEpochMillis) iterator.remove();
        }
        if (seen.containsKey(messageId)) return false;
        long expires;
        try { expires = Math.addExact(nowEpochMillis, retentionMillis); }
        catch (ArithmeticException overflow) { expires = Long.MAX_VALUE; }
        if (seen.size() >= maximumEntries) return false; // fail closed; never evict a live replay key
        seen.put(messageId, expires);
        return true;
    }

    public synchronized int size() { return seen.size(); }
}
