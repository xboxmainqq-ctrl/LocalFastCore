package ru.nexora.core.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class IntegrityCheckerTest {
    @TempDir
    Path tempDir;

    private final IntegrityChecker checker = new IntegrityChecker();

    @Test
    void reportsMatchingAndMismatchingDigests() throws IOException {
        Path file = tempDir.resolve("release.jar");
        Files.writeString(file, "NEXORA");

        IntegrityCheckResult matching = checker.verifySha256(file,
                "a3a7c9b1b4b2a0c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0123456");
        assertFalse(matching.matches());

        String actual = java.util.HexFormat.of().formatHex(
                sha256("NEXORA".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        IntegrityCheckResult verified = checker.verifySha256(file, actual);
        assertTrue(verified.matches());
        assertEquals(actual, verified.actualSha256());
    }

    @Test
    void rejectsInvalidExpectedDigest() throws IOException {
        Path file = tempDir.resolve("release.jar");
        Files.writeString(file, "data");
        assertThrows(IllegalArgumentException.class,
                () -> checker.verifySha256(file, "not-a-sha256"));
    }

    @Test
    void rejectsMissingFiles() {
        assertThrows(IOException.class,
                () -> checker.verifySha256(tempDir.resolve("missing.jar"), "0".repeat(64)));
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
