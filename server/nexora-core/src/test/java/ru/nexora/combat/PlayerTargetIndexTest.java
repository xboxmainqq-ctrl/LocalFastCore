package ru.nexora.combat;

import org.junit.jupiter.api.Test;
import ru.nexora.world.WorldLivingEntity;
import ru.nexora.world.WorldLivingEntityManager;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlayerTargetIndexTest {
    @Test
    void nearestTargetMatchesFullScanAcrossCellBoundaries() {
        WorldLivingEntity mob = new WorldLivingEntityManager().spawn(
                "minecraft:zombie", 20, 127.9, 64, -0.1);
        var nearest = new LivingEntityAiService.PlayerTarget("nearest", -0.1, 64, 0.1, true);
        var sameCell = new LivingEntityAiService.PlayerTarget("same-cell", 128.1, 64, 0.2, true);
        var farther = new LivingEntityAiService.PlayerTarget("farther", 200, 64, 0, true);
        var dead = new LivingEntityAiService.PlayerTarget("dead", 127.91, 64, -0.1, false);
        List<LivingEntityAiService.PlayerTarget> players =
                Arrays.asList(farther, null, dead, sameCell, nearest);

        PlayerTargetIndex index = new PlayerTargetIndex(players);

        assertSame(LivingEntityAiService.nearestAlivePlayer(mob, players),
                index.nearestWithinRange(mob));
        assertSame(nearest, index.find("nearest"));
        assertNull(index.find("dead"));
        assertEquals(3, index.playerCount());
    }

    @Test
    void nearestTargetReturnsNullOutsideTheDespawnAndAcquisitionRadius() {
        WorldLivingEntity mob = new WorldLivingEntityManager().spawn(
                "minecraft:zombie", 20, 0, 64, 0);
        PlayerTargetIndex index = new PlayerTargetIndex(List.of(
                new LivingEntityAiService.PlayerTarget("far", 128.01, 64, 0, true)));

        assertNull(index.nearestWithinRange(mob));
    }

    @Test
    void nearestTargetIncludesExact128BlockBoundary() {
        WorldLivingEntity mob = new WorldLivingEntityManager().spawn(
                "minecraft:zombie", 20, 0, 64, 0);
        var boundary = new LivingEntityAiService.PlayerTarget("boundary", 128, 64, 0, true);

        assertSame(boundary, new PlayerTargetIndex(List.of(boundary)).nearestWithinRange(mob));
    }

    @Test
    void indexedProvokerAndNearestTargetPreserveAiDecision() {
        WorldLivingEntity mob = new WorldLivingEntityManager().spawn(
                "minecraft:bee", 10, 0, 64, 0);
        var attacker = new LivingEntityAiService.PlayerTarget("attacker", 1, 64, 0, true);
        var bystander = new LivingEntityAiService.PlayerTarget("bystander", 1.5, 64, 0, true);
        List<LivingEntityAiService.PlayerTarget> players = List.of(attacker, bystander);
        PlayerTargetIndex index = new PlayerTargetIndex(players);
        LivingEntityAiService service = new LivingEntityAiService();

        var indexed = service.decide(mob, players, 2_000, 0, "attacker",
                index.nearestWithinRange(mob), index.find("attacker"));
        var reference = service.decide(mob, players, 2_000, 0, "attacker");

        assertEquals(reference, indexed);
        assertEquals("attacker", indexed.targetNetworkId());
    }
}
