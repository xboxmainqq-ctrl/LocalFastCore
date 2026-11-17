package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiRuntimeModulesTest {
    @Test
    void coreAssistantModuleStartsAndStopsWithoutAnExternalProvider() throws Exception {
        CoreAiAssistantModule module = new CoreAiAssistantModule(
                new AiToolRegistry(ignored -> { }), null);

        assertEquals("core-ai-assistant", module.id());
        assertTrue(module.assistant().isEmpty());
        module.start();
        assertTrue(module.assistant().isPresent());
        assertEquals(CoreAiAssistant.AnswerStatus.UNAVAILABLE,
                module.assistant().orElseThrow().ask("status", java.util.Map.of()).status());
        module.stop();
        assertTrue(module.assistant().isEmpty());
    }

    @Test
    void moderationAssistantModuleHasIndependentLifecycle() throws Exception {
        ModerationAiModule module = new ModerationAiModule(new ModerationSignalAnalyzer(), null);

        assertEquals("moderation-ai-assistant", module.id());
        module.start();
        assertTrue(module.assistant().isPresent());
        assertEquals(ModerationSignalAnalyzer.Risk.LOW,
                module.assistant().orElseThrow()
                        .reviewChat(java.util.UUID.randomUUID(), "hello", 1).risk());
        module.stop();
        assertTrue(module.assistant().isEmpty());
    }

    @Test
    void assistantModulesRejectDuplicateStart() throws Exception {
        CoreAiAssistantModule core = new CoreAiAssistantModule(
                new AiToolRegistry(ignored -> { }), null);
        core.start();
        assertThrows(IllegalStateException.class, core::start);
        core.stop();

        ModerationAiModule moderation = new ModerationAiModule(new ModerationSignalAnalyzer(), null);
        moderation.start();
        assertThrows(IllegalStateException.class, moderation::start);
        moderation.stop();
    }
}
