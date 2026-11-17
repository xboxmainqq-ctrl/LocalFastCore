package ru.nexora.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Provider SPI for optional language-model integrations.
 * Implementations are supplied by trusted extensions; the Core has no
 * mandatory external AI dependency.
 */
@FunctionalInterface
public interface AiModelProvider {
    Completion complete(Prompt prompt) throws Exception;

    record Prompt(String instruction, String question, Map<String, String> context) {
        public Prompt {
            Objects.requireNonNull(instruction, "instruction");
            Objects.requireNonNull(question, "question");
            Objects.requireNonNull(context, "context");
            if (instruction.isBlank() || instruction.length() > 8_000
                    || question.isBlank() || question.length() > 4_000 || context.size() > 32) {
                throw new IllegalArgumentException("AI prompt exceeds configured bounds");
            }
            Map<String, String> safe = new TreeMap<>();
            context.forEach((key, value) -> {
                Objects.requireNonNull(key, "context key");
                Objects.requireNonNull(value, "context value");
                String normalized = key.toLowerCase(java.util.Locale.ROOT);
                if (normalized.contains("password") || normalized.contains("secret")
                        || normalized.contains("token") || normalized.contains("private")
                        || normalized.contains("ip") || normalized.contains("chat")
                        || normalized.contains("message")) {
                    throw new IllegalArgumentException("Sensitive context key is not allowed: " + key);
                }
                if (!key.matches("[A-Za-z0-9._-]{1,64}") || value.length() > 2_048) {
                    throw new IllegalArgumentException("Invalid AI context entry");
                }
                safe.put(key, value);
            });
            context = Map.copyOf(safe);
        }
    }

    record Completion(String text) {
        public Completion {
            Objects.requireNonNull(text, "text");
            if (text.isBlank() || text.length() > 8_000) {
                throw new IllegalArgumentException("AI completion is empty or exceeds the output limit");
            }
        }
    }
}
