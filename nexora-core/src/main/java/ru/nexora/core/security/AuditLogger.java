package ru.nexora.core.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;

/**
 * Small append-only JSON-lines audit log.
 * Callers must not put passwords, tokens, or other secrets in event details.
 */
public final class AuditLogger {
    private static final int MAX_FIELD_LENGTH = 2048;
    private final Path logFile;

    public AuditLogger(Path logFile) {
        this.logFile = Objects.requireNonNull(logFile, "logFile").toAbsolutePath().normalize();
    }

    public synchronized void record(String event, String actor, String details) throws IOException {
        String safeEvent = validate(event, "event");
        String safeActor = validate(actor, "actor");
        String safeDetails = validate(details, "details");

        Path parent = logFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        String line = "{\"timestamp\":\"" + escape(Instant.now().toString())
                + "\",\"event\":\"" + escape(safeEvent)
                + "\",\"actor\":\"" + escape(safeActor)
                + "\",\"details\":\"" + escape(safeDetails) + "\"}"
                + System.lineSeparator();

        Files.writeString(logFile, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND,
                LinkOption.NOFOLLOW_LINKS);
    }

    public Path getLogFile() {
        return logFile;
    }

    private static String validate(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > MAX_FIELD_LENGTH) {
            throw new IllegalArgumentException(field + " exceeds " + MAX_FIELD_LENGTH + " characters");
        }
        return value;
    }

    private static String escape(String value) {
        StringBuilder result = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        result.append(String.format("\\u%04x", (int) ch));
                    } else {
                        result.append(ch);
                    }
                }
            }
        }
        return result.toString();
    }
}
