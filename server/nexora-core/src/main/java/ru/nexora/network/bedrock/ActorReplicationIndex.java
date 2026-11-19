package ru.nexora.network.bedrock;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Reverse indexes for actor replication.
 *
 * <p>Maps chunk -> interested sessions and actor -> sessions that currently have
 * that actor spawned. Movement fanout therefore visits only current chunk viewers
 * plus previous actor viewers, instead of scanning every connected session.</p>
 */
final class ActorReplicationIndex {
    private final ConcurrentMap<Long, Set<String>> chunkViewers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<Long>> chunksBySession = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Set<String>> actorViewers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<Long>> actorsBySession = new ConcurrentHashMap<>();

    void replaceChunkView(String networkId, Set<Long> desiredChunks) {
        // Session-owned chunk snapshots are immutable; only reverse buckets need
        // concurrent sets because they are updated by multiple sessions.
        Set<Long> next = Set.copyOf(desiredChunks);
        Set<Long> previous = chunksBySession.put(networkId, next);
        if (previous != null) {
            for (long key : previous) {
                if (!next.contains(key)) remove(chunkViewers, key, networkId);
            }
        }
        for (long key : next) {
            if (previous == null || !previous.contains(key)) {
                chunkViewers.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet())
                        .add(networkId);
            }
        }
    }

    void addActorViewer(long runtimeId, String networkId) {
        if (runtimeId <= 0) return;
        actorViewers.computeIfAbsent(runtimeId, ignored -> ConcurrentHashMap.newKeySet())
                .add(networkId);
        actorsBySession.computeIfAbsent(networkId, ignored -> ConcurrentHashMap.newKeySet())
                .add(runtimeId);
    }

    void removeActorViewer(long runtimeId, String networkId) {
        if (runtimeId <= 0) return;
        remove(actorViewers, runtimeId, networkId);
        actorsBySession.computeIfPresent(networkId, (ignored, actors) -> {
            actors.remove(runtimeId);
            return actors.isEmpty() ? null : actors;
        });
    }

    Set<String> chunkViewers(long chunkKey) {
        Set<String> viewers = chunkViewers.get(chunkKey);
        return viewers == null ? Set.of() : viewers;
    }

    Set<String> actorViewers(long runtimeId) {
        Set<String> viewers = actorViewers.get(runtimeId);
        return viewers == null ? Set.of() : viewers;
    }

    int chunkViewerCount(long chunkKey) {
        Set<String> viewers = chunkViewers.get(chunkKey);
        return viewers == null ? 0 : viewers.size();
    }

    int actorViewerCount(long runtimeId) {
        Set<String> viewers = actorViewers.get(runtimeId);
        return viewers == null ? 0 : viewers.size();
    }

    void removeSession(String networkId) {
        Set<Long> chunks = chunksBySession.remove(networkId);
        if (chunks != null) {
            for (long key : chunks) remove(chunkViewers, key, networkId);
        }
        Set<Long> actors = actorsBySession.remove(networkId);
        if (actors != null) {
            for (long runtimeId : actors) remove(actorViewers, runtimeId, networkId);
        }
    }

    void clear() {
        chunkViewers.clear();
        chunksBySession.clear();
        actorViewers.clear();
        actorsBySession.clear();
    }

    private static <K> void remove(ConcurrentMap<K, Set<String>> index, K key, String value) {
        index.computeIfPresent(key, (ignored, values) -> {
            values.remove(value);
            return values.isEmpty() ? null : values;
        });
    }
}
