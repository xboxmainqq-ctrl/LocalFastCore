package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BedrockProtocolVersionTest {
    @Test
    void matchesThePinnedOfficialGameDataBuild() {
        assertEquals(2225, BedrockProtocolVersion.PROTOCOL);
        assertEquals("1.26.60", BedrockProtocolVersion.BASE_GAME_VERSION);
    }
}
