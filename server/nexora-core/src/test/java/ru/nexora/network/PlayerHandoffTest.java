package ru.nexora.network;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerHandoffTest {
    @Test
    void payloadIsDefensivelyCopied() {
        byte[] source = new byte[] {1, 2, 3};
        PlayerHandoff handoff = new PlayerHandoff(UUID.randomUUID(), 42L, "node-a", "node-b", source);
        source[0] = 9;

        byte[] extracted = handoff.statePayload();
        assertArrayEquals(new byte[] {1, 2, 3}, extracted);
        extracted[1] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, handoff.statePayload());
    }

    @Test
    void rejectsSameSourceAndTarget() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerHandoff(UUID.randomUUID(), 1L, "node-a", "node-a", new byte[0]));
    }
}
