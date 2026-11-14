package ru.nexora.core.security;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Coordinates integrity checks, access decisions and audit events.
 * A mismatch is reported and audited; this class never deletes files,
 * disables plugins, or stops the server automatically.
 */
public final class NexoraSecurityManager {
    private final IntegrityChecker integrityChecker;
    private final AuditLogger auditLogger;
    private final NexoraAccessController accessController;

    /** Compatibility constructor retained for existing integrations. */
    public NexoraSecurityManager(IntegrityChecker integrityChecker, AuditLogger auditLogger) {
        this(integrityChecker, auditLogger, null);
    }

    public NexoraSecurityManager(IntegrityChecker integrityChecker, AuditLogger auditLogger,
                                 NexoraAccessController accessController) {
        this.integrityChecker = Objects.requireNonNull(integrityChecker, "integrityChecker");
        this.auditLogger = Objects.requireNonNull(auditLogger, "auditLogger");
        this.accessController = accessController;
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

    /** Empty when the host has not wired an access controller. */
    public Optional<NexoraAccessController> access() {
        return Optional.ofNullable(accessController);
    }

    public AuditLogger audit() {
        return auditLogger;
    }
}
