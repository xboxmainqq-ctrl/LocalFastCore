package ru.nexora.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class NexoraAiConversationMemoryTest {
    @Test
    void keepsOnlyCompletedExchangesForTheSamePlayer() {
        AtomicLong clock = new AtomicLong();
        NexoraAiConversationMemory memory = new NexoraAiConversationMemory(
                clock::get, Duration.ofMinutes(15), 2, 10);

        exchange(memory, "player-1", false, "Привет", "Здравствуй");
        exchange(memory, "player-1", false, "Как дела?", "Хорошо");
        exchange(memory, "player-1", false, "Третий вопрос", "Третий ответ");

        List<NexoraAiConversationMemory.Message> history = memory.history("player-1", false).messages();
        assertEquals(4, history.size());
        assertEquals("Как дела?", history.get(0).content());
        assertEquals("Хорошо", history.get(1).content());
        assertEquals("Третий вопрос", history.get(2).content());
        assertEquals("Третий ответ", history.get(3).content());
    }

    @Test
    void isolatesPlayersAndOperatorContext() {
        NexoraAiConversationMemory memory = new NexoraAiConversationMemory();
        exchange(memory, "same-id", false, "player question", "player answer");
        exchange(memory, "same-id", true, "admin question", "admin answer");

        assertEquals("player question", memory.history("same-id", false).messages().get(0).content());
        assertEquals("admin question", memory.history("same-id", true).messages().get(0).content());
        assertTrue(memory.history("another-player", false).messages().isEmpty());
    }

    @Test
    void expiresInactiveConversation() {
        AtomicLong clock = new AtomicLong();
        NexoraAiConversationMemory memory = new NexoraAiConversationMemory(
                clock::get, Duration.ofSeconds(10), 4, 10);
        exchange(memory, "player-1", false, "question", "answer");

        clock.set(Duration.ofSeconds(11).toNanos());

        assertTrue(memory.history("player-1", false).messages().isEmpty());
    }

    @Test
    void rejectsInvalidOrOversizedExchanges() {
        NexoraAiConversationMemory memory = new NexoraAiConversationMemory();
        NexoraAiConversationMemory.History history = memory.history("player", false);
        assertThrows(IllegalArgumentException.class,
                () -> memory.recordExchange(" ", false, history, "question", "answer"));
        assertThrows(IllegalArgumentException.class,
                () -> memory.recordExchange("player", false, history, "x".repeat(801), "answer"));
        assertThrows(IllegalArgumentException.class,
                () -> memory.recordExchange("player", false, history, "question", "x".repeat(1201)));
    }

    @Test
    void clearRemovesOnlyTheSelectedConversation() {
        NexoraAiConversationMemory memory = new NexoraAiConversationMemory();
        exchange(memory, "player-1", false, "player question", "player answer");
        exchange(memory, "player-1", true, "operator question", "operator answer");

        memory.clear("player-1", false);

        assertTrue(memory.history("player-1", false).messages().isEmpty());
        assertEquals("operator question", memory.history("player-1", true).messages().get(0).content());
    }

    @Test
    void pendingReplyCannotRestoreContextAfterPlayerClearsIt() {
        NexoraAiConversationMemory memory = new NexoraAiConversationMemory();
        NexoraAiConversationMemory.History pending = memory.history("player-1", false);

        memory.clear("player-1", false);
        memory.recordExchange("player-1", false, pending, "old question", "late reply");

        assertTrue(memory.history("player-1", false).messages().isEmpty());
    }

    private static void exchange(
            NexoraAiConversationMemory memory, String playerId, boolean admin,
            String userMessage, String assistantMessage
    ) {
        NexoraAiConversationMemory.History history = memory.history(playerId, admin);
        memory.recordExchange(playerId, admin, history, userMessage, assistantMessage);
    }
}
