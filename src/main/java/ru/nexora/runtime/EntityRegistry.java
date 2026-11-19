package ru.nexora.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Thread-safe in-memory registry of entities belonging to one world.
 *
 * <p>Entities are indexed by 16-block spatial cells. All mutations and queries
 * use the same monitor so the entity map and spatial index remain consistent.</p>
 */
public final class EntityRegistry {
    private static final int CELL_SIZE = 16;
    private static final long MAX_QUERY_CELLS = 8_192L;

    private final Map<UUID, WorldEntity> entities = new HashMap<>();
    private final Map<CellKey, Set<UUID>> spatialIndex = new HashMap<>();

    /** Adds an entity. Duplicate UUIDs are rejected instead of overwriting state. */
    public synchronized void spawn(WorldEntity entity) {
        Objects.requireNonNull(entity, "entity");
        if (entities.containsKey(entity.id())) {
            throw new IllegalStateException("An entity with this UUID already exists: " + entity.id());
        }
        entities.put(entity.id(), entity);
        addToCell(entity);
    }

    /** Replaces position and yaw atomically; returns false if the entity is absent. */
    public synchronized boolean updatePosition(UUID id, double x, double y, double z, double yaw) {
        Objects.requireNonNull(id, "id");
        if (!finiteCoordinate(x) || !finiteCoordinate(y) || !finiteCoordinate(z)
                || !Double.isFinite(yaw) || yaw < -180.0 || yaw > 180.0) {
            throw new IllegalArgumentException("Invalid entity transform");
        }
        WorldEntity current = entities.get(id);
        if (current == null) {
            return false;
        }
        WorldEntity updated = current.at(x, y, z, yaw);
        CellKey oldCell = cellOf(current);
        CellKey newCell = cellOf(updated);
        if (!oldCell.equals(newCell)) {
            removeFromCell(current);
            addToCell(updated);
        }
        entities.put(id, updated);
        return true;
    }

    /** Removes an entity and returns its last state, if it existed. */
    public synchronized Optional<WorldEntity> remove(UUID id) {
        Objects.requireNonNull(id, "id");
        WorldEntity removed = entities.remove(id);
        if (removed != null) {
            removeFromCell(removed);
        }
        return Optional.ofNullable(removed);
    }

    public synchronized Optional<WorldEntity> find(UUID id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(entities.get(id));
    }

    /** Returns a stable, detached snapshot ordered by UUID. */
    public synchronized List<WorldEntity> snapshot() {
        List<WorldEntity> result = new ArrayList<>(entities.values());
        result.sort(Comparator.comparing(entity -> entity.id().toString()));
        return List.copyOf(result);
    }

    /**
     * Returns a detached entity snapshot for the single-threaded simulation loop.
     * Unlike {@link #snapshot()}, this method does not sort by UUID: simulation
     * order is intentionally unspecified, avoiding an O(n log n) sort every tick.
     */
    public synchronized List<WorldEntity> simulationSnapshot() {
        return List.copyOf(entities.values());
    }

    /**
     * Returns entities within an inclusive 3D radius, nearest first.
     * Small searches inspect only intersecting cells; very large searches fall
     * back to a full scan to avoid iterating millions of empty cells.
     */
    public synchronized List<WorldEntity> within(double x, double y, double z, double radius) {
        if (!finiteCoordinate(x) || !finiteCoordinate(y) || !finiteCoordinate(z)
                || !Double.isFinite(radius) || radius < 0.0 || radius > 30_000_000d) {
            throw new IllegalArgumentException("Invalid entity search radius or center");
        }

        List<WorldEntity> candidates = collectCandidates(x, y, z, radius);
        double radiusSquared = radius * radius;
        List<WorldEntity> result = new ArrayList<>();
        for (WorldEntity entity : candidates) {
            if (distanceSquared(entity, x, y, z) <= radiusSquared) {
                result.add(entity);
            }
        }
        result.sort(Comparator.comparingDouble((WorldEntity entity) -> distanceSquared(entity, x, y, z))                .thenComparing(entity -> entity.id().toString()));
        return List.copyOf(result);
    }

    public synchronized int size() {
        return entities.size();
    }

    public synchronized boolean isEmpty() {
        return entities.isEmpty();
    }

    private List<WorldEntity> collectCandidates(double x, double y, double z, double radius) {
        long minX = cellCoordinate(x - radius);
        long minY = cellCoordinate(y - radius);
        long minZ = cellCoordinate(z - radius);
        long maxX = cellCoordinate(x + radius);
        long maxY = cellCoordinate(y + radius);
        long maxZ = cellCoordinate(z + radius);
        long cellsX = maxX - minX + 1;
        long cellsY = maxY - minY + 1;
        long cellsZ = maxZ - minZ + 1;

        if (cellsX > MAX_QUERY_CELLS || cellsY > MAX_QUERY_CELLS || cellsZ > MAX_QUERY_CELLS
                || cellsX * cellsY > MAX_QUERY_CELLS
                || cellsX * cellsY * cellsZ > MAX_QUERY_CELLS) {
            return new ArrayList<>(entities.values());
        }

        List<WorldEntity> result = new ArrayList<>();
        for (long cellX = minX; cellX <= maxX; cellX++) {
            for (long cellY = minY; cellY <= maxY; cellY++) {
                for (long cellZ = minZ; cellZ <= maxZ; cellZ++) {
                    Set<UUID> ids = spatialIndex.get(new CellKey(cellX, cellY, cellZ));
                    if (ids != null) {
                        for (UUID id : ids) {
                            WorldEntity entity = entities.get(id);
                            if (entity != null) {
                                result.add(entity);
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    private void addToCell(WorldEntity entity) {
        spatialIndex.computeIfAbsent(cellOf(entity), ignored -> new HashSet<>()).add(entity.id());
    }

    private void removeFromCell(WorldEntity entity) {
        CellKey key = cellOf(entity);
        Set<UUID> ids = spatialIndex.get(key);
        if (ids == null) {
            return;
        }
        ids.remove(entity.id());
        if (ids.isEmpty()) {
            spatialIndex.remove(key);
        }
    }

    private static CellKey cellOf(WorldEntity entity) {
        return new CellKey(cellCoordinate(entity.x()), cellCoordinate(entity.y()),
                cellCoordinate(entity.z()));
    }

    private static long cellCoordinate(double coordinate) {
        return (long) Math.floor(coordinate / CELL_SIZE);
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

    private record CellKey(long x, long y, long z) {
    }
}