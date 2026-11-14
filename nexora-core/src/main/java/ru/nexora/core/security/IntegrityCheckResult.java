package ru.nexora.core.security;

import java.nio.file.Path;
import java.util.Objects;

/** Immutable result of a non-destructive file integrity check. */
public record IntegrityCheckResult(Path file, String expectedSha256,
                                   String actualSha256, boolean matches) {
    public IntegrityCheckResult {
        file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        expectedSha256 = Objects.requireNonNull(expectedSha256, "expectedSha256");
        actualSha256 = Objects.requireNonNull(actualSha256, "actualSha256");
        if (!expectedSha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Expected SHA-256 must contain exactly 64 hexadecimal characters");
        }
        if (!actualSha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Actual SHA-256 must contain exactly 64 hexadecimal characters");
        }
        expectedSha256 = expectedSha256.toLowerCase(java.util.Locale.ROOT);
        actualSha256 = actualSha256.toLowerCase(java.util.Locale.ROOT);
        if (matches != expectedSha256.equals(actualSha256)) {
            throw new IllegalArgumentException("matches must reflect the SHA-256 comparison");
        }
    }
}
