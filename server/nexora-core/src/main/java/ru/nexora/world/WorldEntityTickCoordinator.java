package ru.nexora.world;

import ru.nexora.ai.NexoraAiDiagnostics;
import ru.nexora.combat.LivingEntityAiService;
import ru.nexora.combat.MobAiLoadController;
import ru.nexora.combat.MobPhysicsService;

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
    private final WorldEntityDecisionApplier decisionApplier;
    private final MobPhysicsService physicsService;

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
        this.decisionApplier = world == null ? null : new WorldEntityDecisionApplier(world, entities);
        this.physicsService = world == null ? null : new MobPhysicsService();
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
        Objects.requireNonNull(playerChunks, "playerChunks");
        Objects.requireNonNull(players, "players");
        Objects.requireNonNull(decisionSink, "decisionSink");
        long started = System.nanoTime();
        WorldChunkResidencyManager.ResidencyPlan plan = residency.update(playerChunks, tick);
        World.ChunkResidencyResult worldChunkResult = world == null
                ? new World.ChunkResidencyResult(0, 0, 0, 0, 0)
                : world.applyChunkResidencyPlan(plan);
        WorldLivingEntityManager.ChunkSnapshot entitySnapshot =
                entities.snapshotInChunks(residency.residentChunkCoordinates());
        int totalEntities = entitySnapshot.totalEntities();
        List<WorldLivingEntity> activeEntities = new ArrayList<>(entitySnapshot.residentEntities());

        WorldTickPipeline.TickResult aiResult;
        try {
            aiResult = tickPipeline.tick(tick, nowMillis, activeEntities, players, decision -> {
                if (decisionApplier != null) decisionApplier.apply(decision, nowMillis);
                decisionSink.accept(decision);
            });
            // Apply gravity and knockback after AI movement, using fresh registry snapshots
            // so physics sees positions written by the decision applier in this same tick.
            if (physicsService != null) {
                for (WorldLivingEntity active : activeEntities) {
                    entities.find(active.runtimeId()).ifPresent(current ->
                            physicsService.tick(world, current));
                }
            }
        } finally {
            diagnostics.recordSlowOperation("world-entity-tick",
                    Math.max(0L, System.nanoTime() - started), tickBudgetNanos);
        }
        return new TickResult(plan, worldChunkResult, totalEntities, activeEntities.size(),
                totalEntities - activeEntities.size(), aiResult);
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
