package ru.nexora.core.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Computes SHA-256 digests for regular files without modifying them.
 * A symbolic link is rejected to avoid silently verifying a different target.
 */
public final class IntegrityChecker {
    public IntegrityCheckResult verifySha256(Path file, String expectedSha256) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(expectedSha256, "expectedSha256");
        if (!expectedSha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Expected SHA-256 must contain exactly 64 hexadecimal characters");
        }

        Path normalized = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Integrity target is not a regular file: " + normalized);
        }

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available in this Java runtime", impossible);
        }

        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(normalized, LinkOption.NOFOLLOW_LINKS)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }

        String actual = HexFormat.of().formatHex(digest.digest());
        String expected = expectedSha256.toLowerCase(java.util.Locale.ROOT);
        return new IntegrityCheckResult(normalized, expected, actual, expected.equals(actual));
    }
}
