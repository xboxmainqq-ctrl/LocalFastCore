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
    private final Map<Long, Integer> desiredRefCounts = new HashMap<>();
    private final Map<Long, Long> lastDesiredTick = new HashMap<>();
    private final Set<Long> resident = new HashSet<>();

    public WorldChunkResidencyManager(int viewDistance, int unloadGraceTicks) {
        if (viewDistance < 2 || viewDistance > 32) {
            throw new IllegalArgumentException("viewDistance must be between 2 and 32");
        }
        if (unloadGraceTicks < 0 || unloadGraceTicks > 20 * 60) {
            throw new IllegalArgumentException("unloadGraceTicks must be between 0 and 1200");
        }
        this.viewDistance = viewDistance;
        this.unloadGraceTicks = unloadGraceTicks;
    }

    public synchronized ResidencyPlan update(List<PlayerChunk> players, long tick) {
        if (tick < 0) throw new IllegalArgumentException("tick must be non-negative");
        desiredRefCounts.clear();

        for (PlayerChunk player : players) {
            if (player == null) continue;
            int minX = player.chunkX() - viewDistance;
            int maxX = player.chunkX() + viewDistance;
            int minZ = player.chunkZ() - viewDistance;
            int maxZ = player.chunkZ() + viewDistance;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    // Circular residency avoids loading the four extreme corners
                    // of a square view distance.
                    long dx = (long) x - player.chunkX();
                    long dz = (long) z - player.chunkZ();
                    if (dx * dx + dz * dz > (long) viewDistance * viewDistance) continue;
                    long key = chunkKey(x, z);
                    desiredRefCounts.merge(key, 1, Integer::sum);
                    lastDesiredTick.put(key, tick);
                }
            }
        }

        Set<Long> load = new HashSet<>(desiredRefCounts.keySet());
        load.removeAll(resident);
        resident.addAll(load);

        Set<Long> unload = new HashSet<>();
        for (long key : new ArrayList<>(resident)) {
            if (desiredRefCounts.containsKey(key)) continue;
            long last = lastDesiredTick.getOrDefault(key, tick);
            if (tick - last >= unloadGraceTicks) {
                unload.add(key);
            }
        }
        resident.removeAll(unload);
        return new ResidencyPlan(List.copyOf(decode(load)), List.copyOf(decode(unload)),
                resident.size(), desiredRefCounts.size());
    }

    public synchronized int residentChunkCount() {
        return resident.size();
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

    public record PlayerChunk(int chunkX, int chunkZ) {}

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
