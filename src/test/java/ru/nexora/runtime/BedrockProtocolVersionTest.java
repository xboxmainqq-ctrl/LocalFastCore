package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BedrockProtocolVersionTest {
    @Test
    void pinsTheSelectedStableRelease() {
        assertEquals("1.26.60", BedrockProtocolVersion.GAME_VERSION);
        assertEquals(2225, BedrockProtocolVersion.PROTOCOL_VERSION);
    }

    @Test
    void acceptsOnlyThePinnedProtocol() {
        assertTrue(BedrockProtocolVersion.supportsProtocol(2225));
        assertFalse(BedrockProtocolVersion.supportsProtocol(2193));
        assertFalse(BedrockProtocolVersion.supportsProtocol(2235));
    }
}
