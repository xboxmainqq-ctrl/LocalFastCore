package ru.nexora.world;

import ru.nexora.runtime.OwnershipGuard;
import ru.nexora.runtime.StateOwnership;

import java.util.Objects;

/** Minimal chunk lifecycle state. Data mutation remains explicitly WORLD-owned. */
public final class ChunkState {
    public enum Status { UNLOADED, LOADING, ACTIVE, UNLOADING }

    private final ChunkKey key;
    private final StateOwnership ownership;
    private Status status = Status.UNLOADED;

    public ChunkState(ChunkKey key, StateOwnership ownership) {
        this.key = Objects.requireNonNull(key, "key");
        this.ownership = Objects.requireNonNull(ownership, "ownership");
    }

    public void activate() {
        try (OwnershipGuard ignored = OwnershipGuard.acquire(ownership, StateOwnership.Domain.WORLD)) {
            if (status != Status.UNLOADED && status != Status.LOADING) {
                throw new IllegalStateException("Cannot activate chunk from " + status);
            }
            status = Status.ACTIVE;
        }
    }

    public void beginLoading() {
        try (OwnershipGuard ignored = OwnershipGuard.acquire(ownership, StateOwnership.Domain.WORLD)) {
            if (status != Status.UNLOADED) throw new IllegalStateException("Cannot load chunk from " + status);
            status = Status.LOADING;
        }
    }

    public void beginUnloading() {
        try (OwnershipGuard ignored = OwnershipGuard.acquire(ownership, StateOwnership.Domain.WORLD)) {
            if (status != Status.ACTIVE) throw new IllegalStateException("Cannot unload chunk from " + status);
            status = Status.UNLOADING;
        }
    }

    public ChunkKey key() { return key; }
    public Status status() { return status; }
}
