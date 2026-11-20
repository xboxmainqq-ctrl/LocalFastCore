package ru.nexora.plugins.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PositionTest {
    @Test
    void acceptsFiniteCoordinatesAndPreservesWorldIdentity() {
        Position position = new Position("overworld", -12.5, 64, 8.25, 90, 0);

        assertEquals("overworld", position.worldId());
        assertEquals(-12.5, position.x());
        assertEquals(64, position.y());
        assertEquals(8.25, position.z());
    }

    @Test
    void rejectsMissingWorldAndNonFiniteCoordinates() {
        assertThrows(NullPointerException.class,
                () -> new Position(null, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Position(" ", 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Position("overworld", Double.NaN, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Position("overworld", 0, Double.POSITIVE_INFINITY, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Position("overworld", 0, 0, 0, Float.NaN, 0));
    }
}
