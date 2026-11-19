package ru.nexora.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes bounded chunk residency around active players.
 *
 * <p>This is intentionally separate from the network layer: networking reports player
 * positions, while the world runtime decides which chunks should remain resident.
 * It does not unload chunks by itself; a future persistence-aware chunk store can
 * consume the returned unload set after flushing dirty state.</p>
 */
public final class WorldChunkResidencyManager {
    private final int viewDistance;
    private final int unloadGraceTicks;
    private final Shape shape;
    private final Set<Long> desiredChunks = new HashSet<>();
    private final Set<Long> loadScratch = new HashSet<>();
    private final Set<Long> unloadScratch = new HashSet<>();
    private final Map<Long, Long> lastDesiredTick = new HashMap<>();
    private final Set<Long> resident = new HashSet<>();
    private volatile Set<Long> residentSnapshot = Set.of();

    public WorldChunkResidencyManager(int viewDistance, int unloadGraceTicks) {
        this(viewDistance, unloadGraceTicks, Shape.CIRCULAR);
    }

    public WorldChunkResidencyManager(int viewDistance, int unloadGraceTicks, Shape shape) {
        if (viewDistance < 1 || viewDistance > 32) {
            throw new IllegalArgumentException("viewDistance must be between 1 and 32");
        }
        if (unloadGraceTicks < 0 || unloadGraceTicks > 20 * 60) {
            throw new IllegalArgumentException("unloadGraceTicks must be between 0 and 1200");
        }
        this.viewDistance = viewDistance;
        this.unloadGraceTicks = unloadGraceTicks;
        this.shape = java.util.Objects.requireNonNull(shape, "shape");
    }

    public synchronized ResidencyPlan update(List<PlayerChunk> players, long tick) {
        return update(players, java.util.Set.of(), tick);
    }

    /** Pinned chunks contain active world-owned entities and cannot be evicted yet. */
    public synchronized ResidencyPlan update(
            List<PlayerChunk> players,
            java.util.Set<ChunkCoordinate> pinnedChunks,
            long tick
    ) {
        if (tick < 0) throw new IllegalArgumentException("tick must be non-negative");
        java.util.Objects.requireNonNull(players, "players");
        java.util.Objects.requireNonNull(pinnedChunks, "pinnedChunks");
        desiredChunks.clear();

        for (PlayerChunk player : players) {
            if (player == null) continue;
            int playerRadius = player.viewDistance() < 0 ? viewDistance : player.viewDistance();
            if (playerRadius < 1 || playerRadius > 32) {
                throw new IllegalArgumentException("Player view distance must be between 1 and 32");
            }
            int minX = player.chunkX() - playerRadius;
            int maxX = player.chunkX() + playerRadius;
            int minZ = player.chunkZ() - playerRadius;
            int maxZ = player.chunkZ() + playerRadius;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    // Circular residency avoids loading the four extreme corners
                    // of a square view distance.
                    long dx = (long) x - player.chunkX();
                    long dz = (long) z - player.chunkZ();
                    if (shape == Shape.CIRCULAR
                            && dx * dx + dz * dz > (long) playerRadius * playerRadius) continue;
                    long key = chunkKey(x, z);
                    desiredChunks.add(key);
                    lastDesiredTick.put(key, tick);
                }
            }
        }

        for (ChunkCoordinate pinned : pinnedChunks) {
            if (pinned == null) continue;
            long key = chunkKey(pinned.chunkX(), pinned.chunkZ());
            desiredChunks.add(key);
            lastDesiredTick.put(key, tick);
        }

        // Reuse per-manager scratch sets: this method runs on the world hot path
        // and allocating two new HashSets on every tick creates avoidable GC work.
        loadScratch.clear();
        for (long key : desiredChunks) {
            if (!resident.contains(key)) loadScratch.add(key);
        }
        resident.addAll(loadScratch);

        unloadScratch.clear();
        for (long key : resident) {
            if (desiredChunks.contains(key)) continue;
            long last = lastDesiredTick.getOrDefault(key, tick);
            if (tick - last >= unloadGraceTicks) {
                unloadScratch.add(key);
            }
        }
        resident.removeAll(unloadScratch);
        for (long key : unloadScratch) lastDesiredTick.remove(key);
        if (!loadScratch.isEmpty() || !unloadScratch.isEmpty()) {
            residentSnapshot = Set.copyOf(resident);
        }
        return new ResidencyPlan(decode(loadScratch), decode(unloadScratch),
                resident.size(), desiredChunks.size());
    }

    /** Lock-free read against the last fully published residency snapshot. */
    public boolean isResident(int chunkX, int chunkZ) {
        return residentSnapshot.contains(chunkKey(chunkX, chunkZ));
    }

    /** Lock-free read used by entity ticking for every active entity. */
    public boolean isResidentAtBlockPosition(double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z)) return false;
        int chunkX = (int) Math.floor(x / 16.0);
        int chunkZ = (int) Math.floor(z / 16.0);
        return residentSnapshot.contains(chunkKey(chunkX, chunkZ));
    }

    public int residentChunkCount() {
        return residentSnapshot.size();
    }

    public int viewDistance() {
        return viewDistance;
    }

    private static List<ChunkCoordinate> decode(Set<Long> keys) {
        List<ChunkCoordinate> result = new ArrayList<>(keys.size());
        for (long key : keys) {
            result.add(new ChunkCoordinate((int) (key >> 32), (int) key));
        }
        return result;
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffff_ffffL);
    }

    public record PlayerChunk(int chunkX, int chunkZ, int viewDistance) {
        public PlayerChunk(int chunkX, int chunkZ) {
            this(chunkX, chunkZ, -1);
        }

        public PlayerChunk {
            if (Math.abs((long) chunkX) > 1_875_000L
                    || Math.abs((long) chunkZ) > 1_875_000L) {
                throw new IllegalArgumentException("Player chunk is outside supported world bounds");
            }
            if (viewDistance != -1 && (viewDistance < 1 || viewDistance > 32)) {
                throw new IllegalArgumentException("Player view distance must be -1 or between 1 and 32");
            }
        }
    }

    public enum Shape {
        CIRCULAR,
        SQUARE
    }

    public record ChunkCoordinate(int chunkX, int chunkZ) {}

    public record ResidencyPlan(
            List<ChunkCoordinate> load,
            List<ChunkCoordinate> unload,
            int residentCount,
            int desiredCount
    ) {
        public ResidencyPlan {
            load = List.copyOf(load);
            unload = List.copyOf(unload);
            if (residentCount < 0 || desiredCount < 0) {
                throw new IllegalArgumentException("Chunk counts must be non-negative");
            }
        }
    }
}
