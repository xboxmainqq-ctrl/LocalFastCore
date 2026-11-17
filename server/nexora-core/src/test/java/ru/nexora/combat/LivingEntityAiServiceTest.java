package ru.nexora.combat;

import org.junit.jupiter.api.Test;
import ru.nexora.world.WorldLivingEntity;
import ru.nexora.world.WorldLivingEntityManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LivingEntityAiServiceTest {
    private final LivingEntityAiService ai = new LivingEntityAiService();

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
    void rejectsInvalidTimeAndTarget() {
        WorldLivingEntity zombie = new WorldLivingEntityManager().spawn(
                "minecraft:zombie", 20, 0, 64, 0);
        assertThrows(IllegalArgumentException.class,
                () -> ai.decide(zombie, List.of(), -1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new LivingEntityAiService.PlayerTarget("", 0, 64, 0, true));
    }
}
