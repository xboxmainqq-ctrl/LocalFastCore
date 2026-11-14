package ru.nexora.core.event.player;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerLifecycleEventTest {
    @Test
    void joinEventExposesStablePlayerIdentity() {
        UUID id = UUID.randomUUID();
        PlayerJoinEvent event = new PlayerJoinEvent(id, "PlayerOne");

        assertEquals(id, event.getPlayerId());
        assertEquals("PlayerOne", event.getPlayerName());
    }

    @Test
    void quitEventExposesStablePlayerIdentity() {
        UUID id = UUID.randomUUID();
        PlayerQuitEvent event = new PlayerQuitEvent(id, "PlayerTwo");

        assertEquals(id, event.getPlayerId());
        assertEquals("PlayerTwo", event.getPlayerName());
    }
}
