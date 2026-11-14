package ru.nexora.core.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NexoraAccessControllerTest {
    @TempDir
    Path tempDir;

    @Test
    void recordsGrantedAndDeniedDecisions() throws IOException {
        Path auditFile = tempDir.resolve("security-audit.jsonl");
        NexoraAccessController access = new NexoraAccessController(new AuditLogger(auditFile));

        assertTrue(access.authorize(permission -> permission.equals("nexora.admin.reload"),
                "operator", "nexora.admin.reload"));
        assertFalse(access.authorize(permission -> false,
                "guest", "nexora.admin.reload"));

        List<String> lines = Files.readAllLines(auditFile, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"event\":\"access.granted\""));
        assertTrue(lines.get(1).contains("\"event\":\"access.denied\""));
        assertTrue(lines.get(0).contains("\"actor\":\"operator\""));
        assertTrue(lines.get(1).contains("\"actor\":\"guest\""));
        assertTrue(lines.stream().allMatch(
                line -> line.contains("permission=nexora.admin.reload")));
    }

    @Test
    void rejectsInvalidPermissionNodesBeforeCheckingSubject() {
        NexoraAccessController access = new NexoraAccessController(
                new AuditLogger(tempDir.resolve("security-audit.jsonl")));

        assertThrows(IllegalArgumentException.class,
                () -> access.authorize(permission -> fail("must not be called"),
                        "operator", "Nexora.Admin"));
        assertThrows(IllegalArgumentException.class,
                () -> access.authorize(permission -> fail("must not be called"),
                        "operator", "../reload"));
    }

    @Test
    void rejectsInvalidActorLabels() {
        NexoraAccessController access = new NexoraAccessController(
                new AuditLogger(tempDir.resolve("security-audit.jsonl")));

        assertThrows(IllegalArgumentException.class,
                () -> access.authorize(permission -> true, " ", "nexora.admin.reload"));
    }
}
