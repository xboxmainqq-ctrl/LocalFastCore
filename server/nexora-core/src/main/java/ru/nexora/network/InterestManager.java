package ru.nexora.network;

import ru.nexora.world.ChunkKey;

import java.util.*;

/**
 * Maintains observer interest areas without coupling them to transport.
 * Only observer-specific work should use this layer; authoritative simulation
 * remains server-owned.
 */
public final class InterestManager {
    private final Map<Long, InterestArea> observers = new HashMap<>();

    public synchronized void setInterest(long playerId, InterestArea area) {
        observers.put(playerId, Objects.requireNonNull(area));
    }

    public synchronized void remove(long playerId) {
        observers.remove(playerId);
    }

    public synchronized boolean observes(long playerId, ChunkKey chunk) {
        InterestArea area = observers.get(playerId);
        return area != null && area.contains(chunk.x(), chunk.z());
    }

    public synchronized List<Long> observersOf(ChunkKey chunk) {
        List<Long> result = new ArrayList<>();
        for (Map.Entry<Long, InterestArea> entry : observers.entrySet()) {
            if (entry.getValue().contains(chunk.x(), chunk.z())) result.add(entry.getKey());
        }
        return List.copyOf(result);
    }

    public synchronized int size() {
        return observers.size();
    }
}
