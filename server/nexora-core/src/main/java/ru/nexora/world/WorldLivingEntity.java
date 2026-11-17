package ru.nexora.world;

import java.util.Objects;

/** Immutable server-owned living actor state for mobs and other damageable world entities. */
public record WorldLivingEntity(
        long uniqueId, long runtimeId, String identifier, Family family,
        double x, double y, double z,
        double velocityX, double velocityY, double velocityZ,
        float health, float maxHealth,
        long fireUntilMillis, long nextFireDamageMillis
) {
    public WorldLivingEntity {
        if (uniqueId == 0 || runtimeId <= 0) {
            throw new IllegalArgumentException("Living actor IDs must be non-zero and positive");
        }
        Objects.requireNonNull(identifier, "identifier");
        if (!identifier.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Living actor identifier must be namespaced");
        }
        Objects.requireNonNull(family, "family");
        if (!finite(x, y, z, velocityX, velocityY, velocityZ)
                || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000
                || y < WorldChunk.MIN_Y || y > WorldChunk.MAX_Y
                || Math.abs(velocityX) > 100 || Math.abs(velocityY) > 100 || Math.abs(velocityZ) > 100) {
            throw new IllegalArgumentException("Living actor position or velocity is outside supported bounds");
        }
        if (!Float.isFinite(maxHealth) || maxHealth <= 0 || maxHealth > 1024
                || !Float.isFinite(health) || health < 0 || health > maxHealth) {
            throw new IllegalArgumentException("Invalid living actor health");
        }
        if (fireUntilMillis < 0 || nextFireDamageMillis < 0) {
            throw new IllegalArgumentException("Living actor fire timestamps must be non-negative");
        }
    }

    public WorldLivingEntity damage(float amount) {
        if (!Float.isFinite(amount) || amount <= 0) {
            throw new IllegalArgumentException("Damage must be finite and positive");
        }
        return new WorldLivingEntity(uniqueId, runtimeId, identifier, family,
                x, y, z, velocityX, velocityY, velocityZ,
                Math.max(0, health - amount), maxHealth, fireUntilMillis, nextFireDamageMillis);
    }

    public WorldLivingEntity ignite(int durationSeconds, long nowMillis) {
        if (durationSeconds < 1 || durationSeconds > 120 || nowMillis < 0
                || nowMillis > Long.MAX_VALUE - durationSeconds * 1000L) {
            throw new IllegalArgumentException("Invalid living actor fire duration");
        }
        long nextUntil = Math.max(fireUntilMillis, nowMillis + durationSeconds * 1000L);
        long nextDamage = fireUntilMillis <= nowMillis ? nowMillis + 1000L : nextFireDamageMillis;
        return new WorldLivingEntity(uniqueId, runtimeId, identifier, family,
                x, y, z, velocityX, velocityY, velocityZ,
                health, maxHealth, nextUntil, nextDamage);
    }

    public WorldLivingEntity withPosition(double nextX, double nextY, double nextZ) {
        return new WorldLivingEntity(uniqueId, runtimeId, identifier, family,
                nextX, nextY, nextZ, velocityX, velocityY, velocityZ,
                health, maxHealth, fireUntilMillis, nextFireDamageMillis);
    }

    public WorldLivingEntity withVelocity(double nextX, double nextY, double nextZ) {
        return new WorldLivingEntity(uniqueId, runtimeId, identifier, family,
                x, y, z, nextX, nextY, nextZ, health, maxHealth,
                fireUntilMillis, nextFireDamageMillis);
    }

    public static Family familyForIdentifier(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        return switch (identifier) {
            case "minecraft:zombie", "minecraft:husk", "minecraft:drowned",
                    "minecraft:zombie_villager", "minecraft:zombie_villager_v2",
                    "minecraft:skeleton", "minecraft:stray", "minecraft:wither_skeleton",
                    "minecraft:wither", "minecraft:phantom", "minecraft:zombified_piglin",
                    "minecraft:zoglin", "minecraft:bogged" -> Family.UNDEAD;
            case "minecraft:spider", "minecraft:cave_spider",
                    "minecraft:silverfish", "minecraft:endermite", "minecraft:bee" -> Family.ARTHROPOD;
            default -> Family.NORMAL;
        };
    }

    public enum Family {
        NORMAL,
        UNDEAD,
        ARTHROPOD
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }
}
