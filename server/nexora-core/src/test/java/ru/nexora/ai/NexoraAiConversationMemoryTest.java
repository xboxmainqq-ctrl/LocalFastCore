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

        memory.recordExchange("player-1", false, "Привет", "Здравствуй");
        memory.recordExchange("player-1", false, "Как дела?", "Хорошо");
        memory.recordExchange("player-1", false, "Третий вопрос", "Третий ответ");

        List<NexoraAiConversationMemory.Message> history = memory.history("player-1", false);
        assertEquals(4, history.size());
        assertEquals("Как дела?", history.get(0).content());
        assertEquals("Хорошо", history.get(1).content());
        assertEquals("Третий вопрос", history.get(2).content());
        assertEquals("Третий ответ", history.get(3).content());
    }

    @Test
    void isolatesPlayersAndOperatorContext() {
        NexoraAiConversationMemory memory = new NexoraAiConversationMemory();
        memory.recordExchange("same-id", false, "player question", "player answer");
        memory.recordExchange("same-id", true, "admin question", "admin answer");

        assertEquals("player question", memory.history("same-id", false).get(0).content());
        assertEquals("admin question", memory.history("same-id", true).get(0).content());
        assertTrue(memory.history("another-player", false).isEmpty());
    }

    @Test
    void expiresInactiveConversation() {
        AtomicLong clock = new AtomicLong();
        NexoraAiConversationMemory memory = new NexoraAiConversationMemory(
                clock::get, Duration.ofSeconds(10), 4, 10);
        memory.recordExchange("player-1", false, "question", "answer");

        clock.set(Duration.ofSeconds(11).toNanos());

        assertTrue(memory.history("player-1", false).isEmpty());
    }

    @Test
    void rejectsInvalidOrOversizedExchanges() {
        NexoraAiConversationMemory memory = new NexoraAiConversationMemory();
        assertThrows(IllegalArgumentException.class,
                () -> memory.recordExchange(" ", false, "question", "answer"));
        assertThrows(IllegalArgumentException.class,
                () -> memory.recordExchange("player", false, "x".repeat(801), "answer"));
        assertThrows(IllegalArgumentException.class,
                () -> memory.recordExchange("player", false, "question", "x".repeat(1201)));
    }
    @Test
    void clearRemovesOnlyTheSelectedConversation() {
        NexoraAiConversationMemory memory = new NexoraAiConversationMemory();
        memory.recordExchange("player-1", false, "player question", "player answer");
        memory.recordExchange("player-1", true, "operator question", "operator answer");

        memory.clear("player-1", false);

        assertTrue(memory.history("player-1", false).isEmpty());
        assertEquals("operator question", memory.history("player-1", true).get(0).content());
    }

}
