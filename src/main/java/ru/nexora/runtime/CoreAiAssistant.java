package ru.nexora.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Operator-facing facade for the optional Core AI Assistant.
 * Model/provider integration is optional; every proposed tool call still
 * passes through the permission and approval boundary.
 */
public final class CoreAiAssistant {
    private static final String SYSTEM_INSTRUCTION = """
            You are the NEXORA server operations assistant.
            Explain diagnostics and suggest safe next steps.
            Never request secrets or private player messages.
            Never claim to have changed server state. Tool execution is separate,
            permission checked, audited, and requires explicit approval for mutations.
            """;

    private final AiToolRegistry tools;
    private final AiModelProvider provider;

    /** Creates a diagnostics/tool facade that remains usable without a model provider. */
    public CoreAiAssistant(AiToolRegistry tools) {
        this(tools, null);
    }

    public CoreAiAssistant(AiToolRegistry tools, AiModelProvider provider) {
        this.tools = Objects.requireNonNull(tools, "tools");
        this.provider = provider;
    }

    public AiToolRegistry.ToolResult invoke(
            AiToolRegistry.Principal operator,
            String toolId,
            Map<String, String> arguments,
            boolean explicitlyApproved
    ) {
        return tools.execute(operator, toolId, arguments, explicitlyApproved);
    }

    /** Sends only explicitly supplied, bounded, non-sensitive context to the configured provider. */
    public Answer ask(String question, Map<String, String> diagnosticContext) {
        if (provider == null) {
            return new Answer(AnswerStatus.UNAVAILABLE, "No AI model provider is configured");
        }
        try {
            AiModelProvider.Prompt prompt = new AiModelProvider.Prompt(
                    SYSTEM_INSTRUCTION, question, diagnosticContext);
            AiModelProvider.Completion completion = provider.complete(prompt);
            return new Answer(AnswerStatus.SUCCESS, completion.text());
        } catch (Exception failure) {
            return new Answer(AnswerStatus.FAILED,
                    "AI provider failed: " + failure.getClass().getSimpleName());
        }
    }

    public java.util.Set<String> availableTools() {
        return tools.toolIds();
    }

    public enum AnswerStatus {
        SUCCESS,
        UNAVAILABLE,
        FAILED
    }

    public record Answer(AnswerStatus status, String text) {
        public Answer {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(text, "text");
        }
    }
}
