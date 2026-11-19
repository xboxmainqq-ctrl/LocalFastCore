package ru.nexora.combat;

import org.junit.jupiter.api.Test;
import ru.nexora.world.WorldLivingEntity;
import ru.nexora.world.WorldLivingEntityManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Repeatable synthetic mob-AI load probe. It exercises real target selection,
 * species decisions and distance-aware scheduling, but not a live Bedrock server,
 * networking, physics or authenticated player sessions. Timings are diagnostic only.
 */
class LivingEntityAiLoadBenchmarkTest {
    private static final int TICKS = 20;
    private static final List<String> SPECIES = List.of(
            "minecraft:zombie", "minecraft:skeleton", "minecraft:spider",
            "minecraft:creeper", "minecraft:witch", "minecraft:phantom",
            "minecraft:cow", "minecraft:rabbit"
    );

    @Test
    void reportsAiLoadAtIncreasingMobPopulations() {
        for (int mobCount : new int[]{100, 500, 1_000, 2_000, 5_000}) {
            WorldLivingEntityManager entities = new WorldLivingEntityManager();
            List<WorldLivingEntity> mobs = new ArrayList<>(mobCount);
            for (int i = 0; i < mobCount; i++) {
                String species = SPECIES.get(i % SPECIES.size());
                double x = (i % 80) - 40.0;
                double z = (i / 80) - (mobCount / 160.0);
                mobs.add(entities.spawn(species, 20, x, 64, z));
            }

            List<LivingEntityAiService.PlayerTarget> players = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                double angle = Math.PI * 2.0 * i / 8.0;
                players.add(new LivingEntityAiService.PlayerTarget(
                        "load-player-" + i, Math.cos(angle) * 12.0, 64,
                        Math.sin(angle) * 12.0, true));
            }

            LivingEntityAiService ai = new LivingEntityAiService();
            MobAiLoadController load = new MobAiLoadController();
            long[] tickNanos = new long[TICKS];
            long executedDecisions = 0;

            for (int tick = 0; tick < TICKS; tick++) {
                long started = System.nanoTime();
                long nowMillis = tick * 50L;
                for (int i = 0; i < mobs.size(); i++) {
                    WorldLivingEntity mob = mobs.get(i);
                    double nearestDistanceSquared = nearestDistanceSquared(mob, players);
                    boolean activeTarget = i % 5 == 0;
                    if (!load.shouldUpdate(mob.runtimeId(), tick,
                            nearestDistanceSquared, activeTarget)) {
                        continue;
                    }
                    ai.decide(mob, players, nowMillis, 0);
                    executedDecisions++;
                }
                tickNanos[tick] = Math.max(0L, System.nanoTime() - started);
            }

            java.util.Arrays.sort(tickNanos);
            LivingEntityAiService.Stats aiStats = ai.stats();
            MobAiLoadController.Stats loadStats = load.stats();
            assertEquals(executedDecisions, aiStats.decisionCount());
            assertEquals((long) mobCount * TICKS, loadStats.totalDecisions());
            assertTrue(executedDecisions > 0);
            assertTrue(aiStats.totalDecisionNanos() >= aiStats.maxDecisionNanos());
            assertTrue(aiStats.maxDecisionNanos() > 0);

            long p50 = percentile(tickNanos, 0.50);
            long p95 = percentile(tickNanos, 0.95);
            long max = tickNanos[tickNanos.length - 1];
            System.out.printf(
                    "NEXORA mob-AI workload: mobs=%d ticks=%d decisions=%d tick_p50_ms=%.3f tick_p95_ms=%.3f tick_max_ms=%.3f ai_avg_us=%.3f ai_max_us=%.3f deferred=%d%n",
                    mobCount, TICKS, executedDecisions, p50 / 1_000_000.0,
                    p95 / 1_000_000.0, max / 1_000_000.0,
                    aiStats.averageDecisionNanos() / 1_000.0,
                    aiStats.maxDecisionNanos() / 1_000.0, loadStats.deferredUpdates()
            );
        }
    }

    private static double nearestDistanceSquared(
            WorldLivingEntity mob, List<LivingEntityAiService.PlayerTarget> players) {
        double nearest = Double.POSITIVE_INFINITY;
        for (LivingEntityAiService.PlayerTarget player : players) {
            if (!player.alive()) continue;
            double dx = mob.x() - player.x();
            double dy = mob.y() - player.y();
            double dz = mob.z() - player.z();
            nearest = Math.min(nearest, dx * dx + dy * dy + dz * dz);
        }
        return nearest;
    }

    private static long percentile(long[] sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }
}
