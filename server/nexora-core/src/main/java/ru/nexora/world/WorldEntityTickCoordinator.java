package ru.nexora.world;

import ru.nexora.ai.NexoraAiDiagnostics;
import ru.nexora.combat.LivingEntityAiService;
import ru.nexora.combat.MobAiLoadController;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Connects player-driven chunk residency to entity ticking and AI diagnostics.
 *
 * <p>Only entities in resident chunks are offered to the world tick pipeline. Chunks
 * inside their unload grace period remain resident; after the residency manager
 * expires them, their entities stop receiving generic AI ticks until the chunk
 * becomes resident again.</p>
 */
public final class WorldEntityTickCoordinator {
    private final World world;
    private final WorldChunkResidencyManager residency;
    private final WorldLivingEntityManager entities;
    private final WorldTickPipeline tickPipeline;
    private final NexoraAiDiagnostics diagnostics;
    private final long tickBudgetNanos;

    public WorldEntityTickCoordinator(
            WorldChunkResidencyManager residency,
            WorldLivingEntityManager entities,
            WorldTickPipeline tickPipeline,
            NexoraAiDiagnostics diagnostics,
            long tickBudgetNanos
    ) {
        this(null, residency, entities, tickPipeline, diagnostics, tickBudgetNanos);
    }

    /** Production constructor bound to the world's authoritative entity manager and chunk cache. */
    public WorldEntityTickCoordinator(
            World world,
            WorldChunkResidencyManager residency,
            WorldTickPipeline tickPipeline,
            NexoraAiDiagnostics diagnostics,
            long tickBudgetNanos
    ) {
        this(Objects.requireNonNull(world, "world"), residency, world.livingEntities(),
                tickPipeline, diagnostics, tickBudgetNanos);
    }

    private WorldEntityTickCoordinator(
            World world,
            WorldChunkResidencyManager residency,
            WorldLivingEntityManager entities,
            WorldTickPipeline tickPipeline,
            NexoraAiDiagnostics diagnostics,
            long tickBudgetNanos
    ) {
        this.world = world;
        this.residency = Objects.requireNonNull(residency, "residency");
        this.entities = Objects.requireNonNull(entities, "entities");
        this.tickPipeline = Objects.requireNonNull(tickPipeline, "tickPipeline");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        if (tickBudgetNanos < 1) throw new IllegalArgumentException("tickBudgetNanos must be positive");
        this.tickBudgetNanos = tickBudgetNanos;
    }

    public TickResult tick(
            long tick,
            long nowMillis,
            List<WorldChunkResidencyManager.PlayerChunk> playerChunks,
            List<LivingEntityAiService.PlayerTarget> players,
            Consumer<LivingEntityAiService.Decision> decisionSink
    ) {
        long started = System.nanoTime();
        WorldChunkResidencyManager.ResidencyPlan plan = residency.update(playerChunks, tick);
        World.ChunkResidencyResult worldChunkResult = world == null
                ? new World.ChunkResidencyResult(0, 0, 0, 0, 0)
                : world.applyChunkResidencyPlan(plan);
        List<WorldLivingEntity> activeEntities = new ArrayList<>();
        for (WorldLivingEntity entity : entities.snapshot()) {
            if (residency.isResidentAtBlockPosition(entity.x(), entity.z())) {
                activeEntities.add(entity);
            }
        }

        WorldTickPipeline.TickResult aiResult;
        try {
            aiResult = tickPipeline.tick(tick, nowMillis, activeEntities, players, decisionSink);
        } finally {
            diagnostics.recordSlowOperation("world-entity-tick",
                    Math.max(0L, System.nanoTime() - started), tickBudgetNanos);
        }
        return new TickResult(plan, worldChunkResult, entities.size(), activeEntities.size(),
                entities.size() - activeEntities.size(), aiResult);
    }

    public record TickResult(
            WorldChunkResidencyManager.ResidencyPlan residencyPlan,
            World.ChunkResidencyResult worldChunkResult,
            int totalEntities,
            int residentEntities,
            int dormantEntities,
            WorldTickPipeline.TickResult aiResult
    ) {
        public TickResult {
            Objects.requireNonNull(residencyPlan, "residencyPlan");
            Objects.requireNonNull(worldChunkResult, "worldChunkResult");
            Objects.requireNonNull(aiResult, "aiResult");
            if (totalEntities < 0 || residentEntities < 0 || dormantEntities < 0
                    || residentEntities + dormantEntities != totalEntities) {
                throw new IllegalArgumentException("Invalid entity tick counts");
            }
        }
    }
}
