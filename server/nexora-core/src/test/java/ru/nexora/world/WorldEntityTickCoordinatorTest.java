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
}
