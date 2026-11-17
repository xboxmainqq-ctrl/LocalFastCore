package ru.nexora.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MobAiLoadControllerTest {
    @Test
    void mobsNearPlayersAlwaysUpdate() {
        MobAiLoadController controller = new MobAiLoadController(2);

        for (long tick = 0; tick < 10; tick++) {
            assertTrue(controller.shouldUpdate(1, tick, 10.0, false));
        }
        assertEquals(10, controller.stats().fullRateUpdates());
        assertEquals(0, controller.stats().deferredUpdates());
    }

    @Test
    void mobsWithActiveTargetsAlwaysUpdateEvenWhenFarAway() {
        MobAiLoadController controller = new MobAiLoadController(2);

        for (long tick = 0; tick < 10; tick++) {
            assertTrue(controller.shouldUpdate(1, tick, 10_000.0, true));
        }
        assertEquals(10, controller.stats().fullRateUpdates());
    }

    @Test
    void distantIdleMobsAreDeterministicallyDistributedAcrossTicks() {
        MobAiLoadController controller = new MobAiLoadController(2);
        int updatedOnEvenTick = 0;
        int updatedOnOddTick = 0;
        for (long id = 0; id < 100; id++) {
            if (controller.shouldUpdate(id, 0, 10_000.0, false)) updatedOnEvenTick++;
            if (controller.shouldUpdate(id, 1, 10_000.0, false)) updatedOnOddTick++;
        }

        assertEquals(50, updatedOnEvenTick);
        assertEquals(50, updatedOnOddTick);
        assertEquals(100, controller.stats().fullRateUpdates());
        assertEquals(100, controller.stats().deferredUpdates());
        assertEquals(200, controller.stats().totalDecisions());
    }

    @Test
    void intervalOneKeepsEveryMobAtFullRate() {
        MobAiLoadController controller = new MobAiLoadController(1);
        assertTrue(controller.shouldUpdate(3, 0, 1_000, false));
        assertTrue(controller.shouldUpdate(3, 1, 1_000, false));
    }

    @Test
    void rejectsInvalidIntervalsAndObservations() {
        assertThrows(IllegalArgumentException.class, () -> new MobAiLoadController(0));
        assertThrows(IllegalArgumentException.class, () -> new MobAiLoadController(21));
        MobAiLoadController controller = new MobAiLoadController();
        assertThrows(IllegalArgumentException.class, () -> controller.shouldUpdate(-1, 0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> controller.shouldUpdate(1, -1, 0, false));
        assertThrows(IllegalArgumentException.class, () -> controller.shouldUpdate(1, 0, Double.NaN, false));
    }
}
