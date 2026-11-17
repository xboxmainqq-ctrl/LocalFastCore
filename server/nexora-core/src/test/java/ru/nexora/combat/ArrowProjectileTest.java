package ru.nexora.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArrowProjectileTest {
    @Test
    void advancesPositionAppliesGravityAndDrag() {
        ArrowProjectile arrow = new ArrowProjectile(-1, 2, "mob", 1, 2, 3, 1, 0.5, 0, 0);
        ArrowProjectile next = arrow.advance();
        assertEquals(2.0, next.x(), 0.000001);
        assertEquals(2.5, next.y(), 0.000001);
        assertEquals(0.99, next.velocityX(), 0.000001);
        assertEquals(0.4455, next.velocityY(), 0.000001);
        assertEquals(1, next.ageTicks());
    }

    @Test
    void expiresAfterTwentySecondsOfTwentyTickUpdates() {
        ArrowProjectile arrow = new ArrowProjectile(-1, 2, "mob", 1, 2, 3, 0, 0, 1, 0);
        for (int i = 0; i < 400; i++) arrow = arrow.advance();
        assertTrue(arrow.expired());
    }

    @Test
    void rejectsInvalidProjectileData() {
        assertThrows(IllegalArgumentException.class,
                () -> new ArrowProjectile(-1, 2, "mob", 0, 0, 0, Double.NaN, 0, 0, 0));
    }
}
