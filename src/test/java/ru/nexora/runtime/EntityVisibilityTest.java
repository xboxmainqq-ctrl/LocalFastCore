package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EntityVisibilityTest {
    @Test
    void includesEntitiesExactlyOnVisibilityBoundary() {
        var first = new NexoraMovementValidator.Position(0, 64, 0);
        var second = new NexoraMovementValidator.Position(96, 64, 0);

        assertTrue(EntityVisibility.isWithin(first, second, 96));
        assertFalse(EntityVisibility.isWithin(first, second, 95.999));
    }

    @Test
    void measuresThreeDimensionalDistance() {
        var first = new NexoraMovementValidator.Position(0, 0, 0);
        var second = new NexoraMovementValidator.Position(0, 3, 4);

        assertTrue(EntityVisibility.isWithin(first, second, 5));
        assertFalse(EntityVisibility.isWithin(first, second, 4.99));
    }

    @Test
    void rejectsInvalidRadiusAndNullPositions() {
        var position = new NexoraMovementValidator.Position(0, 0, 0);

        assertThrows(IllegalArgumentException.class,
                () -> EntityVisibility.isWithin(position, position, -1));
        assertThrows(IllegalArgumentException.class,
                () -> EntityVisibility.isWithin(position, position, Double.NaN));
        assertThrows(NullPointerException.class,
                () -> EntityVisibility.isWithin(null, position, 1));
    }
}
