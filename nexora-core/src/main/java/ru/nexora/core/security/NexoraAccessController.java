package ru.nexora.core.security;

import java.io.IOException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Audited permission gate for sensitive NEXORA module actions.
 *
 * The caller must stop the requested action when this method returns false
 * or throws. Audit I/O failures are propagated so callers can fail closed.
 */
public final class NexoraAccessController {
    private static final Pattern PERMISSION =
            Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");

    private final AuditLogger auditLogger;

    public NexoraAccessController(AuditLogger auditLogger) {
        this.auditLogger = Objects.requireNonNull(auditLogger, "auditLogger");
    }

    /**
     * Checks one permission and records the decision before returning it.
     *
     * @param subject permission source for the current actor
     * @param actor stable, non-secret actor label for the audit log
     * @param permission lowercase permission node, e.g. "nexora.admin.reload"
     * @return true only when the subject has the permission
     * @throws IOException when the audit decision cannot be persisted
     */
    public boolean authorize(PermissionSubject subject, String actor, String permission)
            throws IOException {
        Objects.requireNonNull(subject, "subject");
        validateActor(actor);
        validatePermission(permission);

        boolean granted = subject.hasPermission(permission);
        auditLogger.record(
                granted ? "access.granted" : "access.denied",
                actor,
                "permission=" + permission);
        return granted;
    }

    private static void validateActor(String actor) {
        if (actor == null || actor.isBlank() || actor.length() > 128) {
            throw new IllegalArgumentException("actor must contain 1..128 characters");
        }
    }

    private static void validatePermission(String permission) {
        if (permission == null || !PERMISSION.matcher(permission).matches()) {
            throw new IllegalArgumentException("permission must be a valid lowercase node");
        }
    }
}
