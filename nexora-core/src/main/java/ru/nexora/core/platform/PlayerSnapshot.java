package ru.nexora.core.platform;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable player data crossing the platform boundary.
 * Deliberately contains no Nukkit types or mutable server objects.
 */
public record PlayerSnapshot(UUID uniqueId, String name) {
    public PlayerSnapshot {
        Objects.requireNonNull(uniqueId, "uniqueId");
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Player name must not be blank");
        }
    }
}
