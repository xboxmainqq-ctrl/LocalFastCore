package ru.nexora.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe in-memory registry of entities belonging to one world.
 *
 * <p>Entity values are immutable. Readers receive detached immutable snapshots;
 * updates replace a single value atomically, so packet and game threads never
 * observe partially updated coordinates.</p>
 */
public final class EntityRegistry {
    private final ConcurrentMap<UUID, WorldEntity> entities = new ConcurrentHashMap<>();

    /** Adds an entity. Duplicate UUIDs are rejected instead of overwriting state. */
    public void spawn(WorldEntity entity) {
        Objects.requireNonNull(entity, "entity");
        if (entities.putIfAbsent(entity.id(), entity) != null) {
            throw new IllegalStateException("An entity with this UUID already exists: " + entity.id());
        }
    }

    /** Replaces position and yaw atomically; returns false if the entity is absent. */
    public boolean updatePosition(UUID id, double x, double y, double z, double yaw) {
        Objects.requireNonNull(id, "id");
        if (!finiteCoordinate(x) || !finiteCoordinate(y) || !finiteCoordinate(z)
                || !Double.isFinite(yaw) || yaw < -180.0 || yaw > 180.0) {
            throw new IllegalArgumentException("Invalid entity transform");
        }
        return entities.computeIfPresent(id, (ignored, current) -> current.at(x, y, z, yaw)) != null;
    }

    /** Removes an entity and returns its last state, if it existed. */
    public Optional<WorldEntity> remove(UUID id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(entities.remove(id));
    }

    public Optional<WorldEntity> find(UUID id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(entities.get(id));
    }

    /** Returns a stable, detached snapshot ordered by UUID. */
    public List<WorldEntity> snapshot() {
        List<WorldEntity> result = new ArrayList<>(entities.values());
        result.sort(Comparator.comparing(entity -> entity.id().toString()));
        return List.copyOf(result);
    }

    /** Returns entities whose position is within the inclusive radius of a point. */
    public List<WorldEntity> within(double x, double y, double z, double radius) {
        if (!finiteCoordinate(x) || !finiteCoordinate(y) || !finiteCoordinate(z)
                || !Double.isFinite(radius) || radius < 0.0 || radius > 30_000_000d) {
            throw new IllegalArgumentException("Invalid entity search radius or center");
        }
        double radiusSquared = radius * radius;
        List<WorldEntity> result = new ArrayList<>();
        for (WorldEntity entity : entities.values()) {
            double dx = entity.x() - x;
            double dy = entity.y() - y;
            double dz = entity.z() - z;
            if (dx * dx + dy * dy + dz * dz <= radiusSquared) {
                result.add(entity);
            }
        }
        result.sort(Comparator.comparingDouble(entity -> distanceSquared(entity, x, y, z))
                .thenComparing(entity -> entity.id().toString()));
        return List.copyOf(result);
    }

    public int size() {
        return entities.size();
    }

    public boolean isEmpty() {
        return entities.isEmpty();
    }

    private static double distanceSquared(WorldEntity entity, double x, double y, double z) {
        double dx = entity.x() - x;
        double dy = entity.y() - y;
        double dz = entity.z() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean finiteCoordinate(double value) {
        return Double.isFinite(value) && Math.abs(value) <= 30_000_000d;
    }
}
