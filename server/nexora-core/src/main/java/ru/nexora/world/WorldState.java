package ru.nexora.world;

import ru.nexora.runtime.OwnershipGuard;
import ru.nexora.runtime.StateOwnership;

import java.util.Objects;

/** Minimal NEXORA world state boundary; mutation requires WORLD ownership. */
public final class WorldState {
    private final StateOwnership ownership;
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

    public long worldTime() {
        return worldTime;
    }
}
