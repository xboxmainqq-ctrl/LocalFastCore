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

    @Test void adminAccessRequiresAnExactConfiguredXuid() {
        NexoraAiAssistant assistant = NexoraAiAssistant.fromEnvironment(
                Map.of("NEXORA_AI_ADMIN_XUIDS", "111, 222"), new NexoraAiDiagnostics());
        assertTrue(assistant.isAdmin("111"));
        assertTrue(assistant.isAdmin("222"));
        assertFalse(assistant.isAdmin("333"));
        assertFalse(assistant.isAdmin(null));
    }

    @Test void invalidOptionalConfigurationDisablesAiInsteadOfBreakingStartup() {
        NexoraAiDiagnostics diagnostics = new NexoraAiDiagnostics();
        NexoraAiAssistant assistant = NexoraAiAssistant.fromEnvironment(
                Map.of("NEXORA_AI_ENDPOINT", "http://remote.example/chat"), diagnostics);
        assertFalse(assistant.isEnabled());
        assertEquals(1, diagnostics.inspect().recordedFailures());
    }

    @Test void rejectsExcessiveTimeout() {
        assertThrows(IllegalArgumentException.class, () -> new NexoraAiAssistant(
                "secret", "model", URI.create("https://api.example.com/chat"),
                Duration.ofMinutes(3), new NexoraAiDiagnostics()));
    }
    @Test
    void adminStatusIncludesReadOnlyWorldSummary() {
        NexoraAiAssistant assistant = NexoraAiAssistant.fromEnvironment(
                Map.of(), new NexoraAiDiagnostics());
        assistant.setAdminRuntimeSnapshotProvider(() -> "players=2, mobs=7, droppedItems=3");

        assertTrue(assistant.adminStatus().contains("мир: players=2, mobs=7, droppedItems=3"));
    }

    @Test
    void unavailableWorldSummaryDoesNotBreakAdminDiagnostics() {
        NexoraAiAssistant assistant = NexoraAiAssistant.fromEnvironment(
                Map.of(), new NexoraAiDiagnostics());
        assistant.setAdminRuntimeSnapshotProvider(() -> {
            throw new IllegalStateException("snapshot unavailable");
        });

        assertTrue(assistant.adminStatus().contains("мир: unavailable"));
    }

    @Test
    void repairProposalActionsRequireAnAllowlistedOperator() {
        NexoraAiAssistant assistant = NexoraAiAssistant.fromEnvironment(
                Map.of("NEXORA_AI_ADMIN_XUIDS", "operator-xuid"), new NexoraAiDiagnostics());

        assertThrows(SecurityException.class, () -> assistant.repairProposals("player-xuid"));
        assertThrows(SecurityException.class, () -> assistant.repairProposal("player-xuid", "R-000001"));
        assertThrows(SecurityException.class, () -> assistant.approveRepairProposal("player-xuid", "R-000001"));
        assertThrows(SecurityException.class, () -> assistant.rejectRepairProposal("player-xuid", "R-000001"));
        assertDoesNotThrow(() -> assistant.repairProposals("operator-xuid"));
    }

    @Test
    void repairPlanCreationRejectsNonOperatorBeforeProviderUse() {
        NexoraAiAssistant assistant = NexoraAiAssistant.fromEnvironment(
                Map.of("NEXORA_AI_ADMIN_XUIDS", "operator-xuid"), new NexoraAiDiagnostics());

        assertThrows(SecurityException.class, () ->
                assistant.createRepairProposal("player-xuid", "Player", "Server is slow", ignored -> {}));
    }

}
