package ru.nexora.world;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.nexora.ai.NexoraAiDiagnostics;
import ru.nexora.combat.LivingEntityAiService;
import ru.nexora.combat.MobAiLoadController;

class WorldEntityTickCoordinatorTest {

    @Test
    void onlyTicksEntitiesInResidentChunksAndKeepsGracePeriod() {
        WorldChunkResidencyManager residency = new WorldChunkResidencyManager(2, 5);
        WorldLivingEntityManager entities = new WorldLivingEntityManager();
        WorldLivingEntity resident = entities.spawn("minecraft:zombie", 20, 0, 64, 0);
        entities.spawn("minecraft:zombie", 20, 1_000, 64, 1_000);
        WorldTickPipeline pipeline = new WorldTickPipeline(
                new MobAiLoadController(), new LivingEntityAiService());
        WorldEntityTickCoordinator coordinator = new WorldEntityTickCoordinator(
                residency, entities, pipeline, new NexoraAiDiagnostics(), 50_000_000L);
        var player = new LivingEntityAiService.PlayerTarget("p", 0, 64, 0, true);
        List<LivingEntityAiService.Decision> decisions = new ArrayList<>();

        var first = coordinator.tick(0, 1_000,
                List.of(new WorldChunkResidencyManager.PlayerChunk(0, 0)),
                List.of(player), decisions::add);
        assertEquals(2, first.totalEntities());
        assertEquals(1, first.residentEntities());
        assertEquals(1, first.dormantEntities());
        assertEquals(1, first.aiResult().scheduledMobs());
        assertEquals(resident.runtimeId(), decisions.getFirst().mobRuntimeId());

        var grace = coordinator.tick(2, 1_100, List.of(), List.of(), ignored -> {});
        assertEquals(1, grace.residentEntities());

        var expired = coordinator.tick(5, 1_200, List.of(), List.of(), ignored -> {});
        assertEquals(0, expired.residentEntities());
        assertEquals(2, expired.dormantEntities());
        assertEquals(0, expired.aiResult().decisionsProduced());
    }

    @Test
    void negativeBlockCoordinatesUseFloorChunkDivision() {
        WorldChunkResidencyManager residency = new WorldChunkResidencyManager(2, 10);
        residency.update(List.of(new WorldChunkResidencyManager.PlayerChunk(-1, -1)), 0);

        assertTrue(residency.isResidentAtBlockPosition(-1, -1));
        assertTrue(residency.isResidentAtBlockPosition(-16, -16));
        assertFalse(residency.isResidentAtBlockPosition(48, 48));
    }

    @Test
    void worldBoundCoordinatorAppliesGravityToResidentMobs() {
        World world = new World("world-coordinator-physics", 0L, new FlatWorldGenerator());
        WorldLivingEntity mob = world.livingEntities().spawn(
                "minecraft:zombie", 20, 0.5, 70.0, 0.5);
        WorldChunkResidencyManager residency = new WorldChunkResidencyManager(1, 5);
        WorldTickPipeline pipeline = new WorldTickPipeline(
                new MobAiLoadController(), new LivingEntityAiService());
        WorldEntityTickCoordinator coordinator = new WorldEntityTickCoordinator(
                world, residency, pipeline, new NexoraAiDiagnostics(), 50_000_000L);

        coordinator.tick(0, 1_000,
                List.of(new WorldChunkResidencyManager.PlayerChunk(0, 0)),
                List.of(), ignored -> {});

        WorldLivingEntity updated = world.livingEntities().find(mob.runtimeId()).orElseThrow();
        assertTrue(updated.y() < mob.y(),
                "The world coordinator must apply gravity to resident mobs");
        assertTrue(updated.velocityY() < 0,
                "Gravity must persist vertical velocity for the next world tick");
    }

    @Test
    void worldBoundCoordinatorAppliesAiMovementBeforePhysics() {
        World world = new World("world-coordinator-ai-apply", 0L, new FlatWorldGenerator());
        WorldLivingEntity mob = world.livingEntities().spawn(
                "minecraft:zombie", 20, 0.5, 65.0, 0.5);
        WorldChunkResidencyManager residency = new WorldChunkResidencyManager(1, 5);
        WorldTickPipeline pipeline = new WorldTickPipeline(
                new MobAiLoadController(), new LivingEntityAiService());
        WorldEntityTickCoordinator coordinator = new WorldEntityTickCoordinator(
                world, residency, pipeline, new NexoraAiDiagnostics(), 50_000_000L);
        var target = new LivingEntityAiService.PlayerTarget("player", 4.5, 65.0, 0.5, true);
        List<LivingEntityAiService.Decision> decisions = new ArrayList<>();

        coordinator.tick(0, 1_000,
                List.of(new WorldChunkResidencyManager.PlayerChunk(0, 0)),
                List.of(target), decisions::add);

        WorldLivingEntity updated = world.livingEntities().find(mob.runtimeId()).orElseThrow();
        assertFalse(decisions.isEmpty(), "The AI pipeline should produce a mob decision");
        assertTrue(updated.x() > mob.x(),
                "The coordinator must apply the produced AI movement to the world registry");
    }


    @Test
    void worldBoundCoordinatorKeepsKnockbackInsideLoadedCollisionSpace() {
        World world = new World("world-coordinator-collision", 0L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        world.setBlock(1, 65, 0, BlockType.STONE);
        world.setBlock(1, 66, 0, BlockType.STONE);
        WorldLivingEntity mob = world.livingEntities().spawn(
                "minecraft:zombie", 20, 0.5, 65.0, 0.5);
        world.livingEntities().setVelocity(mob.runtimeId(), 1.0, 0.0, 0.0).orElseThrow();
        WorldChunkResidencyManager residency = new WorldChunkResidencyManager(1, 5);
        WorldTickPipeline pipeline = new WorldTickPipeline(
                new MobAiLoadController(), new LivingEntityAiService());
        WorldEntityTickCoordinator coordinator = new WorldEntityTickCoordinator(
                world, residency, pipeline, new NexoraAiDiagnostics(), 50_000_000L);

        coordinator.tick(0, 1_000,
                List.of(new WorldChunkResidencyManager.PlayerChunk(0, 0)),
                List.of(), ignored -> {});

        WorldLivingEntity updated = world.livingEntities().find(mob.runtimeId()).orElseThrow();
        assertEquals(0.5, updated.x(), 1.0e-6,
                "Physics must not push a mob through a solid wall");
        assertEquals(0.0, updated.velocityX(), 1.0e-6,
                "Blocked knockback must be cancelled");
    }

}
