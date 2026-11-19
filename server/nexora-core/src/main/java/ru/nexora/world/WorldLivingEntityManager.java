package ru.nexora.world;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/** Thread-safe registry and lifecycle owner for server-created living actors. */
public final class WorldLivingEntityManager {
    private static final int MAX_ENTITIES = 5_000;
    private final AtomicLong nextRuntimeId = new AtomicLong(2_000_000L);
    private final AtomicInteger entityCount = new AtomicInteger();
    private final ConcurrentMap<Long, WorldLivingEntity> entities = new ConcurrentSkipListMap<>();
    // WORLD-owned secondary index: entity IDs grouped by their current chunk.
    private final java.util.Map<ChunkKey, java.util.Set<Long>> entitiesByChunk = new java.util.HashMap<>();
    private final java.util.Map<Long, ChunkKey> entityChunks = new java.util.HashMap<>();
    private final WorldLivingEntityStorage storage;

    public WorldLivingEntityManager() {
        this.storage = null;
    }

    public WorldLivingEntityManager(WorldLivingEntityStorage storage) throws IOException {
        this.storage = Objects.requireNonNull(storage, "storage");
        long highestRuntimeId = 1_999_999L;
        for (WorldLivingEntity entity : storage.load()) {
            if (entity.runtimeId() == Long.MAX_VALUE
                    || entities.putIfAbsent(entity.runtimeId(), entity) != null) {
                throw new IOException("Invalid or duplicate persisted living actor runtime ID");
            }
            entityCount.incrementAndGet();
            index(entity);
            highestRuntimeId = Math.max(highestRuntimeId, entity.runtimeId());
        }
        nextRuntimeId.set(highestRuntimeId + 1);
    }

    public WorldLivingEntity spawn(
            String identifier, float maxHealth, double x, double y, double z
    ) {
        return spawn(identifier, WorldLivingEntity.familyForIdentifier(identifier),
                maxHealth, x, y, z);
    }

    public synchronized WorldLivingEntity spawn(
            String identifier, WorldLivingEntity.Family family,
            float maxHealth, double x, double y, double z
    ) {
        if (entityCount.get() >= MAX_ENTITIES) {
            throw new IllegalStateException("Living actor world cap reached");
        }
        long runtimeId = nextRuntimeId.getAndIncrement();
        if (runtimeId <= 0 || runtimeId == Long.MAX_VALUE) {
            throw new IllegalStateException("Living actor runtime ID space exhausted");
        }
        WorldLivingEntity entity = new WorldLivingEntity(
                -runtimeId, runtimeId, identifier, family,
                x, y, z, 0, 0, 0, maxHealth, maxHealth, 0, 0);
        if (entities.putIfAbsent(runtimeId, entity) != null) {
            throw new IllegalStateException("Duplicate living actor runtime ID");
        }
        entityCount.incrementAndGet();
        index(entity);
        return entity;
    }

    public Optional<WorldLivingEntity> find(long runtimeId) {
        if (runtimeId <= 0) return Optional.empty();
        return Optional.ofNullable(entities.get(runtimeId));
    }

    /**
     * Returns one consistent view of total population and actors in resident chunks.
     * Work is proportional to indexed resident chunks and their occupants, not every
     * actor in dormant chunks.
     */
    public synchronized ChunkSnapshot snapshotInChunks(
            java.util.Set<WorldChunkResidencyManager.ChunkCoordinate> chunks) {
        Objects.requireNonNull(chunks, "chunks");
        java.util.Set<Long> selected = new java.util.TreeSet<>();
        for (WorldChunkResidencyManager.ChunkCoordinate coordinate : chunks) {
            if (coordinate == null) continue;
            java.util.Set<Long> ids = entitiesByChunk.get(new ChunkKey(
                    coordinate.chunkX(), coordinate.chunkZ()));
            if (ids != null) selected.addAll(ids);
        }
        List<WorldLivingEntity> result = new ArrayList<>(selected.size());
        for (long id : selected) {
            WorldLivingEntity entity = entities.get(id);
            if (entity != null) result.add(entity);
        }
        return new ChunkSnapshot(entityCount.get(), result);
    }

