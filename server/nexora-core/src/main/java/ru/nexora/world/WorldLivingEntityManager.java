package ru.nexora.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe registry and lifecycle owner for server-created living actors. */
public final class WorldLivingEntityManager {
    private final AtomicLong nextRuntimeId = new AtomicLong(2_000_000L);
    private final ConcurrentMap<Long, WorldLivingEntity> entities = new ConcurrentHashMap<>();

    public WorldLivingEntity spawn(
            String identifier, float maxHealth, double x, double y, double z
    ) {
        return spawn(identifier, WorldLivingEntity.familyForIdentifier(identifier),
                maxHealth, x, y, z);
    }

    public WorldLivingEntity spawn(
            String identifier, WorldLivingEntity.Family family,
            float maxHealth, double x, double y, double z
    ) {
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
        return entity;
    }

    public Optional<WorldLivingEntity> find(long runtimeId) {
        if (runtimeId <= 0) return Optional.empty();
        return Optional.ofNullable(entities.get(runtimeId));
    }

    public List<WorldLivingEntity> snapshot() {
        List<WorldLivingEntity> snapshot = new ArrayList<>(entities.values());
        snapshot.sort(Comparator.comparingLong(WorldLivingEntity::runtimeId));
        return List.copyOf(snapshot);
    }

    public synchronized Optional<DamageResult> damage(long runtimeId, float amount) {
        if (!Float.isFinite(amount) || amount <= 0) {
            throw new IllegalArgumentException("Damage must be finite and positive");
        }
        WorldLivingEntity before = entities.get(runtimeId);
        if (before == null) return Optional.empty();
        WorldLivingEntity after = before.damage(amount);
        float applied = before.health() - after.health();
        if (after.health() <= 0) entities.remove(runtimeId, before);
        else entities.replace(runtimeId, before, after);
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
                        before.fireUntilMillis(), nowMillis + 1000L);
                if (after.health() <= 0) entities.remove(before.runtimeId(), before);
                else entities.replace(before.runtimeId(), before, after);
                damage.add(new DamageResult(before, after, before.health() - after.health(),
                        after.health() <= 0));
            }
        }
        return List.copyOf(damage);
    }

    public synchronized boolean remove(long runtimeId) {
        return entities.remove(runtimeId) != null;
    }

    public int size() {
        return entities.size();
    }

    public record DamageResult(
            WorldLivingEntity previous, WorldLivingEntity current,
            float appliedDamage, boolean killed
    ) {
    }
}
