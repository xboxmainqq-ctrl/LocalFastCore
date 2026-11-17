package ru.nexora.ai;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class NexoraAiAssistantTest {
    @Test void missingKeyDisablesAssistant() {
        assertFalse(NexoraAiAssistant.fromEnvironment(Map.of(), new NexoraAiDiagnostics()).isEnabled());
    }
    @Test void rejectsInsecureRemoteEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> new NexoraAiAssistant(
                "secret", "model", URI.create("http://example.com/chat"),
                Duration.ofSeconds(10), new NexoraAiDiagnostics()));
    }
    @Test void allowsHttpsAndLocalDevelopmentEndpoint() {
        assertDoesNotThrow(() -> new NexoraAiAssistant("secret", "model",
                URI.create("https://api.example.com/chat"), Duration.ofSeconds(10), new NexoraAiDiagnostics()));
        assertDoesNotThrow(() -> new NexoraAiAssistant("secret", "model",
                URI.create("http://localhost:8080/chat"), Duration.ofSeconds(10), new NexoraAiDiagnostics()));
    }
    @Test void rejectsExcessiveTimeout() {
        assertThrows(IllegalArgumentException.class, () -> new NexoraAiAssistant(
                "secret", "model", URI.create("https://api.example.com/chat"),
                Duration.ofMinutes(3), new NexoraAiDiagnostics()));
    }
}
