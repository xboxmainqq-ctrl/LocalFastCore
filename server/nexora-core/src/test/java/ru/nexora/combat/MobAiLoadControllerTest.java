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
        assertTrue(controller.stats().totalDecisionNanos() > 0);
        assertTrue(controller.stats().lastDecisionNanos() >= 0);
        assertTrue(controller.stats().maxDecisionNanos() >= controller.stats().lastDecisionNanos());
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
    @Test
    void distributesTenThousandMobSchedulingDecisionsAcrossTwentyTicks() {
        MobAiLoadController controller = new MobAiLoadController(2);
        int mobCount = 10_000;
        int nearMobCount = 100;
        int targetedMobCount = 50;
        long observedFullRate = 0;
        long observedDeferred = 0;

        for (long tick = 0; tick < 20; tick++) {
            int updatedThisTick = 0;
            for (long runtimeId = 0; runtimeId < mobCount; runtimeId++) {
                boolean nearPlayer = runtimeId < nearMobCount;
                boolean hasTarget = runtimeId >= nearMobCount
                        && runtimeId < nearMobCount + targetedMobCount;
                if (controller.shouldUpdate(runtimeId, tick,
                        nearPlayer ? 8.0 : 10_000.0, hasTarget)) {
                    updatedThisTick++;
                }
            }
            observedFullRate += updatedThisTick;
            observedDeferred += mobCount - updatedThisTick;
        }

        long alwaysActiveMobs = (long) (nearMobCount + targetedMobCount) * 20;
        long staggeredMobs = (long) (mobCount - nearMobCount - targetedMobCount) * 10;
        assertEquals(alwaysActiveMobs + staggeredMobs, observedFullRate);
        assertEquals((long) (mobCount - nearMobCount - targetedMobCount) * 10,
                observedDeferred);
        assertEquals(observedFullRate, controller.stats().fullRateUpdates());
        assertEquals(observedDeferred, controller.stats().deferredUpdates());
        assertEquals(200_000, controller.stats().totalDecisions());
    }

    @Test
    void adaptiveDistanceTiersKeepNearMobsResponsiveAndThrottleFarMobs() {
        MobAiLoadController controller = new MobAiLoadController(4);

        assertEquals(1, controller.intervalForDistance(10 * 10));
        assertEquals(2, controller.intervalForDistance(30 * 30));
        assertEquals(3, controller.intervalForDistance(60 * 60));
        assertEquals(4, controller.intervalForDistance(200 * 200));

        assertEquals(MobAiLoadController.Priority.HIGH,
                controller.priority(10 * 10, false, false));
        assertEquals(MobAiLoadController.Priority.HIGH,
                controller.priority(200 * 200, true, false));
        assertEquals(MobAiLoadController.Priority.HIGH,
                controller.priority(200 * 200, false, true));
        assertEquals(MobAiLoadController.Priority.LOW,
                controller.priority(200 * 200, false, false));
    }

    @Test
    void lowPriorityTasksAreDeferredAfterBudgetIsConsumedButHighPriorityIsAdmitted() {
        MobAiLoadController controller = new MobAiLoadController(4);
        controller.setAiBudgetNanos(1_000);

        assertFalse(controller.admit(10, 1, 10_000, false,
                MobAiLoadController.Priority.LOW, 1_000));
        assertFalse(controller.admit(11, 1, 10_000, false,
                MobAiLoadController.Priority.NORMAL, 2_000));
        assertTrue(controller.admit(12, 1, 10_000, true,
                MobAiLoadController.Priority.HIGH, 3_000));
        assertEquals(2, controller.budgetDeferrals());
        assertEquals(0, controller.budgetNanosConsumed());
        controller.recordBudgetNanos(500);
        assertEquals(500, controller.budgetNanosConsumed());
    }

    @Test
    void budgetRejectsInvalidConfiguration() {
        MobAiLoadController controller = new MobAiLoadController();
        assertThrows(IllegalArgumentException.class, () -> controller.setAiBudgetNanos(-1));
        assertThrows(IllegalArgumentException.class, () -> controller.setAiBudgetNanos(50_000_001));
    }

}

