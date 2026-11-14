package ru.nexora.core.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class AuditLoggerTest {
    @TempDir
    Path tempDir;

    @Test
    void appendsEscapedJsonLines() throws IOException {
        Path log = tempDir.resolve("security/audit.jsonl");
        AuditLogger logger = new AuditLogger(log);

        logger.record("operator.action", "Admin", "line one\nline \"two\"");

        List<String> lines = Files.readAllLines(log);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\\n"));
        assertTrue(lines.get(0).contains("\\\"two\\\""));
        assertTrue(lines.get(0).startsWith("{\"timestamp\":"));
    }

    @Test
    void rejectsBlankAndOversizedFields() {
        AuditLogger logger = new AuditLogger(tempDir.resolve("audit.jsonl"));
        assertThrows(IllegalArgumentException.class, () -> logger.record(" ", "admin", "details"));
        assertThrows(IllegalArgumentException.class,
                () -> logger.record("event", "admin", "x".repeat(2049)));
    }
}
