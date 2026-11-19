package ru.nexora.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FireballProjectileTest {
    @Test
    void fireballMovesWithoutArrowGravityAndRetainsItsKindAndImpact() {
        FireballProjectile ghast = new FireballProjectile(
                -4_000_001L, 4_000_001L, "mob:42", "minecraft:fireball",
                1.0, 65.0, 2.0, 1.5, 0.25, -0.5, 0, 6.0f, 5);

        FireballProjectile next = ghast.advance();

        assertEquals(2.5, next.x(), 0.0001);
        assertEquals(65.25, next.y(), 0.0001);
        assertEquals(1.5, next.z(), 0.0001);
        assertEquals(1.485, next.velocityX(), 0.0001);
        assertEquals("minecraft:fireball", next.identifier());
        assertEquals(6.0f, next.damage());
        assertEquals(5, next.fireDurationSeconds());
        assertEquals(1, next.ageTicks());
    }

    @Test
    void expiresAtBoundedLifetimeAndRejectsInvalidState() {
        FireballProjectile fireball = new FireballProjectile(
                -4_000_002L, 4_000_002L, "mob:7", "minecraft:small_fireball",
                0, 64, 0, 1, 0, 0, FireballProjectile.MAX_AGE_TICKS, 4, 3);
        assertTrue(fireball.expired());
        assertThrows(IllegalArgumentException.class, () -> new FireballProjectile(
                -4_000_003L, 4_000_003L, "mob:7", "minecraft:small_fireball",
                0, 64, 0, Double.NaN, 0, 0, 0, 4, 3));
        assertThrows(IllegalArgumentException.class, () -> new FireballProjectile(
                -4_000_004L, 4_000_004L, "mob:7", "minecraft:fireball",
                0, 64, 0, 1, 0, 0, 0, 4, 30));
    }
}
