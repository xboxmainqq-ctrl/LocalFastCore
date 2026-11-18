package ru.nexora.world;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.nexora.combat.LivingEntityAiService;
import ru.nexora.combat.MobAiLoadController;

class WorldTickPipelineTest {

    @Test
    void schedulesNearMobsAndDefersFarUnprovokedMobs() {
        WorldTickPipeline pipeline = new WorldTickPipeline(
                new MobAiLoadController(2), new LivingEntityAiService());
        WorldLivingEntityManager manager = new WorldLivingEntityManager();
        WorldLivingEntity near = manager.spawn("minecraft:zombie", 20, 0, 64, 0);
        WorldLivingEntity far = manager.spawn("minecraft:zombie", 20, 100, 64, 100);
        var player = new LivingEntityAiService.PlayerTarget("player", 0, 64, 0, true);
        List<LivingEntityAiService.Decision> decisions = new ArrayList<>();

        var result = pipeline.tick(0, 1_000, List.of(near, far), List.of(player), decisions::add);

        assertEquals(2, result.observedMobs());
        assertEquals(1, result.scheduledMobs());
        assertEquals(1, result.deferredMobs());
        assertEquals(1, decisions.size());
        assertEquals(near.runtimeId(), decisions.getFirst().mobRuntimeId());
        assertTrue(decisions.getFirst().moving());
    }

    @Test
    void activeTargetForcesFullRateEvenWhenFarUpdateSlotWouldDefer() {
        WorldTickPipeline pipeline = new WorldTickPipeline(
                new MobAiLoadController(20), new LivingEntityAiService());
        WorldLivingEntityManager manager = new WorldLivingEntityManager();
        WorldLivingEntity mob = manager.spawn("minecraft:zombie", 20, 0, 64, 0);
        var player = new LivingEntityAiService.PlayerTarget("player", 1.5, 64, 0, true);
        List<LivingEntityAiService.Decision> decisions = new ArrayList<>();

        var result = pipeline.tick(19, 2_000, List.of(mob), List.of(player), decisions::add);

        assertEquals(1, result.scheduledMobs());
        assertEquals(1, result.decisionsProduced());
        assertEquals("player", decisions.getFirst().targetNetworkId());
        assertTrue(decisions.getFirst().attack());
    }
}
