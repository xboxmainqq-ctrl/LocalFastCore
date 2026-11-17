package ru.nexora.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Repeatable workload probe for cache behavior at representative mob counts.
 * Wall-clock timings are reported for comparison only; CI does not enforce
 * machine-dependent performance thresholds.
 */
class MobPathCacheLoadBenchmarkTest {
    private static final int REPEATS = 10;

    @Test
    void reportsPathfindingWorkloadsForFiftyOneHundredAndTwoHundredMobs() {
        for (int mobCount : new int[]{50, 100, 200}) {
            World world = new World("mob-path-load-" + mobCount, 0L, new FlatWorldGenerator());
            world.getChunk(0, 0);
            MobPathCache cache = new MobPathCache(1_024, 150);
            double[][] starts = new double[mobCount][2];

            for (int i = 0; i < mobCount; i++) {
                starts[i][0] = (i % 20) + 0.5;
                starts[i][1] = (i / 20) + 0.5;
            }

            long directStart = System.nanoTime();
            for (int repeat = 0; repeat < REPEATS; repeat++) {
                for (int i = 0; i < mobCount; i++) {
                    double x = starts[i][0];
                    double z = starts[i][1];
                    assertTrue(WorldPathfinder.findNextStep(world, x, 65, z, x + 3.5, z).isPresent());
                }
            }
            long directNanos = System.nanoTime() - directStart;

            long cachedStart = System.nanoTime();
            for (int repeat = 0; repeat < REPEATS; repeat++) {
                long now = 1_000L + repeat * 10L;
                for (int i = 0; i < mobCount; i++) {
                    double x = starts[i][0];
                    double z = starts[i][1];
                    assertTrue(cache.findNextStep(i + 1L, world, x, 65, z,
                            x + 3.5, z, now).isPresent());
                }
            }
            long cachedNanos = System.nanoTime() - cachedStart;

            MobPathCache.Stats stats = cache.stats();
            assertEquals((long) mobCount * (REPEATS - 1), stats.hits());
            assertEquals(mobCount, stats.recomputations());
            assertEquals((long) mobCount * REPEATS, stats.requests());
            assertEquals(1.0 - 1.0 / REPEATS, stats.hitRate(), 0.000_001);
            assertEquals(mobCount, stats.entries());

            System.out.printf(
                    "Nerox mob-path workload: mobs=%d repeats=%d direct_ms=%.3f cached_ms=%.3f hits=%d recomputations=%d hit_rate=%.1f%%%n",
                    mobCount, REPEATS, directNanos / 1_000_000.0,
                    cachedNanos / 1_000_000.0, stats.hits(), stats.recomputations(),
                    stats.hitRate() * 100.0
            );
        }
    }
}
