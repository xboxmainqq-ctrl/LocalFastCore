package ru.nexora.core.security;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Coordinates integrity checks and audit events.
 * A mismatch is reported and audited; this class never deletes files,
 * disables plugins, or stops the server automatically.
 */
public final class NexoraSecurityManager {
    private final IntegrityChecker integrityChecker;
    private final AuditLogger auditLogger;

    public NexoraSecurityManager(IntegrityChecker integrityChecker, AuditLogger auditLogger) {
        this.integrityChecker = Objects.requireNonNull(integrityChecker, "integrityChecker");
        this.auditLogger = Objects.requireNonNull(auditLogger, "auditLogger");
    }

    public IntegrityCheckResult verifyFile(Path file, String expectedSha256, String actor)
            throws IOException {
        IntegrityCheckResult result = integrityChecker.verifySha256(file, expectedSha256);
        String event = result.matches() ? "integrity.match" : "integrity.mismatch";
        String details = "file=" + result.file()
                + "; expected=" + result.expectedSha256()
                + "; actual=" + result.actualSha256();
        auditLogger.record(event, actor, details);
        return result;
    }

    public AuditLogger audit() {
        return auditLogger;
    }
}
