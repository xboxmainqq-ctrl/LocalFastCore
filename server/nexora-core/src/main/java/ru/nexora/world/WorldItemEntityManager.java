package ru.nexora.world;

import ru.nexora.inventory.ItemStack;
import ru.nexora.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory dropped-item actor manager. Physics and persistence are deliberately
 * separate follow-up systems; pickup and inventory conservation are authoritative here.
 */
public final class WorldItemEntityManager {
    public static final double DEFAULT_PICKUP_RADIUS = 1.5;
    public static final long DEFAULT_PICKUP_DELAY_MILLIS = 500;
    private final AtomicLong nextRuntimeId = new AtomicLong(1_000_000);
    private final ConcurrentMap<Long, WorldItemEntity> entities = new ConcurrentHashMap<>();

    public WorldItemEntity spawn(ItemStack stack, double x, double y, double z,
                                 double velocityX, double velocityY, double velocityZ,
                                 long nowMillis, long pickupDelayMillis, String ownerXuid) {
        Objects.requireNonNull(stack, "stack");
        if (nowMillis < 0 || pickupDelayMillis < 0 || nowMillis > Long.MAX_VALUE - pickupDelayMillis) {
            throw new IllegalArgumentException("Invalid item actor timing");
        }
        long runtimeId = nextRuntimeId.getAndIncrement();
        if (runtimeId <= 0) throw new IllegalStateException("Item actor runtime ID space exhausted");
        WorldItemEntity entity = new WorldItemEntity(-runtimeId, runtimeId, stack,
                x, y, z, velocityX, velocityY, velocityZ,
                nowMillis + pickupDelayMillis, ownerXuid);
        entities.put(runtimeId, entity);
        return entity;
    }

    public Optional<WorldItemEntity> find(long runtimeId) {
        return Optional.ofNullable(entities.get(runtimeId));
    }

    public List<WorldItemEntity> snapshot() {
        List<WorldItemEntity> result = new ArrayList<>(entities.values());
        result.sort(Comparator.comparingLong(WorldItemEntity::runtimeId));
        return List.copyOf(result);
    }

    public boolean remove(long runtimeId) {
        WorldItemEntity entity = entities.get(runtimeId);
        if (entity == null) return false;
        synchronized (entity) {
            return entities.remove(runtimeId, entity);
        }
    }

    /** Restores a previously removed immutable actor after a failed durable inventory update. */
    public void restore(WorldItemEntity entity) {
        Objects.requireNonNull(entity, "entity");
        if (entities.putIfAbsent(entity.runtimeId(), entity) != null) {
            throw new IllegalStateException("Item actor runtime ID is already present");
        }
    }

    /**
     * Picks up an entire stack only when the player can accept all of it.
     * Partial inventory insertion is rolled back, leaving the world actor unchanged.
     */
    public boolean pickup(long runtimeId, PlayerInventory inventory, String playerXuid,
                          double playerX, double playerY, double playerZ, long nowMillis) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(playerXuid, "playerXuid");
        if (!playerXuid.matches("[A-Za-z0-9_-]{1,64}") || nowMillis < 0
                || !Double.isFinite(playerX) || !Double.isFinite(playerY) || !Double.isFinite(playerZ)) {
            return false;
        }
        WorldItemEntity entity = entities.get(runtimeId);
        if (entity == null) return false;
        synchronized (entity) {
            if (entities.get(runtimeId) != entity) return false;
            if (nowMillis < entity.pickupAvailableAtMillis()) return false;
            if (entity.ownerXuid() != null && !entity.ownerXuid().equals(playerXuid)
                    && nowMillis < entity.pickupAvailableAtMillis() + 2_000) return false;
            double dx = playerX - entity.x();
            double dy = playerY - entity.y();
            double dz = playerZ - entity.z();
            if (dx * dx + dy * dy + dz * dz > DEFAULT_PICKUP_RADIUS * DEFAULT_PICKUP_RADIUS) return false;

            synchronized (inventory) {
                List<Optional<ItemStack>> before = inventory.snapshot();
                int selectedSlot = inventory.selectedHotbarSlot();
                int remaining = inventory.add(entity.stack());
                if (remaining != 0) {
                    inventory.restore(before, selectedSlot);
                    return false;
                }
                return entities.remove(runtimeId, entity);
            }
        }
    }

    public int size() {
        return entities.size();
    }
}
