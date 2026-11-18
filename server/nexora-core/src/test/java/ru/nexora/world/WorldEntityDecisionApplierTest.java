package ru.nexora.world;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import ru.nexora.combat.LivingEntityAiService;

class WorldEntityDecisionApplierTest {

    @Test
    void appliesMovementButLeavesPlayerDamageAsOutputEvent() {
        WorldLivingEntityManager manager = new WorldLivingEntityManager();
        WorldLivingEntity mob = manager.spawn("minecraft:zombie", 20, 0, 64, 0);
        WorldEntityDecisionApplier applier = new WorldEntityDecisionApplier(manager);

        var result = applier.apply(new LivingEntityAiService.Decision(
                mob.runtimeId(), "player-1", 0.23, 0, 0, 3, true));

        assertTrue(result.moved());
        assertTrue(result.attacked());
        assertEquals(3, result.attackDamage());
        assertEquals(0, manager.find(mob.runtimeId()).orElseThrow().x(), 0.00001);
    }

    @Test
    void ignoresDeadOrMissingMob() {
        WorldLivingEntityManager manager = new WorldLivingEntityManager();
        WorldEntityDecisionApplier applier = new WorldEntityDecisionApplier(manager);

        var result = applier.apply(new LivingEntityAiService.Decision(
                12345, "player-1", 1, 0, 0, 3, true));

        assertFalse(result.moved());
        assertFalse(result.attacked());
    }

    @Test
    void movementIsValidatedByEntityManager() {
        WorldLivingEntityManager manager = new WorldLivingEntityManager();
        WorldLivingEntity mob = manager.spawn("minecraft:zombie", 20, 0, 64, 0);
        WorldEntityDecisionApplier applier = new WorldEntityDecisionApplier(manager);

        assertThrows(IllegalArgumentException.class, () -> applier.apply(
                new LivingEntityAiService.Decision(
                        mob.runtimeId(), null, Double.NaN, 0, 0, 0, false)));
    }
}
