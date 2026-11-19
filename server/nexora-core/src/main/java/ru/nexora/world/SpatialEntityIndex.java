package ru.nexora.world;

import ru.nexora.runtime.OwnershipGuard;
import ru.nexora.runtime.StateOwnership;

import java.util.*;

/** WORLD-owned spatial index mapping entities to chunk coordinates. */
public final class SpatialEntityIndex {
    private final StateOwnership ownership;
    private final Map<ChunkKey, Set<Long>> byChunk = new HashMap<>();
    private final Map<Long, ChunkKey> entityChunk = new HashMap<>();

    public SpatialEntityIndex(StateOwnership ownership) {
        this.ownership = Objects.requireNonNull(ownership, "ownership");
    }

    public void place(long entityId, ChunkKey chunk) {
        try (OwnershipGuard ignored = OwnershipGuard.acquire(ownership, StateOwnership.Domain.WORLD)) {
            Objects.requireNonNull(chunk);
            ChunkKey old = entityChunk.put(entityId, chunk);
            if (old != null && old.equals(chunk)) return;
            if (old != null) {
                Set<Long> oldSet = byChunk.get(old);
                if (oldSet != null) {
                    oldSet.remove(entityId);
                    if (oldSet.isEmpty()) byChunk.remove(old);
                }
            }
            byChunk.computeIfAbsent(chunk, ignoredKey -> new HashSet<>()).add(entityId);
        }
    }

    public void remove(long entityId) {
        try (OwnershipGuard ignored = OwnershipGuard.acquire(ownership, StateOwnership.Domain.WORLD)) {
            ChunkKey old = entityChunk.remove(entityId);
            if (old == null) return;
            Set<Long> set = byChunk.get(old);
            if (set != null) {
                set.remove(entityId);
                if (set.isEmpty()) byChunk.remove(old);
            }
        }
    }

    public Set<Long> entitiesIn(ChunkKey chunk) {
        try (OwnershipGuard ignored = OwnershipGuard.acquire(ownership, StateOwnership.Domain.WORLD)) {
            return Set.copyOf(byChunk.getOrDefault(chunk, Set.of()));
        }
    }
}
