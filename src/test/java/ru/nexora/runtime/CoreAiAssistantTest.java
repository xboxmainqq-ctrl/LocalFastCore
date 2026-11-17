package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CoreAiAssistantTest {
    @Test
    void worksWithoutProviderAndReportsUnavailable() {
        CoreAiAssistant assistant = new CoreAiAssistant(new AiToolRegistry(ignored -> { }));
        CoreAiAssistant.Answer answer = assistant.ask("Why is tick rate low?", Map.of("tickRate", "12"));
        assertEquals(CoreAiAssistant.AnswerStatus.UNAVAILABLE, answer.status());
    }

    @Test
    void sendsBoundedSafeContextToOptionalProvider() {
        AtomicReference<AiModelProvider.Prompt> received = new AtomicReference<>();
        AiModelProvider provider = prompt -> {
            received.set(prompt);
            return new AiModelProvider.Completion("Tick rate is below target.");
        };
        CoreAiAssistant assistant = new CoreAiAssistant(new AiToolRegistry(ignored -> { }), provider);

        CoreAiAssistant.Answer answer = assistant.ask(
                "Explain server performance", Map.of("tickRate", "12", "loadedChunks", "84"));

        assertEquals(CoreAiAssistant.AnswerStatus.SUCCESS, answer.status());
        assertEquals("Tick rate is below target.", answer.text());
        assertEquals(Map.of("tickRate", "12", "loadedChunks", "84"), received.get().context());
    }

    @Test
    void rejectsSensitiveContextBeforeCallingProvider() {
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        CoreAiAssistant assistant = new CoreAiAssistant(
                new AiToolRegistry(ignored -> { }),
                prompt -> {
                    called.set(true);
                    return new AiModelProvider.Completion("ok");
                });

        CoreAiAssistant.Answer answer = assistant.ask(
                "Inspect server", Map.of("authToken", "do-not-send"));

        assertEquals(CoreAiAssistant.AnswerStatus.FAILED, answer.status());
        assertFalse(called.get());
    }

    @Test
    void providerOutputMustBeBoundedAndNonEmpty() {
        assertThrows(IllegalArgumentException.class, () -> new AiModelProvider.Completion(" "));
        assertThrows(IllegalArgumentException.class,
                () -> new AiModelProvider.Prompt("system", "question", Map.of("playerMessage", "private")));
        assertThrows(IllegalArgumentException.class,
                () -> new AiModelProvider.Prompt("system", "question", Map.of("x", "y".repeat(2049))));
    }
}
