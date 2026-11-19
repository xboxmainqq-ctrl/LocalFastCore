package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskContractTest {
    @Test
    void criticalContractCannotBeAccidentallyDeferrable() {
        TaskContract contract = TaskContract.critical(AdaptiveRuntime.Lane.PLAYER, 100_000L);
        assertTrue(contract.critical());
        assertFalse(contract.deferrable());
        assertTrue(contract.orderingSensitive());
    }

    @Test
    void orderedContractCanWaitWithoutLosingOrdering() {
        TaskContract contract = TaskContract.ordered(AdaptiveRuntime.Lane.MAGIC, 200_000L);
        assertFalse(contract.critical());
        assertTrue(contract.deferrable());
        assertTrue(contract.orderingSensitive());
    }
}
