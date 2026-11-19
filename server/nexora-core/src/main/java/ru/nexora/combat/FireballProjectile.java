package ru.nexora.combat;

import java.util.Objects;

/** Server-authoritative, gravity-free fireball used by ghasts and blazes. */
public record FireballProjectile(
        long uniqueId, long runtimeId, String ownerNetworkId, String identifier,
        double x, double y, double z,
        double velocityX, double velocityY, double velocityZ,
        int ageTicks, float damage, int fireDurationSeconds
) {
    public static final int MAX_AGE_TICKS = 160;

    public FireballProjectile {
        if (uniqueId == 0 || runtimeId <= 0 || ageTicks < 0 || ageTicks > MAX_AGE_TICKS) {
            throw new IllegalArgumentException("Invalid fireball identity or age");
        }
        Objects.requireNonNull(ownerNetworkId, "ownerNetworkId");
        Objects.requireNonNull(identifier, "identifier");
        if (ownerNetworkId.isBlank()
                || !("minecraft:fireball".equals(identifier)
                || "minecraft:small_fireball".equals(identifier))
                || !finite(x, y, z, velocityX, velocityY, velocityZ, damage)
                || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000
                || y < -2048 || y > 2048
                || Math.abs(velocityX) > 8 || Math.abs(velocityY) > 8
                || Math.abs(velocityZ) > 8
                || damage <= 0 || damage > 20
                || fireDurationSeconds < 0 || fireDurationSeconds > 15) {
            throw new IllegalArgumentException("Invalid fireball state");
        }
    }

    /** Fireballs keep their launch vector and do not receive arrow gravity. */
    public FireballProjectile advance() {
        return new FireballProjectile(uniqueId, runtimeId, ownerNetworkId, identifier,
                x + velocityX, y + velocityY, z + velocityZ,
                velocityX * 0.99, velocityY * 0.99, velocityZ * 0.99,
                ageTicks + 1, damage, fireDurationSeconds);
    }

    public boolean expired() {
        return ageTicks >= MAX_AGE_TICKS;
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }
}