    public record ChunkSnapshot(int totalEntities, List<WorldLivingEntity> residentEntities) {
        public ChunkSnapshot {
            if (totalEntities < 0) throw new IllegalArgumentException("totalEntities must be non-negative");
            residentEntities = List.copyOf(residentEntities);
            if (residentEntities.size() > totalEntities) {
                throw new IllegalArgumentException("Resident count cannot exceed total entity count");
            }
        }
    }

    public List<WorldLivingEntity> snapshot() {
        // The registry is keyed by runtime ID in a concurrent sorted map, so iteration
        // is already deterministic and does not need an O(n log n) sort every world tick.
        return List.copyOf(entities.values());
    }

    public synchronized Optional<DamageResult> damage(long runtimeId, float amount) {
        if (!Float.isFinite(amount) || amount <= 0) {
            throw new IllegalArgumentException("Damage must be finite and positive");
        }
        WorldLivingEntity before = entities.get(runtimeId);
        if (before == null) return Optional.empty();
        WorldLivingEntity after = before.damage(amount);
        float applied = before.health() - after.health();
        if (after.health() <= 0) {
            if (entities.remove(runtimeId, before)) {
                entityCount.decrementAndGet();
                unindex(runtimeId);
            }
        } else entities.replace(runtimeId, before, after);
        return Optional.of(new DamageResult(before, after, applied, after.health() <= 0));
    }

    public synchronized Optional<WorldLivingEntity> ignite(
            long runtimeId, int durationSeconds, long nowMillis
    ) {
        WorldLivingEntity entity = entities.get(runtimeId);
        if (entity == null) return Optional.empty();
        WorldLivingEntity updated = entity.ignite(durationSeconds, nowMillis);
        entities.replace(runtimeId, entity, updated);
        return Optional.of(updated);
    }

