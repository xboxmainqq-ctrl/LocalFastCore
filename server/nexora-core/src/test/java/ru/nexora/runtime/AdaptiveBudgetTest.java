package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdaptiveBudgetTest {
    @Test
    void backgroundBudgetShrinksUnderPressure() {
        AdaptiveBudget budget = new AdaptiveBudget(1_000_000L);
        long before = budget.budget(AdaptiveRuntime.Lane.BACKGROUND);
        budget.rebalance(0.95);
        assertTrue(budget.budget(AdaptiveRuntime.Lane.BACKGROUND) < before);
    }

    @Test
    void playerBudgetKeepsMovingWithinBoundaries() {
        AdaptiveBudget budget = new AdaptiveBudget(1_000_000L);
        long before = budget.budget(AdaptiveRuntime.Lane.PLAYER);
        budget.rebalance(0.95);
        assertTrue(budget.budget(AdaptiveRuntime.Lane.PLAYER) > 0);
        assertTrue(budget.budget(AdaptiveRuntime.Lane.PLAYER) <= before);
    }
}
