package ru.nexora.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Human-in-the-loop moderation assistant.
 * Optional model summaries receive only bounded signal metadata, never raw chat.
 */
public final class ModerationAiAssistant {
    private static final String SYSTEM_INSTRUCTION = """
            You assist human moderators on the NEXORA server.
            Explain only the supplied signal metadata; do not infer guilt or certainty.
            Never recommend automatic punishment. A human moderator must review evidence.
            """;

    private final ModerationSignalAnalyzer analyzer;
    private final AiModelProvider provider;

    public ModerationAiAssistant(ModerationSignalAnalyzer analyzer) {
        this(analyzer, null);
    }

    public ModerationAiAssistant(ModerationSignalAnalyzer analyzer, AiModelProvider provider) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.provider = provider;
    }

    public ModerationSignalAnalyzer.Assessment reviewChat(UUID playerId, String message, long serverTick) {
        return analyzer.recordChatMessage(playerId, message, serverTick);
    }

    /** Optional explanatory summary; raw chat and player identifiers are never sent to the model. */
    public Summary explain(ModerationSignalAnalyzer.Assessment assessment) {
        Objects.requireNonNull(assessment, "assessment");
        if (provider == null) {
            return new Summary(SummaryStatus.UNAVAILABLE, "No AI model provider is configured");
        }
        try {
            Map<String, String> context = Map.of(
                    "risk", assessment.risk().name(),
                    "score", Integer.toString(assessment.score()),
                    "signals", String.join(",", assessment.reasons()),
                    "humanReviewRequired", Boolean.toString(assessment.humanReviewRequired())
            );
            AiModelProvider.Prompt prompt = new AiModelProvider.Prompt(
                    SYSTEM_INSTRUCTION,
                    "Explain the moderation signals and list what a human moderator should verify.",
                    context
            );
            return new Summary(SummaryStatus.SUCCESS, provider.complete(prompt).text());
        } catch (Exception failure) {
            return new Summary(SummaryStatus.FAILED,
                    "AI provider failed: " + failure.getClass().getSimpleName());
        }
    }

    public void forgetPlayer(UUID playerId) {
        analyzer.forgetPlayer(playerId);
    }

    public enum SummaryStatus {
        SUCCESS,
        UNAVAILABLE,
        FAILED
    }

    public record Summary(SummaryStatus status, String text) {
        public Summary {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(text, "text");
        }
    }
}
