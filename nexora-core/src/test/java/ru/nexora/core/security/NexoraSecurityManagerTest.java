package ru.nexora.core.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NexoraSecurityManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void verifiesFilesAndAuditsBothMatchesAndMismatches() throws IOException {
        Path artifact = tempDir.resolve("plugin.jar");
        Path auditFile = tempDir.resolve("security-audit.jsonl");
        byte[] original = "NEXORA-TEST".getBytes(StandardCharsets.UTF_8);
        Files.write(artifact, original);

        NexoraSecurityManager security = new NexoraSecurityManager(
                new IntegrityChecker(), new AuditLogger(auditFile));

        IntegrityCheckResult matching = security.verifyFile(
                artifact, sha256(original), "unit-test");
        IntegrityCheckResult mismatching = security.verifyFile(
                artifact, "0".repeat(64), "unit-test");

        assertTrue(matching.matches());
        assertFalse(mismatching.matches());
        assertArrayEquals(original, Files.readAllBytes(artifact),
                "Integrity checks must never modify the inspected file");

        List<String> auditLines = Files.readAllLines(auditFile, StandardCharsets.UTF_8);
        assertEquals(2, auditLines.size());
        assertTrue(auditLines.get(0).contains("\\"event\\":\\"integrity.match\\""));
        assertTrue(auditLines.get(1).contains("\\"event\\":\\"integrity.mismatch\\""));
        assertTrue(auditLines.stream().allMatch(
                line -> line.contains("\\"actor\\":\\"unit-test\\"")));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 must be available in Java", impossible);
        }
    }
}
