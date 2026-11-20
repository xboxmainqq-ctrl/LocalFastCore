package ru.nexora.plugins.api;

import java.util.Objects;

/** Immutable world position expressed in server-neutral coordinates. */
public record Position(String worldId, double x, double y, double z,
                       float yaw, float pitch) {
    public Position {
        Objects.requireNonNull(worldId, "worldId");
        if (worldId.isBlank() || !Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(z) || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Position must contain a world and finite coordinates");
        }
    }
}
