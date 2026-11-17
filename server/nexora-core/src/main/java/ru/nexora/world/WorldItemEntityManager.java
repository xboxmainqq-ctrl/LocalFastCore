package ru.nexora.world;

import ru.nexora.inventory.ItemStack;
import ru.nexora.inventory.PlayerInventory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-authoritative dropped-item actor manager. Durable storage is optional
 * for tests and embedded worlds; production worlds persist each actor mutation.
 */
public final class WorldItemEntityManager {
    public static final double DEFAULT_PICKUP_RADIUS = 1.5;
    public static final long DEFAULT_PICKUP_DELAY_MILLIS = 500;
    public static final long DEFAULT_DESPAWN_AGE_MILLIS = 10 * 60 * 1000L;
    public static final double DEFAULT_MERGE_RADIUS = 0.75;
    private final List<WorldItemEntity> removedByTick = new ArrayList<>();
    private final AtomicLong nextRuntimeId = new AtomicLong(1_000_000);
    private final ConcurrentMap<Long, WorldItemEntity> entities = new ConcurrentHashMap<>();
    private final WorldItemEntityStorage storage;

    public WorldItemEntityManager() {
        this.storage = null;
    }

    /** Loads persisted actors before the world begins accepting players. */
    public WorldItemEntityManager(WorldItemEntityStorage storage) throws IOException {
        this.storage = Objects.requireNonNull(storage, "storage");
        long highestRuntimeId = 999_999;
        for (WorldItemEntity entity : storage.load()) {
            if (entity.runtimeId() == Long.MAX_VALUE
                    || entities.putIfAbsent(entity.runtimeId(), entity) != null) {
                throw new IOException("Invalid or duplicate persisted dropped-item runtime ID");
            }
            highestRuntimeId = Math.max(highestRuntimeId, entity.runtimeId());
        }
        nextRuntimeId.set(highestRuntimeId + 1);
    }

