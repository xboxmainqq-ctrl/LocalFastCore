package ru.nexora.world;

import ru.nexora.runtime.OwnershipGuard;
import ru.nexora.runtime.StateOwnership;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** WORLD-owned registry for entities; entity mutation remains ENTITY-owned. */
public final class EntityRegistry {
    private final StateOwnership ownership;
    private final Map<Long, EntityState> entities = new HashMap<>();

    public EntityRegistry(StateOwnership ownership) {
        this.ownership = Objects.requireNonNull(ownership, "ownership");
    }

    public void add(long id, EntityState entity) {
        try (OwnershipGuard ignored = OwnershipGuard.acquire(ownership, StateOwnership.Domain.WORLD)) {
            if (entities.putIfAbsent(id, Objects.requireNonNull(entity)) != null) {
                throw new IllegalArgumentException("Duplicate entity id: " + id);
            }
        }
    }

    public EntityState remove(long id) {
        try (OwnershipGuard ignored = OwnershipGuard.acquire(ownership, StateOwnership.Domain.WORLD)) {
            return entities.remove(id);
        }
    }

    public EntityState get(long id) {
        try (OwnershipGuard ignored = OwnershipGuard.acquire(ownership, StateOwnership.Domain.WORLD)) {
            return entities.get(id);
        }
    }

    public int size() {
        try (OwnershipGuard ignored = OwnershipGuard.acquire(ownership, StateOwnership.Domain.WORLD)) {
            return entities.size();
        }
    }
}
