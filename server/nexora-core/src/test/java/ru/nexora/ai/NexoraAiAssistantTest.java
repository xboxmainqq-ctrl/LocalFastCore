package ru.nexora.ai;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class NexoraAiAssistantTest {
    @Test
    void playerOutputGuardDetectsInternalCoreAndInfrastructureDisclosure() {
        assertTrue(NexoraAiAssistant.containsConfidentialInfrastructureDisclosure(
                "NEXORA использует собственную архитектуру ядра."));
        assertTrue(NexoraAiAssistant.containsConfidentialInfrastructureDisclosure(
                "У сервера 8 потоков JVM и 4 CPU."));
        assertTrue(NexoraAiAssistant.containsConfidentialInfrastructureDisclosure(
                "Внутренние модули и исходный код ядра закрыты."));
    }

    @Test
    void playerOutputGuardBlocksPluginAndCoreImplementationDetailsButAllowsPublicUsage() {
        assertTrue(NexoraAiAssistant.containsConfidentialInfrastructureDisclosure(
                "Исходный код плагина хранится во внутреннем модуле."));
        assertTrue(NexoraAiAssistant.containsConfidentialInfrastructureDisclosure(
                "Архитектура ядра и его API закрыты."));
        assertFalse(NexoraAiAssistant.containsConfidentialInfrastructureDisclosure(
                "Плагин добавляет новые игровые задания и команду /quests."));
    }

    @Test
    void playerOutputGuardAllowsOrdinaryGameplayHelp() {
        assertFalse(NexoraAiAssistant.containsConfidentialInfrastructureDisclosure(
                "Чтобы скрафтить факел, поставь уголь над палкой."));
        assertFalse(NexoraAiAssistant.containsConfidentialInfrastructureDisclosure(
                "Зомби появляется ночью и боится солнечного света."));
    }

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

    @Test
    void operatorCanReviewProposalStoreWithoutConfiguredProvider() {
        NexoraAiAssistant assistant = NexoraAiAssistant.fromEnvironment(
                Map.of("NEXORA_AI_ADMIN_XUIDS", "operator-xuid"), new NexoraAiDiagnostics());

        assertFalse(assistant.isEnabled());
        assertTrue(assistant.repairProposals("operator-xuid").isEmpty());
        assertTrue(assistant.repairProposal("operator-xuid", "R-000001").isEmpty());
        assertTrue(assistant.approveRepairProposal("operator-xuid", "R-000001").isEmpty());
        assertTrue(assistant.rejectRepairProposal("operator-xuid", "R-000001").isEmpty());
    }

    @Test
    void operatorStatusIncludesRecentDiagnosticEvidence() {
        NexoraAiDiagnostics diagnostics = new NexoraAiDiagnostics();
        diagnostics.recordFailure(new IllegalStateException("world autosave failed"));
        NexoraAiAssistant assistant = new NexoraAiAssistant(
                "", "gpt-4o-mini", java.net.URI.create("https://api.openai.com/v1/chat/completions"),
                java.time.Duration.ofSeconds(30), diagnostics);

        String status = assistant.adminStatus();

        assertTrue(status.contains("Недавние события"));
        assertTrue(status.contains("[FAILURE] IllegalStateException: world autosave failed"));
    }

}
