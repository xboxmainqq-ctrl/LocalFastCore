package ru.nexora.world;

import ru.nexora.runtime.OwnershipGuard;
import ru.nexora.runtime.StateOwnership;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Minimal NEXORA world state boundary with explicit chunk ownership. */
public final class WorldState {
    private final StateOwnership ownership;
    private final Map<ChunkKey, ChunkState> chunks = new HashMap<>();
    private long worldTime;

    public WorldState(StateOwnership ownership) {
        this.ownership = Objects.requireNonNull(ownership, "ownership");
    }

    public void tick(long delta) {
        if (delta < 0) throw new IllegalArgumentException("delta < 0");
        try (OwnershipGuard ignored = OwnershipGuard.acquire(ownership, StateOwnership.Domain.WORLD)) {
            worldTime += delta;
        }
    }

    public ChunkState getOrCreateChunk(ChunkKey key) {
        try (OwnershipGuard ignored = OwnershipGuard.acquire(ownership, StateOwnership.Domain.WORLD)) {
            return chunks.computeIfAbsent(Objects.requireNonNull(key), k -> new ChunkState(k, ownership));
        }
    }

    public int loadedChunkCount() {
        try (OwnershipGuard ignored = OwnershipGuard.acquire(ownership, StateOwnership.Domain.WORLD)) {
            return chunks.size();
        }
    }

    public long worldTime() { return worldTime; }
}