    public synchronized Optional<WorldLivingEntity> move(
            long runtimeId, double x, double y, double z
    ) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000
                || y < WorldChunk.MIN_Y || y > WorldChunk.MAX_Y) {
            throw new IllegalArgumentException("Living actor position is outside supported bounds");
        }
        WorldLivingEntity entity = entities.get(runtimeId);
        if (entity == null) return Optional.empty();
        WorldLivingEntity updated = entity.withPosition(x, y, z);
        entities.replace(runtimeId, entity, updated);
        reindex(updated);
        return Optional.of(updated);
    }

    public synchronized Optional<WorldLivingEntity> setVelocity(
            long runtimeId, double velocityX, double velocityY, double velocityZ
    ) {
        if (!Double.isFinite(velocityX) || !Double.isFinite(velocityY) || !Double.isFinite(velocityZ)
                || Math.abs(velocityX) > 100 || Math.abs(velocityY) > 100 || Math.abs(velocityZ) > 100) {
            throw new IllegalArgumentException("Living actor velocity is outside supported bounds");
        }
        WorldLivingEntity entity = entities.get(runtimeId);
        if (entity == null) return Optional.empty();
        WorldLivingEntity updated = entity.withVelocity(velocityX, velocityY, velocityZ);
        entities.replace(runtimeId, entity, updated);
        return Optional.of(updated);
    }

    /**
     * Atomically replaces position and velocity together. Physics normally changes both
     * in one tick; combining them avoids two immutable actor allocations and two map writes.
     */
    public synchronized Optional<WorldLivingEntity> updatePhysicsState(
            long runtimeId,
            double x, double y, double z,
            double velocityX, double velocityY, double velocityZ
    ) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000
                || y < WorldChunk.MIN_Y || y > WorldChunk.MAX_Y) {
            throw new IllegalArgumentException("Living actor position is outside supported bounds");
        }
        if (!Double.isFinite(velocityX) || !Double.isFinite(velocityY) || !Double.isFinite(velocityZ)
                || Math.abs(velocityX) > 100 || Math.abs(velocityY) > 100 || Math.abs(velocityZ) > 100) {
            throw new IllegalArgumentException("Living actor velocity is outside supported bounds");
        }
        WorldLivingEntity entity = entities.get(runtimeId);
        if (entity == null) return Optional.empty();
        WorldLivingEntity updated = entity.withPositionAndVelocity(
                x, y, z, velocityX, velocityY, velocityZ);
        entities.replace(runtimeId, entity, updated);
        reindex(updated);
        return Optional.of(updated);
    }

    public synchronized List<DamageResult> tickFire(long nowMillis) {
        if (nowMillis < 0) throw new IllegalArgumentException("nowMillis must be non-negative");
        List<DamageResult> damage = new ArrayList<>();
        for (WorldLivingEntity before : entities.values()) {
            if (before.fireUntilMillis() == 0) continue;
            if (nowMillis >= before.fireUntilMillis()) {
                WorldLivingEntity cleared = new WorldLivingEntity(
                        before.uniqueId(), before.runtimeId(), before.identifier(), before.family(),
                        before.x(), before.y(), before.z(),
                        before.velocityX(), before.velocityY(), before.velocityZ(),
                        before.health(), before.maxHealth(), 0, 0);
                entities.replace(before.runtimeId(), before, cleared);
            } else if (nowMillis >= before.nextFireDamageMillis()) {
                WorldLivingEntity after = new WorldLivingEntity(
                        before.uniqueId(), before.runtimeId(), before.identifier(), before.family(),
                        before.x(), before.y(), before.z(),
                        before.velocityX(), before.velocityY(), before.velocityZ(),
                        Math.max(0, before.health() - 1.0f), before.maxHealth(),
                        before.fireUntilMillis(), safeNextFireDamageAt(nowMillis));
                if (after.health() <= 0) {
                    if (entities.remove(before.runtimeId(), before)) {
                        entityCount.decrementAndGet();
                        unindex(before.runtimeId());
                    }
                } else entities.replace(before.runtimeId(), before, after);
                damage.add(new DamageResult(before, after, before.health() - after.health(),
                        after.health() <= 0));
            }
        }
        return List.copyOf(damage);
    }

    private static long safeNextFireDamageAt(long nowMillis) {
        return nowMillis > Long.MAX_VALUE - 1000L ? Long.MAX_VALUE : nowMillis + 1000L;
    }

    public synchronized boolean remove(long runtimeId) {
        boolean removed = entities.remove(runtimeId) != null;
        if (removed) {
            entityCount.decrementAndGet();
            unindex(runtimeId);
        }
        return removed;
    }

    private void index(WorldLivingEntity entity) {
        ChunkKey chunk = chunkFor(entity);
        entityChunks.put(entity.runtimeId(), chunk);
        entitiesByChunk.computeIfAbsent(chunk, ignored -> new java.util.HashSet<>())
                .add(entity.runtimeId());
    }

    private void reindex(WorldLivingEntity entity) {
        ChunkKey next = chunkFor(entity);
        ChunkKey previous = entityChunks.get(entity.runtimeId());
        if (next.equals(previous)) return;
        unindex(entity.runtimeId());
        entityChunks.put(entity.runtimeId(), next);
        entitiesByChunk.computeIfAbsent(next, ignored -> new java.util.HashSet<>())
                .add(entity.runtimeId());
    }

    private void unindex(long runtimeId) {
        ChunkKey previous = entityChunks.remove(runtimeId);
        if (previous == null) return;
        java.util.Set<Long> ids = entitiesByChunk.get(previous);
        if (ids == null) return;
        ids.remove(runtimeId);
        if (ids.isEmpty()) entitiesByChunk.remove(previous);
    }

    private static ChunkKey chunkFor(WorldLivingEntity entity) {
        return new ChunkKey((int) Math.floor(entity.x() / 16.0),
                (int) Math.floor(entity.z() / 16.0));
    }

    public int size() {
        return entityCount.get();
    }

    /** Flushes living actors to durable storage; a no-storage manager is a no-op. */
    public synchronized void save() throws IOException {
        if (storage != null) storage.save(snapshot());
    }

    public record DamageResult(
            WorldLivingEntity previous, WorldLivingEntity current,
            float appliedDamage, boolean killed
    ) {
    }
}
