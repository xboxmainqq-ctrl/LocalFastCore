package ru.nexora.combat;

import org.junit.jupiter.api.Test;
import ru.nexora.world.WorldLivingEntity;
import ru.nexora.world.WorldLivingEntityManager;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Synthetic microbenchmark comparing full player scans with the production spatial index. */
class PlayerTargetIndexBenchmarkTest {
    private static final int MOB_COUNT = 1_000;
    private static final int MEASURED_ROUNDS = 4;

    @Test
    void comparesLinearAndIndexedTargetLookupAtIncreasingPlayerCounts() {
        List<WorldLivingEntity> mobs = new ArrayList<>(MOB_COUNT);
        WorldLivingEntityManager manager = new WorldLivingEntityManager();
        for (int i = 0; i < MOB_COUNT; i++) {
            double x = (i % 50) * 96.0 - 2_400.0;
            double z = (i / 50) * 96.0 - 960.0;
            mobs.add(manager.spawn("minecraft:zombie", 20, x, 64, z));
        }

        for (int playerCount : new int[]{8, 64, 256, 1_024}) {
            List<LivingEntityAiService.PlayerTarget> players = new ArrayList<>(playerCount);
            for (int i = 0; i < playerCount; i++) {
                double x = (i % 32) * 256.0 - 3_968.0;
                double z = (i / 32) * 256.0 - 3_968.0;
                players.add(new LivingEntityAiService.PlayerTarget(
                        "bench-player-" + i, x, 64, z, true));
            }
            long buildStarted = System.nanoTime();
            PlayerTargetIndex index = new PlayerTargetIndex(players);
            long buildNanos = System.nanoTime() - buildStarted;

            // Warm both paths before collecting comparable whole-population samples.
            verifyEquivalent(mobs, players, index);
            long linearTotal = 0;
            long indexedTotal = 0;
            for (int round = 0; round < MEASURED_ROUNDS; round++) {
                long started = System.nanoTime();
                for (WorldLivingEntity mob : mobs) {
                    nearestWithin128(mob, players);
                }
                linearTotal += System.nanoTime() - started;

                started = System.nanoTime();
                for (WorldLivingEntity mob : mobs) {
                    index.nearestWithinRange(mob);
                }
                indexedTotal += System.nanoTime() - started;
            }

            System.out.printf(
                    "NEXORA target-index benchmark: mobs=%d players=%d linear_avg_us_per_mob=%.3f indexed_avg_us_per_mob=%.3f index_build_ms=%.3f%n",
                    MOB_COUNT, playerCount,
                    linearTotal / (double) (MEASURED_ROUNDS * MOB_COUNT) / 1_000.0,
                    indexedTotal / (double) (MEASURED_ROUNDS * MOB_COUNT) / 1_000.0,
                    buildNanos / 1_000_000.0);
        }
    }

    private static void verifyEquivalent(
            List<WorldLivingEntity> mobs,
            List<LivingEntityAiService.PlayerTarget> players,
            PlayerTargetIndex index
    ) {
        for (WorldLivingEntity mob : mobs) {
            assertEquals(nearestWithin128(mob, players), index.nearestWithinRange(mob));
        }
    }

    private static LivingEntityAiService.PlayerTarget nearestWithin128(
            WorldLivingEntity mob,
            List<LivingEntityAiService.PlayerTarget> players
    ) {
        LivingEntityAiService.PlayerTarget nearest =
                LivingEntityAiService.nearestAlivePlayer(mob, players);
        if (nearest == null) return null;
        double dx = nearest.x() - mob.x();
        double dy = nearest.y() - mob.y();
        double dz = nearest.z() - mob.z();
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        return distanceSquared <= PlayerTargetIndex.SEARCH_RADIUS_SQUARED ? nearest : null;
    }
}
