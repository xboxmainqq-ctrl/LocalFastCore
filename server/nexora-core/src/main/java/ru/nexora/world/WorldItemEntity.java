package ru.nexora.world;

import ru.nexora.inventory.ItemStack;

import java.util.Objects;

/** Immutable server-authoritative dropped item actor. */
public record WorldItemEntity(
        long uniqueId,
        long runtimeId,
        ItemStack stack,
        double x,
        double y,
        double z,
        double velocityX,
        double velocityY,
        double velocityZ,
        long pickupAvailableAtMillis,
        String ownerXuid
) {
    public WorldItemEntity {
        if (uniqueId == 0 || runtimeId <= 0) throw new IllegalArgumentException("Invalid item actor ID");
        Objects.requireNonNull(stack, "stack");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Double.isFinite(velocityX) || !Double.isFinite(velocityY)
                || !Double.isFinite(velocityZ)) {
            throw new IllegalArgumentException("Item actor position and velocity must be finite");
        }
        if (pickupAvailableAtMillis < 0) throw new IllegalArgumentException("Invalid pickup availability time");
        if (ownerXuid != null && !ownerXuid.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid item owner XUID");
        }
    }

    public WorldItemEntity withStack(ItemStack replacement) {
        return new WorldItemEntity(uniqueId, runtimeId, replacement, x, y, z,
                velocityX, velocityY, velocityZ, pickupAvailableAtMillis, ownerXuid);
    }
}
