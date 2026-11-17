package ru.nexora.combat;

import java.util.Objects;

/** Short-lived server-authoritative arrow state used by ranged mob attacks. */
public record ArrowProjectile(
        long uniqueId, long runtimeId, String ownerNetworkId,
        double x, double y, double z,
        double velocityX, double velocityY, double velocityZ,
        int ageTicks
) {
    public ArrowProjectile {
        if (uniqueId == 0 || runtimeId <= 0 || ageTicks < 0 || ageTicks > 400) {
            throw new IllegalArgumentException("Invalid arrow identity or age");
        }
        Objects.requireNonNull(ownerNetworkId, "ownerNetworkId");
        if (ownerNetworkId.isBlank() || !finite(x, y, z, velocityX, velocityY, velocityZ)
                || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000
                || y < -2048 || y > 2048
                || Math.abs(velocityX) > 8 || Math.abs(velocityY) > 8
                || Math.abs(velocityZ) > 8) {
            throw new IllegalArgumentException("Invalid arrow position or velocity");
        }
    }

    public ArrowProjectile advance() {
        double nextX = x + velocityX;
        double nextY = y + velocityY;
        double nextZ = z + velocityZ;
        return new ArrowProjectile(uniqueId, runtimeId, ownerNetworkId,
                nextX, nextY, nextZ,
                velocityX * 0.99, (velocityY - 0.05) * 0.99, velocityZ * 0.99,
                ageTicks + 1);
    }

    public boolean expired() {
        return ageTicks >= 400;
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }
}
