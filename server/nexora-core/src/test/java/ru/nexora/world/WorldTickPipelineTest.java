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

        WorldLivingEntity near = new WorldLivingEntity(
                1, "minecraft:zombie", 0, 64, 0, 20);
        WorldLivingEntity far = new WorldLivingEntity(
                2, "minecraft:zombie", 100, 64, 100, 20);
        var player = new LivingEntityAiService.PlayerTarget(
                "player", 0, 64, 0, true);
        List<LivingEntityAiService.Decision> decisions = new ArrayList<>();

        var first = pipeline.tick(0, 1_000, List.of(near, far), List.of(player), decisions::add);

        assertEquals(2, first.observedMobs());
        assertEquals(1, first.scheduledMobs());
        assertEquals(1, first.deferredMobs());
        assertEquals(1, decisions.size());
        assertEquals(1, decisions.getFirst().mobRuntimeId());
        assertTrue(decisions.getFirst().moving());
    }

    @Test
    void activeTargetForcesFullRateEvenForFarMob() {
        WorldTickPipeline pipeline = new WorldTickPipeline(
                new MobAiLoadController(20), new LivingEntityAiService());
        WorldLivingEntity mob = new WorldLivingEntity(
                7, "minecraft:zombie", 0, 64, 0, 20);
        var player = new LivingEntityAiService.PlayerTarget(
                "player", 15, 64, 0, true);
        List<LivingEntityAiService.Decision> decisions = new ArrayList<>();

        var result = pipeline.tick(19, 2_000, List.of(mob), List.of(player), decisions::add);

        assertEquals(1, result.scheduledMobs());
        assertEquals(1, result.decisionsProduced());
        assertEquals("player", decisions.getFirst().targetNetworkId());
    }
}
