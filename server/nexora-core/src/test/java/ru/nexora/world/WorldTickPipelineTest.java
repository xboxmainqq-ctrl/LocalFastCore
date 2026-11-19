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
        WorldLivingEntity near = manager.spawn("minecraft:zombie", 20, 5, 64, 0);
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
    void hostileMobAttackCooldownIsAppliedAcrossWorldTicks() {
        WorldTickPipeline pipeline = new WorldTickPipeline(
                new MobAiLoadController(20), new LivingEntityAiService());
        WorldLivingEntityManager manager = new WorldLivingEntityManager();
        WorldLivingEntity mob = manager.spawn("minecraft:zombie", 20, 0, 64, 0);
        var player = new LivingEntityAiService.PlayerTarget("player", 1.5, 64, 0, true);
        List<LivingEntityAiService.Decision> decisions = new ArrayList<>();

        pipeline.tick(0, 2_000, List.of(mob), List.of(player), decisions::add);
        assertTrue(decisions.getFirst().attack());

        decisions.clear();
        pipeline.tick(1, 2_500, List.of(mob), List.of(player), decisions::add);
        assertEquals(1, decisions.size());
        assertFalse(decisions.getFirst().attack());

        decisions.clear();
        pipeline.tick(2, 3_000, List.of(mob), List.of(player), decisions::add);
        assertTrue(decisions.getFirst().attack());
    }

    @Test
    void reusedRuntimeIdDoesNotInheritPreviousMobsAttackCooldown() {
        WorldTickPipeline pipeline = new WorldTickPipeline(
                new MobAiLoadController(20), new LivingEntityAiService());
        WorldLivingEntityManager manager = new WorldLivingEntityManager();
        WorldLivingEntity original = manager.spawn("minecraft:zombie", 20, 0, 64, 0);
        WorldLivingEntity replacement = new WorldLivingEntity(
                original.uniqueId() + 1, original.runtimeId(), original.identifier(), original.family(),
                original.x(), original.y(), original.z(), original.velocityX(), original.velocityY(),
                original.velocityZ(), original.health(), original.maxHealth(),
                original.fireUntilMillis(), original.nextFireDamageMillis());
        var player = new LivingEntityAiService.PlayerTarget("player", 1.5, 64, 0, true);
        List<LivingEntityAiService.Decision> decisions = new ArrayList<>();

        pipeline.tick(0, 2_000, List.of(original), List.of(player), decisions::add);
        assertTrue(decisions.getFirst().attack());

        decisions.clear();
        pipeline.tick(1, 2_100, List.of(replacement), List.of(player), decisions::add);
        assertTrue(decisions.getFirst().attack());
    }

    @Test
    void heavyMobUsesLongerSpeciesSpecificAttackCooldown() {
        WorldTickPipeline pipeline = new WorldTickPipeline(
                new MobAiLoadController(20), new LivingEntityAiService());
        WorldLivingEntityManager manager = new WorldLivingEntityManager();
        WorldLivingEntity warden = manager.spawn("minecraft:warden", 20, 0, 64, 0);
        var player = new LivingEntityAiService.PlayerTarget("player", 1.5, 64, 0, true);
        List<LivingEntityAiService.Decision> decisions = new ArrayList<>();

        pipeline.tick(0, 2_000, List.of(warden), List.of(player), decisions::add);
        assertTrue(decisions.getFirst().attack());

        decisions.clear();
        pipeline.tick(1, 3_500, List.of(warden), List.of(player), decisions::add);
        assertFalse(decisions.getFirst().attack());

        decisions.clear();
        pipeline.tick(2, 3_750, List.of(warden), List.of(player), decisions::add);
        assertTrue(decisions.getFirst().attack());
    }

    @Test
    void attackCooldownProfilesRejectNullAndKeepBaselineForOrdinaryMobs() {
        assertEquals(LivingEntityAiService.ATTACK_COOLDOWN_MILLIS,
                LivingEntityAiService.attackCooldownMillisFor("minecraft:zombie"));
        assertTrue(LivingEntityAiService.attackCooldownMillisFor("minecraft:warden")
                > LivingEntityAiService.ATTACK_COOLDOWN_MILLIS);
        assertThrows(NullPointerException.class,
                () -> LivingEntityAiService.attackCooldownMillisFor(null));
    }

    @Test
    void attackCooldownDeadlineDoesNotOverflowAtMaximumTimestamp() {
        WorldTickPipeline pipeline = new WorldTickPipeline(
                new MobAiLoadController(20), new LivingEntityAiService());
        WorldLivingEntityManager manager = new WorldLivingEntityManager();
        WorldLivingEntity mob = manager.spawn("minecraft:zombie", 20, 0, 64, 0);
        var player = new LivingEntityAiService.PlayerTarget("player", 1.5, 64, 0, true);
        List<LivingEntityAiService.Decision> decisions = new ArrayList<>();

        pipeline.tick(0, Long.MAX_VALUE - 500, List.of(mob), List.of(player), decisions::add);
        assertTrue(decisions.getFirst().attack());

        decisions.clear();
        pipeline.tick(1, Long.MAX_VALUE - 100, List.of(mob), List.of(player), decisions::add);
        assertFalse(decisions.getFirst().attack());
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
