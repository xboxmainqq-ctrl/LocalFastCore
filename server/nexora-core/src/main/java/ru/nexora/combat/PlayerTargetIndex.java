package ru.nexora.combat;

import ru.nexora.world.WorldLivingEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Per-world-tick spatial index for alive players.
 *
 * <p>The index uses 128-block cells. A nearest-player query checks only the mob's
 * cell and its eight neighbors, then performs an exact 3D distance comparison.
 * This returns the exact nearest player whenever one is within 128 blocks. If no
 * player is in that radius, {@code null} is sufficient for mob acquisition and
 * the 128-block despawn threshold.</p>
 *
 * <p>Build once per tick and share across all mob decisions. It is immutable after
 * construction and requires no synchronization during reads.</p>
 */
public final class PlayerTargetIndex {
    public static final double SEARCH_RADIUS = 128.0;
    public static final double SEARCH_RADIUS_SQUARED = SEARCH_RADIUS * SEARCH_RADIUS;
    private static final int CELL_SIZE = 128;

    private final Map<Long, List<LivingEntityAiService.PlayerTarget>> cells;
    private final Map<String, LivingEntityAiService.PlayerTarget> byNetworkId;

    public PlayerTargetIndex(List<LivingEntityAiService.PlayerTarget> players) {
        Objects.requireNonNull(players, "players");
        Map<Long, List<LivingEntityAiService.PlayerTarget>> mutableCells = new HashMap<>();
        Map<String, LivingEntityAiService.PlayerTarget> mutableIds = new HashMap<>();
        for (LivingEntityAiService.PlayerTarget player : players) {
            if (player == null || !player.alive()) continue;
            int cellX = cell(player.x());
            int cellZ = cell(player.z());
            mutableCells.computeIfAbsent(key(cellX, cellZ), ignored -> new ArrayList<>()).add(player);
            mutableIds.put(player.networkId(), player);
        }
        // These maps and their bucket lists are privately owned and never mutated
        // after construction; avoid per-tick defensive copies of every bucket.
        this.cells = mutableCells;
        this.byNetworkId = mutableIds;
    }

    /**
     * Finds the exact nearest alive player inside 128 blocks, or null if none exists.
     * The bounded result is enough for hostile acquisition and distant-despawn checks.
     */
    public LivingEntityAiService.PlayerTarget nearestWithinRange(WorldLivingEntity mob) {
        Objects.requireNonNull(mob, "mob");
        int centerX = cell(mob.x());
        int centerZ = cell(mob.z());
        LivingEntityAiService.PlayerTarget nearest = null;
        double nearestDistanceSquared = SEARCH_RADIUS_SQUARED;
        for (int cellX = centerX - 1; cellX <= centerX + 1; cellX++) {
            for (int cellZ = centerZ - 1; cellZ <= centerZ + 1; cellZ++) {
                List<LivingEntityAiService.PlayerTarget> candidates = cells.get(key(cellX, cellZ));
                if (candidates == null) continue;
                for (LivingEntityAiService.PlayerTarget candidate : candidates) {
                    double dx = candidate.x() - mob.x();
                    double dy = candidate.y() - mob.y();
                    double dz = candidate.z() - mob.z();
                    double distanceSquared = dx * dx + dy * dy + dz * dz;
                    if ((nearest == null && distanceSquared <= SEARCH_RADIUS_SQUARED)
                            || distanceSquared < nearestDistanceSquared) {
                        nearestDistanceSquared = distanceSquared;
                        nearest = candidate;
                    }
                }
            }
        }
        return nearest;
    }

    /** O(1) lookup for a mob's remembered attacker/provoker. */
    public LivingEntityAiService.PlayerTarget find(String networkId) {
        if (networkId == null || networkId.isBlank()) return null;
        return byNetworkId.get(networkId);
    }

    public int playerCount() {
        return byNetworkId.size();
    }

    private static int cell(double coordinate) {
        return (int) Math.floor(coordinate / CELL_SIZE);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffff_ffffL);
    }
}
