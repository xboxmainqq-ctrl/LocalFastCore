package ru.nexora.combat;

import org.junit.jupiter.api.Test;
import ru.nexora.world.WorldLivingEntity;
import ru.nexora.world.WorldLivingEntityManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LivingEntityAiServiceTest {
    private final LivingEntityAiService ai = new LivingEntityAiService();

    @Test
    void decisionMetricsCountDelegatedCallsOnceAndTrackFailures() {
        WorldLivingEntity zombie = new WorldLivingEntityManager().spawn(
                "minecraft:zombie", 20, 0, 64, 0);
        var player = new LivingEntityAiService.PlayerTarget("player", 4, 64, 0, true);

        ai.decide(zombie, List.of(player), 1_000, 0);
        ai.decide(zombie, List.of(player), 1_000, 0, null,
                LivingEntityAiService.nearestAlivePlayer(zombie, List.of(player)));

        var beforeFailure = ai.stats();
        assertEquals(2, beforeFailure.decisionCount());
        assertEquals(0, beforeFailure.failures());
        assertTrue(beforeFailure.totalDecisionNanos() >= beforeFailure.lastDecisionNanos());
        assertTrue(beforeFailure.maxDecisionNanos() >= beforeFailure.lastDecisionNanos());
        assertTrue(beforeFailure.averageDecisionNanos() >= 0);

        assertThrows(IllegalArgumentException.class,
                () -> ai.decide(zombie, List.of(), -1, 0, null, null));
        var afterFailure = ai.stats();
        assertEquals(3, afterFailure.decisionCount());
        assertEquals(1, afterFailure.failures());
        assertTrue(afterFailure.maxDecisionNanos() >= afterFailure.lastDecisionNanos());
    }

    @Test
    void hostileMobAcquiresNearestAlivePlayerAndMovesTowardThem() {
        WorldLivingEntity zombie = new WorldLivingEntityManager().spawn(
                "minecraft:zombie", 20, 0, 64, 0);
        var decision = ai.decide(zombie, List.of(
                new LivingEntityAiService.PlayerTarget("far", 8, 64, 0, true),
                new LivingEntityAiService.PlayerTarget("near", 4, 64, 0, true),
                new LivingEntityAiService.PlayerTarget("dead", 1, 64, 0, false)), 1_000, 0);

        assertEquals("near", decision.targetNetworkId());
        assertTrue(decision.moving());
        assertFalse(decision.attack());
        assertEquals(0.23, decision.moveX(), 0.0001);
        assertEquals(0.0, decision.moveZ(), 0.0001);
    }

    @Test
    void attacksOnlyInsideReachAndHonorsCooldown() {
        WorldLivingEntity zombie = new WorldLivingEntityManager().spawn(
                "minecraft:zombie", 20, 0, 64, 0);
        var player = new LivingEntityAiService.PlayerTarget("player", 1, 64, 0, true);

        var ready = ai.decide(zombie, List.of(player), 2_000, 1_000);
        assertTrue(ready.attack());
        assertEquals(3.0f, ready.attackDamage());
        assertEquals("player", ready.targetNetworkId());

        var coolingDown = ai.decide(zombie, List.of(player), 2_500, 3_000);
        assertFalse(coolingDown.attack());
        assertFalse(coolingDown.moving());
    }

    @Test
    void usesDifferentMeleeDamageForDifferentHostileSpecies() {
        WorldLivingEntity spider = new WorldLivingEntityManager().spawn(
                "minecraft:spider", 16, 0, 64, 0);
        WorldLivingEntity silverfish = new WorldLivingEntityManager().spawn(
                "minecraft:silverfish", 8, 0, 64, 0);
        var player = new LivingEntityAiService.PlayerTarget("player", 1, 64, 0, true);

        assertEquals(2.0f, ai.decide(spider, List.of(player), 1_000, 0).attackDamage());
        assertEquals(1.0f, ai.decide(silverfish, List.of(player), 1_000, 0).attackDamage());
    }

    @Test
    void flyingHostilesPursueInThreeDimensionsAndPassiveFlyingMobsFleeVertically() {
        assertTrue(LivingEntityAiService.isHostile("minecraft:vex"));
        assertTrue(LivingEntityAiService.isFlyingMob("minecraft:vex"));
        assertTrue(LivingEntityAiService.isHostile("minecraft:wither"));
        assertTrue(LivingEntityAiService.isFlyingMob("minecraft:wither"));
        WorldLivingEntity ghast = new WorldLivingEntityManager().spawn(
                "minecraft:ghast", 10, 0, 64, 0);
        // Keep the player outside projectile range so the flying-hostile path must pursue in 3D.
        var elevated = new LivingEntityAiService.PlayerTarget("player", 17, 74, 0, true);
        var ghastDecision = ai.decide(ghast, List.of(elevated), 1_000, 0);
        assertTrue(ghastDecision.moving());
        assertTrue(ghastDecision.moveY() > 0);

        WorldLivingEntity bat = new WorldLivingEntityManager().spawn(
                "minecraft:bat", 6, 0.5, 65, 0.5);
        var above = new LivingEntityAiService.PlayerTarget("player", 0.5, 69, 0.5, true);
        var batDecision = ai.decide(bat, List.of(above), 1_000, 0);
        assertTrue(batDecision.moving());
        assertTrue(batDecision.moveY() < 0);
    }

    @Test
    void meleeAttackCadenceUsesSpeciesProfilesAndStableDefault() {
        assertEquals(1_000L,
                LivingEntityAiService.attackCooldownMillisFor("minecraft:zombie"));
        assertEquals(1_250L,
                LivingEntityAiService.attackCooldownMillisFor("minecraft:spider"));
        assertEquals(1_500L,
                LivingEntityAiService.attackCooldownMillisFor("minecraft:ravager"));
        assertEquals(1_750L,
                LivingEntityAiService.attackCooldownMillisFor("minecraft:warden"));
        assertThrows(NullPointerException.class,
                () -> LivingEntityAiService.attackCooldownMillisFor(null));
    }

    @Test
    void speciesProfilesAdjustMovementSpeedReachAndDamage() {
        assertTrue(LivingEntityAiService.movementSpeedFor("minecraft:horse")
                > LivingEntityAiService.movementSpeedFor("minecraft:turtle"));
        assertEquals(3.0, LivingEntityAiService.attackRangeFor("minecraft:enderman"));
        assertEquals(2.5, LivingEntityAiService.attackRangeFor("minecraft:iron_golem"));
        assertEquals(6.0f, LivingEntityAiService.attackDamageFor("minecraft:ravager"));
        assertEquals(2.0f, LivingEntityAiService.attackDamageFor("minecraft:bee"));

        WorldLivingEntity enderman = new WorldLivingEntityManager().spawn(
                "minecraft:enderman", 40, 0, 64, 0);
        var player = new LivingEntityAiService.PlayerTarget("player", 2.5, 64, 0, true);
        assertFalse(ai.decide(enderman, List.of(player), 1_000, 0).attack());
        assertTrue(ai.decide(enderman, List.of(player), 2_000, 0, "player").attack());
    }

    @Test
    void passiveMobsDoNotFleeFromOrdinaryPlayersUnlessTimidOrProvoked() {
        var player = new LivingEntityAiService.PlayerTarget("player", 1, 64, 0, true);
        WorldLivingEntity cow = new WorldLivingEntityManager().spawn(
                "minecraft:cow", 10, 0, 64, 0);
        var calmCow = ai.decide(cow, List.of(player), 1_000, 0);
        assertTrue(calmCow.moving());
        assertNull(calmCow.targetNetworkId());

        var frightenedCow = ai.decide(cow, List.of(player), 1_000, 0, "player");
        assertEquals("player", frightenedCow.targetNetworkId());
        assertTrue(frightenedCow.moveX() < 0);

        WorldLivingEntity rabbit = new WorldLivingEntityManager().spawn(
                "minecraft:rabbit", 3, 0, 64, 0);
        var rabbitDecision = ai.decide(rabbit, List.of(player), 1_000, 0);
        assertEquals("player", rabbitDecision.targetNetworkId());
        assertTrue(rabbitDecision.moveX() < 0);
    }

    @Test
    void passiveMobsAndPlayersOutsideAcquireRangeRemainIdle() {
        WorldLivingEntity cow = new WorldLivingEntityManager().spawn(
                "minecraft:cow", 10, 0, 64, 0);
        WorldLivingEntity zombie = new WorldLivingEntityManager().spawn(
                "minecraft:zombie", 20, 0, 64, 0);
        var distant = new LivingEntityAiService.PlayerTarget("far", 30, 64, 0, true);

        assertTrue(ai.decide(cow, List.of(distant), 1, 0).moving());
        assertFalse(ai.decide(zombie, List.of(distant), 1, 0).moving());
        assertFalse(ai.decide(zombie, List.of(), 1, 0).attack());
    }


    @Test
    void piglinsAndZombifiedPiglinsAreNeutralUntilProvoked() {
        assertFalse(LivingEntityAiService.isHostile("minecraft:piglin"));
        assertFalse(LivingEntityAiService.isHostile("minecraft:zombified_piglin"));
        WorldLivingEntity piglin = new WorldLivingEntityManager().spawn(
                "minecraft:piglin", 12, 0, 64, 0);
        var player = new LivingEntityAiService.PlayerTarget("attacker", 1, 64, 0, true);
        assertFalse(ai.decide(piglin, List.of(player), 1_000, 0).attack());
        assertTrue(ai.decide(piglin, List.of(player), 2_000, 0, "attacker").attack());
    }

    @Test
    void villagersAndWanderingTradersDoNotFleeFromPlayers() {
        WorldLivingEntity villager = new WorldLivingEntityManager().spawn(
                "minecraft:villager", 20, 0, 64, 0);
        WorldLivingEntity trader = new WorldLivingEntityManager().spawn(
                "minecraft:wandering_trader", 20, 0, 64, 0);
        var nearby = new LivingEntityAiService.PlayerTarget("player", 2, 64, 0, true);

        var villagerDecision = ai.decide(villager, List.of(nearby), 1_000, 0);
        var traderDecision = ai.decide(trader, List.of(nearby), 1_000, 0);

        assertTrue(villagerDecision.moving());
        assertTrue(traderDecision.moving());
        assertNull(villagerDecision.targetNetworkId());
        assertNull(traderDecision.targetNetworkId());
    }

    @Test
    void nearestAlivePlayerUsesClosestLivingTargetAndSkipsNullAndDeadEntries() {
        WorldLivingEntity zombie = new WorldLivingEntityManager().spawn(
                "minecraft:zombie", 20, 0, 64, 0);
        var nearest = new LivingEntityAiService.PlayerTarget("nearest", 2, 64, 0, true);
        var farther = new LivingEntityAiService.PlayerTarget("farther", 8, 64, 0, true);
        var dead = new LivingEntityAiService.PlayerTarget("dead", 1, 64, 0, false);

        assertSame(nearest, LivingEntityAiService.nearestAlivePlayer(
                zombie, java.util.Arrays.asList(farther, null, dead, nearest)));
        assertNull(LivingEntityAiService.nearestAlivePlayer(zombie, List.of()));
    }

    @Test
    void precomputedNearestPlayerProducesTheSameDecisionWithoutRescanning() {
        WorldLivingEntity zombie = new WorldLivingEntityManager().spawn(
                "minecraft:zombie", 20, 0, 64, 0);
        var nearest = new LivingEntityAiService.PlayerTarget("nearest", 2, 64, 0, true);
        var players = List.of(
                new LivingEntityAiService.PlayerTarget("far", 8, 64, 0, true), nearest);
        var normal = ai.decide(zombie, players, 1_000, 0, null);
        var precomputed = ai.decide(zombie, players, 1_000, 0, null,
                LivingEntityAiService.nearestAlivePlayer(zombie, players));

        assertEquals(normal, precomputed);
    }

    @Test
    void precomputedNearestPlayerMustBeAlive() {
        WorldLivingEntity zombie = new WorldLivingEntityManager().spawn(
                "minecraft:zombie", 20, 0, 64, 0);
        var dead = new LivingEntityAiService.PlayerTarget("dead", 1, 64, 0, false);

        assertThrows(IllegalArgumentException.class,
                () -> ai.decide(zombie, List.of(dead), 1_000, 0, null, dead));
    }

    @Test
    void rejectsInvalidTimeAndTarget() {
        WorldLivingEntity zombie = new WorldLivingEntityManager().spawn(
                "minecraft:zombie", 20, 0, 64, 0);
        assertThrows(IllegalArgumentException.class,
                () -> ai.decide(zombie, List.of(), -1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new LivingEntityAiService.PlayerTarget("", 0, 64, 0, true));
    }
    @Test
    void passivePreyFleesFromNearbyPlayers() {
        WorldLivingEntity cow = new WorldLivingEntityManager().spawn(
                "minecraft:cow", 10, 0, 64, 0);
        var nearby = new LivingEntityAiService.PlayerTarget("player", 2, 64, 0, true);

        var decision = ai.decide(cow, List.of(nearby), 1_000, 0);

        assertTrue(decision.moving());
        assertTrue(decision.moveX() < 0);
        assertNull(decision.targetNetworkId());
    }

    @Test
    void neutralArthropodOnlyRetaliatesAgainstItsProvoker() {
        WorldLivingEntity bee = new WorldLivingEntityManager().spawn(
                "minecraft:bee", 10, 0, 64, 0);
        var attacker = new LivingEntityAiService.PlayerTarget("attacker", 1, 64, 0, true);
        var bystander = new LivingEntityAiService.PlayerTarget("bystander", 1.5, 64, 0, true);

        assertFalse(ai.decide(bee, List.of(attacker), 1_000, 0).attack());
        assertTrue(ai.decide(bee, List.of(attacker, bystander), 2_000, 0, "attacker").attack());
        assertEquals("attacker",
                ai.decide(bee, List.of(attacker, bystander), 2_000, 0, "attacker").targetNetworkId());
    }

    @Test
    void flyingHostileMobUsesThreeDimensionalPursuit() {
        WorldLivingEntity phantom = new WorldLivingEntityManager().spawn(
                "minecraft:phantom", 20, 0, 64, 0);
        var player = new LivingEntityAiService.PlayerTarget("player", 4, 70, 0, true);

        var decision = ai.decide(phantom, List.of(player), 1_000, 0);

        assertTrue(LivingEntityAiService.isFlyingMob("minecraft:phantom"));
        assertTrue(decision.moving());
        assertTrue(decision.moveX() > 0);
        assertTrue(decision.moveY() > 0);
    }

    @Test
    void flyingPassiveMobCanFleeInThreeDimensions() {
        WorldLivingEntity bat = new WorldLivingEntityManager().spawn(
                "minecraft:bat", 6, 0, 70, 0);
        var player = new LivingEntityAiService.PlayerTarget("player", 1, 64, 0, true);

        var decision = ai.decide(bat, List.of(player), 1_000, 0);

        assertTrue(decision.moving());
        assertTrue(decision.moveY() > 0);
    }


    @Test
    void skeletonUsesProjectileIntentAtRangedDistance() {
        LivingEntityAiService service = new LivingEntityAiService();
        WorldLivingEntityManager manager = new WorldLivingEntityManager();
        WorldLivingEntity skeleton = manager.spawn("minecraft:skeleton", 20, 0, 64, 0);
        var target = new LivingEntityAiService.PlayerTarget("player", 10, 64, 0, true);

        var decision = service.decide(skeleton, List.of(target), 2_000, 0);

        assertTrue(decision.attack());
        assertEquals(LivingEntityAiService.AttackStyle.PROJECTILE, decision.attackStyle());
        assertEquals(LivingEntityAiService.BASE_ATTACK_DAMAGE, decision.attackDamage());
    }

    @Test
    void rangedMobApproachesUntilInsideItsProjectileRange() {
        LivingEntityAiService service = new LivingEntityAiService();
        WorldLivingEntityManager manager = new WorldLivingEntityManager();
        WorldLivingEntity skeleton = manager.spawn("minecraft:skeleton", 20, 0, 64, 0);
        var target = new LivingEntityAiService.PlayerTarget("player", 15, 64, 0, true);

        var decision = service.decide(skeleton, List.of(target), 2_000, 0);

        assertFalse(decision.attack());
        assertTrue(decision.moving());
        assertEquals("player", decision.targetNetworkId());
    }

    @Test
    void rangedMobRetreatsWhenAnOpponentGetsTooCloseDuringReload() {
        WorldLivingEntity skeleton = new WorldLivingEntityManager().spawn(
                "minecraft:skeleton", 20, 0, 64, 0);
        var nearby = new LivingEntityAiService.PlayerTarget("player", 2, 64, 0, true);

        var decision = ai.decide(skeleton, List.of(nearby), 2_500, 3_000);

        assertFalse(decision.attack());
        assertTrue(decision.moving());
        assertTrue(decision.moveX() < 0);
        assertEquals("player", decision.targetNetworkId());
    }

    @Test
    void rangedMobRetreatsInsteadOfFiringAtPointBlankEvenWhenItsCooldownIsReady() {
        WorldLivingEntity skeleton = new WorldLivingEntityManager().spawn(
                "minecraft:skeleton", 20, 0, 64, 0);
        var nearby = new LivingEntityAiService.PlayerTarget("player", 2, 64, 0, true);

        var decision = ai.decide(skeleton, List.of(nearby), 3_000, 2_500);

        assertFalse(decision.attack());
        assertTrue(decision.moving());
        assertTrue(decision.moveX() < 0);
    }

    @Test
    void meleeMobDoesNotInheritProjectileCombatStyle() {
        assertEquals(LivingEntityAiService.AttackStyle.MELEE,
                LivingEntityAiService.attackStyleFor("minecraft:zombie"));
        assertEquals(LivingEntityAiService.AttackStyle.PROJECTILE,
                LivingEntityAiService.attackStyleFor("minecraft:bogged"));
        assertEquals(12.0, LivingEntityAiService.projectileRangeFor("minecraft:pillager"));
        assertEquals(12.0, LivingEntityAiService.projectileRangeFor("minecraft:skeleton"));
        assertEquals(14.0, LivingEntityAiService.projectileRangeFor("minecraft:blaze"));
        assertEquals(16.0, LivingEntityAiService.projectileRangeFor("minecraft:ghast"));
        assertEquals(0.0, LivingEntityAiService.projectileRangeFor("minecraft:zombie"));
    }

    @Test
    void rangedMobRetreatsAtCloseRangeEvenWhenItsCooldownIsReady() {
        WorldLivingEntity skeleton = new WorldLivingEntityManager().spawn(
                "minecraft:skeleton", 20, 0, 64, 0);
        var nearby = new LivingEntityAiService.PlayerTarget("player", 2, 64, 0, true);

        var decision = ai.decide(skeleton, List.of(nearby), 3_000, 0);

        assertFalse(decision.attack());
        assertTrue(decision.moving());
        assertTrue(decision.moveX() < 0);
    }

    @Test
    void meleeAttackCooldownsAreSpeciesSpecific() {
        assertEquals(1_000L,
                LivingEntityAiService.attackCooldownMillisFor("minecraft:zombie"));
        assertEquals(1_500L,
                LivingEntityAiService.attackCooldownMillisFor("minecraft:ravager"));
        assertEquals(1_750L,
                LivingEntityAiService.attackCooldownMillisFor("minecraft:warden"));
    }

    @Test
    void rangedAttackCooldownsAreSpeciesSpecific() {
        assertEquals(1_250L,
                LivingEntityAiService.rangedAttackCooldownMillisFor("minecraft:skeleton"));
        assertEquals(1_500L,
                LivingEntityAiService.rangedAttackCooldownMillisFor("minecraft:guardian"));
        assertEquals(3_000L,
                LivingEntityAiService.rangedAttackCooldownMillisFor("minecraft:ghast"));
        assertEquals(1_000L,
                LivingEntityAiService.rangedAttackCooldownMillisFor("minecraft:blaze"));
    }

    @Test
    void blockedRangedTargetProducesGroundApproachIntent() {
        WorldLivingEntity skeleton = new WorldLivingEntityManager().spawn(
                "minecraft:skeleton", 20, 0, 64, 0);
        var target = new LivingEntityAiService.PlayerTarget("player", 10, 64, 0, true);

        var decision = ai.approach(skeleton, target);

        assertTrue(decision.moving());
        assertEquals("player", decision.targetNetworkId());
        assertEquals(LivingEntityAiService.AttackStyle.PROJECTILE, decision.attackStyle());
        assertFalse(decision.attack());
        assertTrue(decision.moveX() > 0);
    }

    @Test
    void blockedRangedTargetStillRetreatsAtPointBlankRange() {
        WorldLivingEntity skeleton = new WorldLivingEntityManager().spawn(
                "minecraft:skeleton", 20, 0, 64, 0);
        var nearby = new LivingEntityAiService.PlayerTarget("player", 2, 64, 0, true);

        var decision = ai.approach(skeleton, nearby);

        assertTrue(decision.moving());
        assertEquals("player", decision.targetNetworkId());
        assertEquals(LivingEntityAiService.AttackStyle.PROJECTILE, decision.attackStyle());
        assertFalse(decision.attack());
        assertTrue(decision.moveX() < 0);
    }

    @Test
    void blockedRangedTargetProducesThreeDimensionalApproachForFlyingMobs() {
        WorldLivingEntity ghast = new WorldLivingEntityManager().spawn(
                "minecraft:ghast", 20, 0, 64, 0);
        var target = new LivingEntityAiService.PlayerTarget("player", 10, 70, 0, true);

        var decision = ai.approach(ghast, target);

        assertTrue(decision.moving());
        assertTrue(decision.moveX() > 0);
        assertTrue(decision.moveY() > 0);
    }

}
