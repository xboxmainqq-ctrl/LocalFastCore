package ru.nexora.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Permission-checked, auditable tool boundary for optional NEXORA AI assistants.
 *
 * <p>Tools are registered by trusted server modules. No shell, arbitrary code,
 * filesystem or network execution is provided by this registry.</p>
 */
public final class AiToolRegistry {
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final Consumer<AuditEvent> auditSink;

    public AiToolRegistry(Consumer<AuditEvent> auditSink) {
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
    }

    public void register(Tool tool) {
        Objects.requireNonNull(tool, "tool");
        Tool previous = tools.putIfAbsent(tool.id(), tool);
        if (previous != null) {
            throw new IllegalStateException("AI tool is already registered: " + tool.id());
        }
    }

    public ToolResult execute(
            Principal principal,
            String toolId,
            Map<String, String> arguments,
            boolean operatorApproved
    ) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(toolId, "toolId");
        Objects.requireNonNull(arguments, "arguments");
        Tool tool = tools.get(toolId);
        if (tool == null) {
            auditSink.accept(new AuditEvent(principal.id(), toolId, Status.DENIED, false));
            return new ToolResult(Status.DENIED, "Unknown AI tool");
        }
        if (!principal.permissions().contains(tool.requiredPermission())) {
            auditSink.accept(new AuditEvent(principal.id(), toolId, Status.DENIED, tool.mutating()));
            return new ToolResult(Status.DENIED, "Missing permission: " + tool.requiredPermission());
        }
        if (tool.mutating() && !operatorApproved) {
            auditSink.accept(new AuditEvent(principal.id(), toolId, Status.APPROVAL_REQUIRED, true));
            return new ToolResult(Status.APPROVAL_REQUIRED, "Explicit operator approval is required");
        }

        Map<String, String> safeArguments;
        try {
            safeArguments = validateArguments(arguments);
        } catch (IllegalArgumentException invalidArguments) {
            auditSink.accept(new AuditEvent(principal.id(), toolId, Status.DENIED, tool.mutating()));
            return new ToolResult(Status.DENIED, "Invalid AI tool arguments");
        }
        // Write the audit record before invoking a state-changing operation.
        auditSink.accept(new AuditEvent(principal.id(), toolId, Status.APPROVED, tool.mutating()));
        try {
            String output = Objects.requireNonNull(tool.action().apply(safeArguments), "AI tool output");
            auditSink.accept(new AuditEvent(principal.id(), toolId, Status.SUCCESS, tool.mutating()));
            return new ToolResult(Status.SUCCESS, output);
        } catch (RuntimeException failure) {
            auditSink.accept(new AuditEvent(principal.id(), toolId, Status.FAILED, tool.mutating()));
            return new ToolResult(Status.FAILED, "Tool failed: " + failure.getClass().getSimpleName());
        }
    }

    private static Map<String, String> validateArguments(Map<String, String> arguments) {
        if (arguments.size() > 32) {
            throw new IllegalArgumentException("Too many AI tool arguments");
        }
        Map<String, String> safe = new TreeMap<>();
        arguments.forEach((key, value) -> {
            Objects.requireNonNull(key, "argument key");
            Objects.requireNonNull(value, "argument value");
            if (!key.matches("[A-Za-z0-9._-]{1,64}") || value.length() > 2_048) {
                throw new IllegalArgumentException("Invalid AI tool argument");
            }
            safe.put(key, value);
        });
        return Map.copyOf(safe);
    }

    public Set<String> toolIds() {
        return Set.copyOf(tools.keySet());
    }

    public record Principal(String id, Set<String> permissions) {
        public Principal {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(permissions, "permissions");
            if (id.isBlank() || id.length() > 128) {
                throw new IllegalArgumentException("Invalid AI principal id");
            }
            permissions = Set.copyOf(permissions);
            if (permissions.stream().anyMatch(permission -> permission == null || permission.isBlank())) {
                throw new IllegalArgumentException("AI permissions must be non-blank");
            }
        }
    }

    public record Tool(
            String id,
            String requiredPermission,
            boolean mutating,
            Function<Map<String, String>, String> action
    ) {
        public Tool {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(requiredPermission, "requiredPermission");
            Objects.requireNonNull(action, "action");
            if (!id.matches("[a-z0-9][a-z0-9._-]{0,63}") || requiredPermission.isBlank()) {
                throw new IllegalArgumentException("Invalid AI tool id or permission");
            }
        }
    }

    public enum Status {
        DENIED,
        APPROVAL_REQUIRED,
        APPROVED,
        SUCCESS,
        FAILED
    }

    public record ToolResult(Status status, String output) {
        public ToolResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(output, "output");
        }
    }

    /** Audit records intentionally omit tool arguments and player message content. */
    public record AuditEvent(String principalId, String toolId, Status status, boolean mutating) {
        public AuditEvent {
            Objects.requireNonNull(principalId, "principalId");
            Objects.requireNonNull(toolId, "toolId");
            Objects.requireNonNull(status, "status");
        }
    }
}
