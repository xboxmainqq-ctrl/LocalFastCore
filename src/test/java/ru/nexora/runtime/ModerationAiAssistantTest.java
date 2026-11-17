package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ModerationAiAssistantTest {
    @Test
    void repeatedMessagesProduceAdvisoryHighRiskAssessment() {
        ModerationAiAssistant assistant = new ModerationAiAssistant(
                new ModerationSignalAnalyzer(8, 3, 100, 32));
        UUID playerId = UUID.randomUUID();

        assistant.reviewChat(playerId, "buy coins now", 1);
        assistant.reviewChat(playerId, "buy coins now", 2);
        ModerationSignalAnalyzer.Assessment assessment =
                assistant.reviewChat(playerId, "buy coins now", 3);

        assertEquals(ModerationSignalAnalyzer.Risk.HIGH, assessment.risk());
        assertEquals(85, assessment.score());
        assertTrue(assessment.reasons().contains("REPEATED_IDENTICAL_MESSAGES"));
        assertTrue(assessment.humanReviewRequired());
    }

    @Test
    void highMessageRateIsFlaggedWithoutStoringRawContent() {
        ModerationSignalAnalyzer analyzer = new ModerationSignalAnalyzer(3, 3, 20, 8);
        UUID playerId = UUID.randomUUID();

        analyzer.recordChatMessage(playerId, "first", 1);
        analyzer.recordChatMessage(playerId, "second", 2);
        ModerationSignalAnalyzer.Assessment assessment =
                analyzer.recordChatMessage(playerId, "third", 3);

        assertEquals(ModerationSignalAnalyzer.Risk.MEDIUM, assessment.risk());
        assertTrue(assessment.reasons().contains("HIGH_MESSAGE_RATE"));
        assertTrue(assessment.humanReviewRequired());
    }

    @Test
    void oldSignalsExpireAndPlayerDataCanBeForgotten() {
        ModerationSignalAnalyzer analyzer = new ModerationSignalAnalyzer(8, 3, 5, 16);
        UUID playerId = UUID.randomUUID();

        analyzer.recordChatMessage(playerId, "same", 1);
        analyzer.recordChatMessage(playerId, "same", 2);
        ModerationSignalAnalyzer.Assessment afterWindow =
                analyzer.recordChatMessage(playerId, "same", 10);

        assertEquals(ModerationSignalAnalyzer.Risk.LOW, afterWindow.risk());
        analyzer.forgetPlayer(playerId);
        assertDoesNotThrow(() -> analyzer.recordChatMessage(playerId, "fresh", 11));
    }

    @Test
    void rejectsUnorderedOrOversizedEventsAndInvalidLimits() {
        ModerationSignalAnalyzer analyzer = new ModerationSignalAnalyzer();
        UUID playerId = UUID.randomUUID();
        analyzer.recordChatMessage(playerId, "hello", 10);
        assertThrows(IllegalArgumentException.class,
                () -> analyzer.recordChatMessage(playerId, "older", 9));
        assertThrows(IllegalArgumentException.class,
                () -> analyzer.recordChatMessage(playerId, "x".repeat(513), 11));
        assertThrows(IllegalArgumentException.class,
                () -> new ModerationSignalAnalyzer(1, 1, 0, 0));
    }
}
