package ru.nexora.runtime;

import java.util.Objects;
import java.util.UUID;

/** Human-in-the-loop facade for moderation signal review. */
public final class ModerationAiAssistant {
    private final ModerationSignalAnalyzer analyzer;

    public ModerationAiAssistant(ModerationSignalAnalyzer analyzer) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
    }

    public ModerationSignalAnalyzer.Assessment reviewChat(UUID playerId, String message, long serverTick) {
        return analyzer.recordChatMessage(playerId, message, serverTick);
    }

    public void forgetPlayer(UUID playerId) {
        analyzer.forgetPlayer(playerId);
    }
}
