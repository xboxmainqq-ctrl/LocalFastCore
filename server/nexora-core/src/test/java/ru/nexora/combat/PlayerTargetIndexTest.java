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
    void equidistantTargetsPreserveOriginalPlayerOrderAcrossCells() {
        WorldLivingEntity mob = new WorldLivingEntityManager().spawn(
                "minecraft:zombie", 20, 0, 64, 0);
        var first = new LivingEntityAiService.PlayerTarget("first", 10, 64, 0, true);
        var second = new LivingEntityAiService.PlayerTarget("second", -10, 64, 0, true);
        List<LivingEntityAiService.PlayerTarget> players = List.of(first, second);

        assertSame(first, new PlayerTargetIndex(players).nearestWithinRange(mob));
        assertSame(LivingEntityAiService.nearestAlivePlayer(mob, players),
                new PlayerTargetIndex(players).nearestWithinRange(mob));
    }

    @Test
    void spatialIndexMatchesBoundedLinearScanForLargePlayerSets() {
        List<LivingEntityAiService.PlayerTarget> players = new java.util.ArrayList<>();
        for (int i = 0; i < 48; i++) {
            double x = ((i * 73) % 1_100) - 550.0;
            double z = ((i * 137) % 1_300) - 650.0;
            players.add(new LivingEntityAiService.PlayerTarget(
                    "indexed-" + i, x, 64 + (i % 5), z, i % 11 != 0));
        }
        PlayerTargetIndex index = new PlayerTargetIndex(players);
        WorldLivingEntityManager manager = new WorldLivingEntityManager();

        for (int i = 0; i < 32; i++) {
            double x = ((i * 191) % 1_000) - 500.0;
            double z = ((i * 97) % 1_200) - 600.0;
            WorldLivingEntity mob = manager.spawn("minecraft:zombie", 20, x, 64, z);
            var expected = LivingEntityAiService.nearestAlivePlayer(mob, players);
            if (expected != null) {
                double dx = expected.x() - mob.x();
                double dy = expected.y() - mob.y();
                double dz = expected.z() - mob.z();
                if (dx * dx + dy * dy + dz * dz
                        > PlayerTargetIndex.SEARCH_RADIUS_SQUARED) {
                    expected = null;
                }
            }
            assertSame(expected, index.nearestWithinRange(mob),
                    "Indexed and linear results must match for mob " + i);
        }
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
