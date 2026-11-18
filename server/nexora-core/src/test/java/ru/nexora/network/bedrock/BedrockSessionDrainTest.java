package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class BedrockSessionDrainTest {
    @Test
    void reportsDrainImmediatelyWhenNoSessionsAreActive() throws Exception {
        BedrockSessionManager sessions = new BedrockSessionManager(
                2225, (networkId, channel, frame) -> {}, event -> {});

        assertTrue(sessions.awaitNoActiveSessions(Duration.ZERO));
        sessions.close();
    }

    @Test
    void rejectsNegativeDrainTimeout() {
        BedrockSessionManager sessions = new BedrockSessionManager(
                2225, (networkId, channel, frame) -> {}, event -> {});

        assertThrows(IllegalArgumentException.class,
                () -> sessions.awaitNoActiveSessions(Duration.ofNanos(-1)));
        sessions.close();
    }
}
