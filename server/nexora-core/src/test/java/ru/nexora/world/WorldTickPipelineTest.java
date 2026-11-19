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
    void neutralMobRetaliatesAgainstThePlayerWhoProvokedIt() {
        WorldTickPipeline pipeline = new WorldTickPipeline(
                new MobAiLoadController(20), new LivingEntityAiService());
        WorldLivingEntityManager manager = new WorldLivingEntityManager();
        WorldLivingEntity piglin = manager.spawn("minecraft:piglin", 20, 0, 64, 0);
        var attacker = new LivingEntityAiService.PlayerTarget("attacker", 1.5, 64, 0, true);
        List<LivingEntityAiService.Decision> decisions = new ArrayList<>();

        pipeline.recordProvocation(piglin.runtimeId(), "attacker", 1_000);
        pipeline.tick(0, 1_000, List.of(piglin), List.of(attacker), decisions::add);

        assertEquals(1, decisions.size());
        assertEquals("attacker", decisions.getFirst().targetNetworkId());
        assertTrue(decisions.getFirst().attack());
    }

    @Test
    void neutralMobForgetsProvocationAfterTenSecondsOrWhenCleared() {
        WorldTickPipeline pipeline = new WorldTickPipeline(
                new MobAiLoadController(20), new LivingEntityAiService());
        WorldLivingEntityManager manager = new WorldLivingEntityManager();
        WorldLivingEntity piglin = manager.spawn("minecraft:piglin", 20, 0, 64, 0);
        var attacker = new LivingEntityAiService.PlayerTarget("attacker", 1.5, 64, 0, true);
        List<LivingEntityAiService.Decision> decisions = new ArrayList<>();

        pipeline.recordProvocation(piglin.runtimeId(), "attacker", 1_000);
        pipeline.tick(0, 11_000, List.of(piglin), List.of(attacker), decisions::add);

        assertEquals(1, decisions.size());
        assertNull(decisions.getFirst().targetNetworkId());
        assertFalse(decisions.getFirst().attack());

        pipeline.recordProvocation(piglin.runtimeId(), "attacker", 12_000);
        pipeline.clearProvocation(piglin.runtimeId());
        decisions.clear();
        pipeline.tick(1, 12_001, List.of(piglin), List.of(attacker), decisions::add);

        assertNull(decisions.getFirst().targetNetworkId());
        assertFalse(decisions.getFirst().attack());
    }

    @Test
    void indexedTargetSelectionIsUsedForLargePlayerLists() {
        WorldTickPipeline pipeline = new WorldTickPipeline(
                new MobAiLoadController(20), new LivingEntityAiService());
        WorldLivingEntity mob = new WorldLivingEntityManager().spawn(
                "minecraft:zombie", 20, 0, 64, 0);
        List<LivingEntityAiService.PlayerTarget> players = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            players.add(new LivingEntityAiService.PlayerTarget(
                    "far-" + i, 80 + i, 64, 80 + i, true));
        }
        var nearest = new LivingEntityAiService.PlayerTarget("nearest", 1.5, 64, 0, true);
        players.add(nearest);
        List<LivingEntityAiService.Decision> decisions = new ArrayList<>();

        pipeline.tick(0, 2_000, List.of(mob), players, decisions::add);

        assertEquals(1, decisions.size());
        assertEquals("nearest", decisions.getFirst().targetNetworkId());
        assertTrue(decisions.getFirst().attack());
    }

    @Test
    void indexedProvokerLookupWorksForLargePlayerLists() {
        WorldTickPipeline pipeline = new WorldTickPipeline(
                new MobAiLoadController(20), new LivingEntityAiService());
        WorldLivingEntity piglin = new WorldLivingEntityManager().spawn(
                "minecraft:piglin", 20, 0, 64, 0);
        List<LivingEntityAiService.PlayerTarget> players = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            players.add(new LivingEntityAiService.PlayerTarget(
                    "bystander-" + i, 1.0 + i * 0.1, 64, 0, true));
        }
        var attacker = new LivingEntityAiService.PlayerTarget("attacker", 2.0, 64, 0, true);
        players.add(attacker);
        pipeline.recordProvocation(piglin.runtimeId(), "attacker", 2_000);
        List<LivingEntityAiService.Decision> decisions = new ArrayList<>();

        pipeline.tick(0, 2_000, List.of(piglin), players, decisions::add);

        assertEquals(1, decisions.size());
        assertEquals("attacker", decisions.getFirst().targetNetworkId());
        assertTrue(decisions.getFirst().attack());
    }

    @Test
    void rejectsInvalidMobProvocations() {
        WorldTickPipeline pipeline = new WorldTickPipeline(
                new MobAiLoadController(20), new LivingEntityAiService());

        assertThrows(IllegalArgumentException.class,
                () -> pipeline.recordProvocation(-1, "player", 1));
        assertThrows(IllegalArgumentException.class,
                () -> pipeline.recordProvocation(1, "", 1));
        assertThrows(IllegalArgumentException.class,
                () -> pipeline.recordProvocation(1, "player", -1));
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
    void rangedMobUsesItsOwnProjectileCooldownProfile() {
        WorldTickPipeline pipeline = new WorldTickPipeline(
                new MobAiLoadController(20), new LivingEntityAiService());
        WorldLivingEntityManager manager = new WorldLivingEntityManager();
        WorldLivingEntity skeleton = manager.spawn("minecraft:skeleton", 20, 0, 64, 0);
        var player = new LivingEntityAiService.PlayerTarget("player", 8, 64, 0, true);
        List<LivingEntityAiService.Decision> decisions = new ArrayList<>();

        pipeline.tick(0, 2_000, List.of(skeleton), List.of(player), decisions::add);
        assertTrue(decisions.getFirst().attack());
        assertEquals(LivingEntityAiService.AttackStyle.PROJECTILE, decisions.getFirst().attackStyle());

        decisions.clear();
        pipeline.tick(1, 3_000, List.of(skeleton), List.of(player), decisions::add);
        assertFalse(decisions.getFirst().attack());

        decisions.clear();
        pipeline.tick(2, 3_250, List.of(skeleton), List.of(player), decisions::add);
        assertTrue(decisions.getFirst().attack());
    }

    @Test
    void rangedCooldownProfilesAreSpeciesSpecificAndValidateInput() {
        assertEquals(1_250L,
                LivingEntityAiService.rangedAttackCooldownMillisFor("minecraft:skeleton"));
        assertEquals(3_000L,
                LivingEntityAiService.rangedAttackCooldownMillisFor("minecraft:ghast"));
        assertEquals(LivingEntityAiService.ATTACK_COOLDOWN_MILLIS,
                LivingEntityAiService.rangedAttackCooldownMillisFor("minecraft:zombie"));
        assertThrows(NullPointerException.class,
                () -> LivingEntityAiService.rangedAttackCooldownMillisFor(null));
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
