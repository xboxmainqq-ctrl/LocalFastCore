package ru.nexora.runtime;

import java.util.Objects;
import java.util.Optional;

/** Optional lifecycle-managed module for moderation assistance. */
public final class ModerationAiModule implements RuntimeModule {
    private final ModerationSignalAnalyzer analyzer;
    private final AiModelProvider provider;
    private ModerationAiAssistant assistant;

    public ModerationAiModule(ModerationSignalAnalyzer analyzer, AiModelProvider provider) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.provider = provider;
    }

    @Override
    public String id() {
        return "moderation-ai-assistant";
    }

    @Override
    public synchronized void start() {
        if (assistant != null) {
            throw new IllegalStateException("Moderation AI Assistant is already started");
        }
        assistant = new ModerationAiAssistant(analyzer, provider);
    }

    @Override
    public synchronized void stop() {
        assistant = null;
    }

    public synchronized Optional<ModerationAiAssistant> assistant() {
        return Optional.ofNullable(assistant);
    }
}
