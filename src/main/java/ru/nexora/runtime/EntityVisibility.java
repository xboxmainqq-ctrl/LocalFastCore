package ru.nexora.runtime;

import java.util.Objects;

/** Distance-based entity visibility calculations shared by world replication. */
public final class EntityVisibility {
    private EntityVisibility() {
    }

    /** Returns whether two entity positions are within an inclusive 3D radius. */
    public static boolean isWithin(
            NexoraMovementValidator.Position first,
            NexoraMovementValidator.Position second,
            double radius
    ) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (!Double.isFinite(radius) || radius < 0.0 || radius > 30_000_000.0) {
            throw new IllegalArgumentException("Visibility radius must be finite and within world bounds");
        }
        double dx = first.x() - second.x();
        double dy = first.y() - second.y();
        double dz = first.z() - second.z();
        double radiusSquared = radius * radius;
        return dx * dx + dy * dy + dz * dz <= radiusSquared;
    }
}
