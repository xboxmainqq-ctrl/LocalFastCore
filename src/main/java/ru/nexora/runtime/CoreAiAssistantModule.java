package ru.nexora.runtime;

import java.util.Objects;
import java.util.Optional;

/** Optional lifecycle-managed module for the Core AI Assistant. */
public final class CoreAiAssistantModule implements RuntimeModule {
    private final AiToolRegistry tools;
    private final AiModelProvider provider;
    private CoreAiAssistant assistant;

    public CoreAiAssistantModule(AiToolRegistry tools, AiModelProvider provider) {
        this.tools = Objects.requireNonNull(tools, "tools");
        this.provider = provider;
    }

    @Override
    public String id() {
        return "core-ai-assistant";
    }

    @Override
    public synchronized void start() {
        if (assistant != null) {
            throw new IllegalStateException("Core AI Assistant is already started");
        }
        assistant = new CoreAiAssistant(tools, provider);
    }

    @Override
    public synchronized void stop() {
        assistant = null;
    }

    public synchronized Optional<CoreAiAssistant> assistant() {
        return Optional.ofNullable(assistant);
    }
}
