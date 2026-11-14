package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TransportCloseEventTest {
    @Test
    void recordsRemoteAndLocalCloseOrigins() {
        assertTrue(new TransportCloseEvent("peer disconnected", true).remote());
        assertFalse(new TransportCloseEvent("server shutdown", false).remote());
    }

    @Test
    void rejectsBlankDescriptions() {
        assertThrows(IllegalArgumentException.class,
                () -> new TransportCloseEvent(" ", false));
    }
}