    public synchronized WorldItemEntity spawn(ItemStack stack, double x, double y, double z,
                                 double velocityX, double velocityY, double velocityZ,
                                 long nowMillis, long pickupDelayMillis, String ownerXuid) {
        Objects.requireNonNull(stack, "stack");
        if (nowMillis < 0 || pickupDelayMillis < 0 || nowMillis > Long.MAX_VALUE - pickupDelayMillis) {
            throw new IllegalArgumentException("Invalid item actor timing");
        }
        long runtimeId = nextRuntimeId.getAndIncrement();
        if (runtimeId <= 0 || runtimeId == Long.MAX_VALUE) {
            throw new IllegalStateException("Item actor runtime ID space exhausted");
        }
        WorldItemEntity entity = new WorldItemEntity(-runtimeId, runtimeId, stack,
                x, y, z, velocityX, velocityY, velocityZ,
                nowMillis + pickupDelayMillis, ownerXuid);
        entities.put(runtimeId, entity);
        try {
            persist();
        } catch (RuntimeException exception) {
            entities.remove(runtimeId, entity);
            throw exception;
        }
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

    public synchronized boolean remove(long runtimeId) {
        WorldItemEntity entity = entities.get(runtimeId);
        if (entity == null) return false;
        synchronized (entity) {
            if (!entities.remove(runtimeId, entity)) return false;
            try {
                persist();
                return true;
            } catch (RuntimeException exception) {
                entities.putIfAbsent(runtimeId, entity);
                throw exception;
            }
        }
    }

    /** Restores a previously removed immutable actor after a failed durable inventory update. */
    public synchronized void restore(WorldItemEntity entity) {
        Objects.requireNonNull(entity, "entity");
        if (entity.runtimeId() == Long.MAX_VALUE) {
            throw new IllegalArgumentException("Item actor runtime ID space exhausted");
        }
        if (entities.putIfAbsent(entity.runtimeId(), entity) != null) {
            throw new IllegalStateException("Item actor runtime ID is already present");
        }
        nextRuntimeId.accumulateAndGet(entity.runtimeId() + 1, Math::max);
        try {
            persist();
        } catch (RuntimeException exception) {
            entities.remove(entity.runtimeId(), entity);
            throw exception;
        }
    }

    /**
     * Picks up an entire stack only when the player can accept all of it.
     * Partial inventory insertion is rolled back, leaving the world actor unchanged.
     */
    public synchronized boolean pickup(long runtimeId, PlayerInventory inventory, String playerXuid,
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
                if (!entities.remove(runtimeId, entity)) {
                    inventory.restore(before, selectedSlot);
                    return false;
                }
                try {
                    persist();
                    return true;
                } catch (RuntimeException exception) {
                    entities.putIfAbsent(runtimeId, entity);
                    inventory.restore(before, selectedSlot);
                    throw exception;
                }
            }
        }
    }

    /** Flushes the current actor snapshot; a no-storage manager is a no-op. */
    public synchronized void save() throws IOException {
        if (storage != null) storage.save(snapshot());
    }

    private void persist() {
        if (storage == null) return;
        try {
            storage.save(snapshot());
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to persist dropped world items", exception);
        }
    }

    /**
     * Advances dropped-item physics. The two-argument overload is retained for
     * deterministic physics-only callers; production ticks pass wall-clock time
     * to enable despawning.
     */
    public synchronized List<WorldItemEntity> tick(World world, double deltaSeconds) {
        return tick(world, deltaSeconds, -1);
    }

    public synchronized List<WorldItemEntity> tick(World world, double deltaSeconds, long nowMillis) {
        Objects.requireNonNull(world, "world");
        if (!Double.isFinite(deltaSeconds) || deltaSeconds <= 0 || nowMillis < -1) {
            throw new IllegalArgumentException("Invalid item actor tick timing");
        }
        double dt = Math.min(0.1, deltaSeconds);
        List<WorldItemEntity> before = snapshot();
        List<WorldItemEntity> moved = new ArrayList<>();
        List<WorldItemEntity> removed = new ArrayList<>();
        boolean changed = false;
        boolean durableChanged = false;
        try {
            for (WorldItemEntity current : before) {
                if (nowMillis >= 0 && nowMillis >= current.pickupAvailableAtMillis()
                        && nowMillis - current.pickupAvailableAtMillis() >= DEFAULT_DESPAWN_AGE_MILLIS) {
                    if (entities.remove(current.runtimeId(), current)) {
                        removed.add(current);
                        changed = true;
                        durableChanged = true;
                    }
                    continue;
                }

                double vx = current.velocityX() * 0.98;
                double vy = current.velocityY() - 32.0 * dt;
                double vz = current.velocityZ() * 0.98;
                double nextX = current.x() + vx * dt;
                double nextZ = current.z() + vz * dt;
                if (solid(world, floor(nextX), floor(current.y()), floor(nextZ))) {
                    nextX = current.x();
                    nextZ = current.z();
                    vx = 0;
                    vz = 0;
                }

                double nextY = current.y() + vy * dt;
                int supportY = floor(nextY - 0.125);
                if (vy < 0 && solid(world, floor(nextX), supportY, floor(nextZ))) {
                    nextY = supportY + 1.125;
                    vy = 0;
                    vx *= 0.6;
                    vz *= 0.6;
                } else if (solid(world, floor(nextX), floor(nextY), floor(nextZ))) {
                    nextY = current.y();
                    vy = 0;
                }

                WorldItemEntity updated = new WorldItemEntity(
                        current.uniqueId(), current.runtimeId(), current.stack(),
                        nextX, nextY, nextZ, vx, vy, vz,
                        current.pickupAvailableAtMillis(), current.ownerXuid());
                if (!updated.equals(current) && entities.replace(current.runtimeId(), current, updated)) {
                    moved.add(updated);
                    changed = true;
                }
            }

            for (WorldItemEntity first : snapshot()) {
                WorldItemEntity currentFirst = entities.get(first.runtimeId());
                if (currentFirst == null) continue;
                for (WorldItemEntity second : snapshot()) {
                    if (second.runtimeId() <= currentFirst.runtimeId()) continue;
                    WorldItemEntity currentSecond = entities.get(second.runtimeId());
                    if (currentSecond == null || !canMerge(currentFirst, currentSecond)) continue;
                    int combined = currentFirst.stack().count() + currentSecond.stack().count();
                    if (combined > currentFirst.stack().maxStackSize()) continue;
                    double dx = currentFirst.x() - currentSecond.x();
                    double dy = currentFirst.y() - currentSecond.y();
                    double dz = currentFirst.z() - currentSecond.z();
                    if (dx * dx + dy * dy + dz * dz > DEFAULT_MERGE_RADIUS * DEFAULT_MERGE_RADIUS) continue;
                    currentFirst = new WorldItemEntity(currentFirst.uniqueId(), currentFirst.runtimeId(),
                            new ItemStack(currentFirst.stack().itemId(), combined, currentFirst.stack().maxStackSize()),
                            currentFirst.x(), currentFirst.y(), currentFirst.z(),
                            currentFirst.velocityX(), currentFirst.velocityY(), currentFirst.velocityZ(),
                            Math.max(currentFirst.pickupAvailableAtMillis(), currentSecond.pickupAvailableAtMillis()),
                            currentFirst.ownerXuid());
                    entities.put(currentFirst.runtimeId(), currentFirst);
                    if (entities.remove(currentSecond.runtimeId(), currentSecond)) {
                        removed.add(currentSecond);
                        durableChanged = true;
                    }
                    long mergedRuntimeId = currentFirst.runtimeId();
                    moved.removeIf(entity -> entity.runtimeId() == mergedRuntimeId);
                    moved.add(currentFirst);
                    changed = true;
                }
            }
            if (durableChanged) persist();
            removedByTick.addAll(removed);
            return List.copyOf(moved);
        } catch (RuntimeException exception) {
            entities.clear();
            for (WorldItemEntity entity : before) entities.put(entity.runtimeId(), entity);
            throw exception;
        }
    }

    private static boolean canMerge(WorldItemEntity first, WorldItemEntity second) {
        return first.stack().itemId().equals(second.stack().itemId())
                && first.stack().maxStackSize() == second.stack().maxStackSize()
                && Objects.equals(first.ownerXuid(), second.ownerXuid());
    }

    /** Returns actors removed by the most recent successful tick for client despawn broadcasts. */
    public synchronized List<WorldItemEntity> drainRemovedByTick() {
        List<WorldItemEntity> result = List.copyOf(removedByTick);
        removedByTick.clear();
        return result;
    }

    private static boolean solid(World world, int x, int y, int z) {
        return y >= WorldChunk.MIN_Y && y <= WorldChunk.MAX_Y
                && world.getBlock(x, y, z) != BlockType.AIR;
    }

    private static int floor(double value) {
        if (value <= Integer.MIN_VALUE || value >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Item actor coordinate is outside world bounds");
        }
        return (int) Math.floor(value);
    }

    public int size() {
        return entities.size();
    }
}
