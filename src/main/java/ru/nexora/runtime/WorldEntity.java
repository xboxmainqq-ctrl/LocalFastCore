package ru.nexora.runtime;

import java.util.Objects;
import java.util.UUID;

/** Immutable authoritative state for one entity in a NEXORA world. */
public record WorldEntity(
        UUID id,
        Type type,
        String name,
        double x,
        double y,
        double z,
        double yaw,
        int skinIndex
) {
    public WorldEntity {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(name, "name");
        int maxNameLength = type == Type.ITEM ? 96 : 64;
        if (name.isBlank() || name.length() > maxNameLength || !name.equals(name.trim())) {
            throw new IllegalArgumentException("Entity name exceeds the limit for its type");
        }
        if (!finiteCoordinate(x) || !finiteCoordinate(y) || !finiteCoordinate(z)) {
            throw new IllegalArgumentException("Entity coordinates must be finite and within world bounds");
        }
        if (!Double.isFinite(yaw) || yaw < -180.0 || yaw > 180.0) {
            throw new IllegalArgumentException("Entity yaw must be between -180 and 180 degrees");
        }
        if (type == Type.PLAYER) {
            if (skinIndex < 0 || skinIndex > 4) {
                throw new IllegalArgumentException("Player skin index must be between 0 and 4");
            }
        } else if (skinIndex != -1) {
            throw new IllegalArgumentException("Only player entities may have a player skin index");
        }
    }

    public static WorldEntity player(UUID id, String name, double x, double y, double z,
                                     double yaw, int skinIndex) {
        return new WorldEntity(id, Type.PLAYER, name, x, y, z, yaw, skinIndex);
    }

    public WorldEntity at(double newX, double newY, double newZ, double newYaw) {
        return new WorldEntity(id, type, name, newX, newY, newZ, newYaw, skinIndex);
    }

    private static boolean finiteCoordinate(double value) {
        return Double.isFinite(value) && Math.abs(value) <= 30_000_000d;
    }

    public enum Type {
        PLAYER,
        MOB,
        ITEM,
        PROJECTILE
    }
}
