package ru.nexora.world;

import ru.nexora.inventory.ItemStack;
import ru.nexora.inventory.PlayerInventory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
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
    // WORLD-owned secondary index avoids scanning every dropped item when a client publishes chunks.
    private final Map<Long, java.util.Set<Long>> entitiesByChunk = new HashMap<>();
    private final Map<Long, Long> entityChunkKeys = new HashMap<>();
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
            index(entity);
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
        index(entity);
        try {
            persist();
        } catch (RuntimeException exception) {
            entities.remove(runtimeId, entity);
            unindex(runtimeId);
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
                unindex(runtimeId);
                return true;
            } catch (RuntimeException exception) {
                entities.putIfAbsent(runtimeId, entity);
                index(entity);
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
        index(entity);
        nextRuntimeId.accumulateAndGet(entity.runtimeId() + 1, Math::max);
        try {
            persist();
        } catch (RuntimeException exception) {
            entities.remove(entity.runtimeId(), entity);
            unindex(entity.runtimeId());
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
                    && nowMillis - entity.pickupAvailableAtMillis() < 2_000) return false;
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
                unindex(runtimeId);
                try {
                    persist();
                    return true;
                } catch (RuntimeException exception) {
                    entities.putIfAbsent(runtimeId, entity);
                    index(entity);
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
                        unindex(current.runtimeId());
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
                    // Dropped items rebound once with strong damping, then settle
                    // instead of jittering forever on flat ground.
                    vy = Math.abs(vy) < 0.08 ? 0 : -vy * 0.25;
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
                    reindex(updated);
                    moved.add(updated);
                    changed = true;
                }
            }

            // Candidate IDs are stable for this merge pass. Rebuilding and sorting a
            // full entity snapshot for every outer-loop actor made this phase allocate
            // repeatedly and added avoidable sorting work to the O(n^2) pair scan.
            List<WorldItemEntity> mergeCandidates = snapshot();
            Map<MergeCell, List<WorldItemEntity>> mergeBuckets = new HashMap<>();
            for (WorldItemEntity candidate : mergeCandidates) {
                mergeBuckets.computeIfAbsent(mergeCell(candidate), ignored -> new ArrayList<>()).add(candidate);
            }
            for (WorldItemEntity first : mergeCandidates) {
                WorldItemEntity currentFirst = entities.get(first.runtimeId());
                if (currentFirst == null) continue;

                // Only actors in the same or adjacent cells can be within the merge radius.
                // Candidate order remains runtime-ID order to keep merges deterministic.
                MergeCell cell = mergeCell(currentFirst);
                List<WorldItemEntity> nearby = new ArrayList<>();
                for (long dx = -1; dx <= 1; dx++) {
                    for (long dy = -1; dy <= 1; dy++) {
                        for (long dz = -1; dz <= 1; dz++) {
                            List<WorldItemEntity> bucket = mergeBuckets.get(
                                    new MergeCell(cell.x() + dx, cell.y() + dy, cell.z() + dz));
                            if (bucket != null) nearby.addAll(bucket);
                        }
                    }
                }
                nearby.sort(Comparator.comparingLong(WorldItemEntity::runtimeId));
                for (WorldItemEntity second : nearby) {
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
                            currentFirst.stack().withCount(combined),
                            currentFirst.x(), currentFirst.y(), currentFirst.z(),
                            currentFirst.velocityX(), currentFirst.velocityY(), currentFirst.velocityZ(),
                            Math.max(currentFirst.pickupAvailableAtMillis(), currentSecond.pickupAvailableAtMillis()),
                            currentFirst.ownerXuid());
                    entities.put(currentFirst.runtimeId(), currentFirst);
                    if (entities.remove(currentSecond.runtimeId(), currentSecond)) {
                        unindex(currentSecond.runtimeId());
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
            entitiesByChunk.clear();
            entityChunkKeys.clear();
            for (WorldItemEntity entity : before) {
                entities.put(entity.runtimeId(), entity);
                index(entity);
            }
            throw exception;
        }
    }

    /** Returns dropped items only from chunks currently published to a recipient. */
    public synchronized List<WorldItemEntity> snapshotInChunkKeys(java.util.Set<Long> chunkKeys) {
        Objects.requireNonNull(chunkKeys, "chunkKeys");
        java.util.Set<Long> selected = new java.util.TreeSet<>();
        for (Long chunkKey : chunkKeys) {
            if (chunkKey == null) continue;
            java.util.Set<Long> ids = entitiesByChunk.get(chunkKey);
            if (ids != null) selected.addAll(ids);
        }
        List<WorldItemEntity> result = new ArrayList<>(selected.size());
        for (long id : selected) {
            WorldItemEntity entity = entities.get(id);
            if (entity != null) result.add(entity);
        }
        return List.copyOf(result);
    }

    private void index(WorldItemEntity entity) {
        long key = chunkKey(entity);
        entityChunkKeys.put(entity.runtimeId(), key);
        entitiesByChunk.computeIfAbsent(key, ignored -> new java.util.HashSet<>())
                .add(entity.runtimeId());
    }

    private void reindex(WorldItemEntity entity) {
        long next = chunkKey(entity);
        Long previous = entityChunkKeys.get(entity.runtimeId());
        if (previous != null && previous == next) return;
        unindex(entity.runtimeId());
        entityChunkKeys.put(entity.runtimeId(), next);
        entitiesByChunk.computeIfAbsent(next, ignored -> new java.util.HashSet<>())
                .add(entity.runtimeId());
    }

    private void unindex(long runtimeId) {
        Long previous = entityChunkKeys.remove(runtimeId);
        if (previous == null) return;
        java.util.Set<Long> ids = entitiesByChunk.get(previous);
        if (ids == null) return;
        ids.remove(runtimeId);
        if (ids.isEmpty()) entitiesByChunk.remove(previous);
    }

    private static long chunkKey(WorldItemEntity entity) {
        int chunkX = (int) Math.floor(entity.x() / 16.0);
        int chunkZ = (int) Math.floor(entity.z() / 16.0);
        return ((long) chunkX << 32) | (chunkZ & 0xffff_ffffL);
    }

    private static MergeCell mergeCell(WorldItemEntity entity) {
        return new MergeCell(
                (long) Math.floor(entity.x() / DEFAULT_MERGE_RADIUS),
                (long) Math.floor(entity.y() / DEFAULT_MERGE_RADIUS),
                (long) Math.floor(entity.z() / DEFAULT_MERGE_RADIUS));
    }

    private record MergeCell(long x, long y, long z) {
    }

    private static boolean canMerge(WorldItemEntity first, WorldItemEntity second) {
        return first.stack().canStackWith(second.stack())
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
