package ru.nexora.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerVitalsTest {
    @Test
    void appliesDamageHealingAndDeathWithoutExceedingBounds() {
        PlayerVitals damaged = PlayerVitals.DEFAULT.damage(7.5f);
        assertEquals(12.5f, damaged.health());
        assertEquals(20.0f, damaged.heal(50).health());
        assertTrue(damaged.damage(50).dead());
    }

    @Test
    void consumesSaturationBeforeHungerWhenExhausted() {
        PlayerVitals start = new PlayerVitals(20, 20, 1, 0);
        PlayerVitals afterOne = start.addExhaustion(4);
        assertEquals(20, afterOne.hunger());
        assertEquals(0, afterOne.saturation());
        assertEquals(0, afterOne.exhaustion());

        PlayerVitals afterTwo = afterOne.addExhaustion(4);
        assertEquals(19, afterTwo.hunger());
        assertEquals(0, afterTwo.saturation());
    }

    @Test
    void foodRestoresHungerAndSaturationWithinCaps() {
        PlayerVitals hungry = new PlayerVitals(20, 8, 0, 1);
        PlayerVitals fed = hungry.feed(8, 0.6f);
        assertEquals(16, fed.hunger());
        assertEquals(9.6f, fed.saturation(), 0.0001f);
        assertEquals(1, fed.exhaustion());
        assertEquals(20, fed.feed(20, 2).hunger());
    }

    @Test
    void rejectsInvalidVitalsAndFoodOrExhaustionInputs() {
        assertThrows(IllegalArgumentException.class, () -> new PlayerVitals(21, 20, 5, 0));
        assertThrows(IllegalArgumentException.class, () -> new PlayerVitals(20, 2, 3, 0));
        assertThrows(IllegalArgumentException.class, () -> PlayerVitals.DEFAULT.feed(0, 1));
        assertThrows(IllegalArgumentException.class, () -> PlayerVitals.DEFAULT.addExhaustion(Float.NaN));
    }
}
