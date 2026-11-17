package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AiToolRegistryTest {
    @Test
    void readOnlyToolRequiresPermissionAndIsAudited() {
        List<AiToolRegistry.AuditEvent> audit = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        AiToolRegistry registry = new AiToolRegistry(audit::add);
        registry.register(new AiToolRegistry.Tool(
                "server.status", "server.diagnostics.read", false,
                arguments -> {
                    calls.incrementAndGet();
                    return "RUNNING";
                }
        ));

        AiToolRegistry.ToolResult denied = registry.execute(
                new AiToolRegistry.Principal("helper", Set.of()), "server.status", Map.of(), false);
        AiToolRegistry.ToolResult allowed = registry.execute(
                new AiToolRegistry.Principal("operator", Set.of("server.diagnostics.read")),
                "server.status", Map.of(), false);

        assertEquals(AiToolRegistry.Status.DENIED, denied.status());
        assertEquals(AiToolRegistry.Status.SUCCESS, allowed.status());
        assertEquals("RUNNING", allowed.output());
        assertEquals(1, calls.get());
        assertEquals(3, audit.size());
    }

    @Test
    void mutatingToolCannotRunWithoutExplicitApproval() {
        List<AiToolRegistry.AuditEvent> audit = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        AiToolRegistry registry = new AiToolRegistry(audit::add);
        registry.register(new AiToolRegistry.Tool(
                "server.reload", "server.config.write", true,
                arguments -> {
                    calls.incrementAndGet();
                    return "reloaded";
                }
        ));
        AiToolRegistry.Principal operator =
                new AiToolRegistry.Principal("owner", Set.of("server.config.write"));

        AiToolRegistry.ToolResult pending = registry.execute(
                operator, "server.reload", Map.of(), false);
        assertEquals(AiToolRegistry.Status.APPROVAL_REQUIRED, pending.status());
        assertEquals(0, calls.get());

        AiToolRegistry.ToolResult approved = registry.execute(
                operator, "server.reload", Map.of(), true);
        assertEquals(AiToolRegistry.Status.SUCCESS, approved.status());
        assertEquals(1, calls.get());
        assertTrue(audit.stream().anyMatch(event -> event.status() == AiToolRegistry.Status.APPROVAL_REQUIRED));
    }

    @Test
    void rejectsOversizedToolArgumentsBeforeCallingTheTool() {
        AtomicInteger calls = new AtomicInteger();
        List<AiToolRegistry.AuditEvent> audit = new ArrayList<>();
        AiToolRegistry registry = new AiToolRegistry(audit::add);
        registry.register(new AiToolRegistry.Tool(
                "server.inspect", "server.read", false,
                arguments -> {
                    calls.incrementAndGet();
                    return "ok";
                }
        ));

        AiToolRegistry.ToolResult result = registry.execute(
                new AiToolRegistry.Principal("operator", Set.of("server.read")),
                "server.inspect", Map.of("payload", "x".repeat(2049)), false);

        assertEquals(AiToolRegistry.Status.DENIED, result.status());
        assertEquals(0, calls.get());
        assertEquals(AiToolRegistry.Status.DENIED, audit.getLast().status());
    }

    @Test
    void duplicateToolIdsAndInvalidDefinitionsAreRejected() {
        AiToolRegistry registry = new AiToolRegistry(ignored -> { });
        AiToolRegistry.Tool tool = new AiToolRegistry.Tool("world.inspect", "world.read", false, args -> "ok");
        registry.register(tool);
        assertThrows(IllegalStateException.class, () -> registry.register(tool));
        assertThrows(IllegalArgumentException.class,
                () -> new AiToolRegistry.Tool("../shell", "server.admin", true, args -> "bad"));
    }
}
