package ru.nexora.world;

import ru.nexora.runtime.OwnershipGuard;
import ru.nexora.runtime.StateOwnership;

import java.util.Objects;

/** Minimal mutable entity boundary with explicit ENTITY ownership. */
public final class EntityState {
    private final StateOwnership ownership;
    private double x;
    private double y;
    private double z;

    public EntityState(StateOwnership ownership) {
        this.ownership = Objects.requireNonNull(ownership, "ownership");
    }

    public void move(double x, double y, double z) {
        try (OwnershipGuard ignored = OwnershipGuard.acquire(ownership, StateOwnership.Domain.ENTITY)) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    public Position position() {
        return new Position(x, y, z);
    }

    public record Position(double x, double y, double z) {}
}
