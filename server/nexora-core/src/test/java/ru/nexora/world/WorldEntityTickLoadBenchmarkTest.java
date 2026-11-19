package ru.nexora.world;

import org.junit.jupiter.api.Test;
import ru.nexora.ai.HotPathProfiler;
import ru.nexora.ai.NexoraAiDiagnostics;
import ru.nexora.combat.LivingEntityAiService;
import ru.nexora.combat.MobAiLoadController;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the world entity registry, chunk residency filter and complete world AI
 * tick pipeline together. It remains a synthetic in-process test, not a live server soak.
 */
class WorldEntityTickLoadBenchmarkTest {
    private static final int TICKS = 20;
    private static final List<String> SPECIES = List.of(
            "minecraft:zombie", "minecraft:skeleton", "minecraft:spider",
            "minecraft:creeper", "minecraft:witch", "minecraft:phantom",
            "minecraft:cow", "minecraft:rabbit"
    );

    @Test
    void measuresWorldEntityCoordinatorAtIncreasingMobPopulations() {
        for (int mobCount : new int[]{100, 500, 1_000, 2_000, 5_000}) {
            WorldLivingEntityManager entities = new WorldLivingEntityManager();
            for (int i = 0; i < mobCount; i++) {
                String species = SPECIES.get(i % SPECIES.size());
                double x = (i % 100) - 50.0;
                double z = (i / 100) - 25.0;
                entities.spawn(species, 20, x, 64, z);
            }

            WorldChunkResidencyManager residency = new WorldChunkResidencyManager(8, 20);
            WorldTickPipeline pipeline = new WorldTickPipeline(
                    new MobAiLoadController(), new LivingEntityAiService());
            WorldEntityTickCoordinator coordinator = new WorldEntityTickCoordinator(
                    residency, entities, pipeline, new NexoraAiDiagnostics(), 50_000_000L);

            List<LivingEntityAiService.PlayerTarget> players = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                double angle = Math.PI * 2.0 * i / 8.0;
                players.add(new LivingEntityAiService.PlayerTarget(
                        "world-load-player-" + i, Math.cos(angle) * 12.0, 64,
                        Math.sin(angle) * 12.0, true));
            }
            List<WorldChunkResidencyManager.PlayerChunk> playerChunks = List.of(
                    new WorldChunkResidencyManager.PlayerChunk(0, 0, 8));
            long[] tickNanos = new long[TICKS];
            long totalDecisions = 0;
            long totalDeferred = 0;

            for (int tick = 0; tick < TICKS; tick++) {
                long started = System.nanoTime();
                WorldEntityTickCoordinator.TickResult result = coordinator.tick(
                        tick, tick * 50L, playerChunks, players, ignored -> { });
                tickNanos[tick] = Math.max(0L, System.nanoTime() - started);
                assertEquals(mobCount, result.totalEntities());
                assertEquals(mobCount, result.residentEntities());
                assertEquals(0, result.dormantEntities());
                assertEquals(result.aiResult().decisionsProduced(),
                        result.aiResult().scheduledMobs());
                totalDecisions += result.aiResult().decisionsProduced();
                totalDeferred += result.aiResult().deferredMobs();
            }

            java.util.Arrays.sort(tickNanos);
            HotPathProfiler profiler = pipeline.profiler();
            HotPathProfiler.Snapshot targeting =
                    profiler.snapshot(HotPathProfiler.Path.MOB_TARGETING);
            HotPathProfiler.Snapshot decisions =
                    profiler.snapshot(HotPathProfiler.Path.MOB_AI_DECISION);
            long p50 = percentile(tickNanos, 0.50);
            long p95 = percentile(tickNanos, 0.95);
            long max = tickNanos[tickNanos.length - 1];

            assertTrue(totalDecisions > 0);
            assertEquals(TICKS * mobCount, targeting.sampleCount() + totalDeferred
                    - pipeline.runtime().stats().deferredTasks());
            assertTrue(decisions.sampleCount() > 0);
            System.out.printf(
                    "NEXORA world-tick workload: mobs=%d ticks=%d decisions=%d deferred=%d coordinator_p50_ms=%.3f coordinator_p95_ms=%.3f coordinator_max_ms=%.3f targeting_p95_us=%.3f decision_p95_us=%.3f resident_chunks=%d%n",
                    mobCount, TICKS, totalDecisions, totalDeferred,
                    p50 / 1_000_000.0, p95 / 1_000_000.0, max / 1_000_000.0,
                    targeting.p95Nanos() / 1_000.0, decisions.p95Nanos() / 1_000.0,
                    residency.residentChunkCount()
            );
        }
    }

    private static long percentile(long[] sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }
}
