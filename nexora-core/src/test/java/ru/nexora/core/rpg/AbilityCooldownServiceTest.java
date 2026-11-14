package ru.nexora.core.rpg;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AbilityCooldownServiceTest {
    @Test
    void enforcesCooldownUntilReadyTick() {
        AbilityCooldownService cooldowns = new AbilityCooldownService();
        UUID player = UUID.randomUUID();

        assertTrue(cooldowns.tryStart(player, "fire_bolt", 100, 40));
        assertEquals(40, cooldowns.remainingTicks(player, "fire_bolt", 100));
        assertFalse(cooldowns.tryStart(player, "fire_bolt", 139, 40));
        assertTrue(cooldowns.tryStart(player, "fire_bolt", 140, 40));
        assertEquals(40, cooldowns.remainingTicks(player, "fire_bolt", 140));
    }

    @Test
    void tracksPlayersAndAbilitiesIndependently() {
        AbilityCooldownService cooldowns = new AbilityCooldownService();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(cooldowns.tryStart(first, "dash", 10, 20));
        assertTrue(cooldowns.tryStart(first, "shield", 10, 20));
        assertTrue(cooldowns.tryStart(second, "dash", 10, 20));
        assertEquals(3, cooldowns.activeCooldownCount());
        cooldowns.clearPlayer(first);
        assertEquals(1, cooldowns.activeCooldownCount());
        assertEquals(20, cooldowns.remainingTicks(second, "dash", 10));
    }

    @Test
    void handlesZeroCooldownAndRejectsInvalidTicks() {
        AbilityCooldownService cooldowns = new AbilityCooldownService();
        UUID player = UUID.randomUUID();

        assertTrue(cooldowns.tryStart(player, "blink", 0, 0));
        assertEquals(0, cooldowns.activeCooldownCount());
        assertThrows(IllegalArgumentException.class,
                () -> cooldowns.tryStart(player, "blink", -1, 5));
    }

    @Test
    void saturatesInsteadOfOverflowingTickArithmetic() {
        AbilityCooldownService cooldowns = new AbilityCooldownService();
        UUID player = UUID.randomUUID();

        assertTrue(cooldowns.tryStart(player, "ultimate", Long.MAX_VALUE - 2, 10));
        assertEquals(2, cooldowns.remainingTicks(player, "ultimate", Long.MAX_VALUE - 2));
        assertEquals(0, cooldowns.remainingTicks(player, "ultimate", Long.MAX_VALUE));
    }
}
