package ru.nexora.core.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
    @Test
    void refusesSymbolicLinkAuditTargetsWhenSupported() throws IOException {
        Path target = Files.writeString(tempDir.resolve("target.jsonl"), "keep\n");
        Path link = tempDir.resolve("audit-link.jsonl");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | SecurityException | IOException unavailable) {
            return;
        }

        AuditLogger logger = new AuditLogger(link);
        assertThrows(IOException.class, () -> logger.record("security.check", "system", "details"));
        assertEquals("keep\n", Files.readString(target));
    }

    @Test
    void refusesDirectoryAsAuditLogTarget() throws IOException {
        Path directory = Files.createDirectory(tempDir.resolve("audit-directory"));
        AuditLogger logger = new AuditLogger(directory);
        assertThrows(IOException.class, () -> logger.record("security.check", "system", "details"));
    }

}
