package ru.nexora.core.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class NexoraSecurityManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void recordsMismatchAndLeavesFileUntouched() throws IOException {
        Path file = tempDir.resolve("plugin.jar");
        Files.writeString(file, "unchanged");
        Path auditFile = tempDir.resolve("audit.jsonl");
        NexoraSecurityManager security = new NexoraSecurityManager(
                new IntegrityChecker(), new AuditLogger(auditFile));

        IntegrityCheckResult result = security.verifyFile(file, "0".repeat(64), "console");

        assertFalse(result.matches());
        assertEquals("unchanged", Files.readString(file));
        assertTrue(Files.readString(auditFile).contains("integrity.mismatch"));
    }
}
