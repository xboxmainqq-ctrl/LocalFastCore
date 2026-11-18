package ru.nexora.gameplay;

import org.junit.jupiter.api.Test;
import ru.nexora.ai.NexoraAiDiagnostics;
import ru.nexora.network.bedrock.BedrockSessionManager;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GameplayRuntimeModuleTest {
    @Test
    void configuresAndOwnsBedrockGameplaySessions() {
        BedrockSessionManager sessions = new BedrockSessionManager(
                2225, (networkId, channel, frame) -> {}, event -> {});
        GameplayRuntimeModule module = new GameplayRuntimeModule(
                sessions, new NexoraAiDiagnostics(), Map.of());

        assertSame(sessions, module.sessions());
        assertNotNull(module.assistant());
        assertFalse(module.isClosed());

        module.close();
        module.close();

        assertTrue(module.isClosed());
        assertThrows(IllegalStateException.class, module::sessions);
    }

    @Test
    void rejectsMissingDependencies() {
        assertThrows(NullPointerException.class, () -> new GameplayRuntimeModule(
                null, new NexoraAiDiagnostics(), Map.of()));
        BedrockSessionManager sessions = new BedrockSessionManager(
                2225, (networkId, channel, frame) -> {}, event -> {});
        assertThrows(NullPointerException.class, () -> new GameplayRuntimeModule(
                sessions, null, Map.of()));
    }
}
