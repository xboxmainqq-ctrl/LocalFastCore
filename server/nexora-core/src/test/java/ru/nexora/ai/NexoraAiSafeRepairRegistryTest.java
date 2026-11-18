package ru.nexora.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class NexoraAiSafeRepairRegistryTest {

    @Test
    void manualRepairRequiresConfirmationAndVerifiesItsResult() {
        AtomicBoolean changed = new AtomicBoolean();
        NexoraAiDiagnostics diagnostics = new NexoraAiDiagnostics();
        NexoraAiSafeRepairRegistry registry = new NexoraAiSafeRepairRegistry(execution ->
                diagnostics.recordRepairExecution(execution.actionId(),
                        execution.status().name(), execution.detail()));
        registry.register("cache.mob-path.clear", "Clear transient path cache", false,
                () -> true, () -> changed.set(true), changed::get);

        var rejected = registry.execute("cache.mob-path.clear", "server-console", false);
        assertEquals(NexoraAiSafeRepairRegistry.Status.CONFIRMATION_REQUIRED, rejected.status());
        assertFalse(changed.get());

        var applied = registry.execute("cache.mob-path.clear", "server-console", true);
        assertEquals(NexoraAiSafeRepairRegistry.Status.APPLIED, applied.status());
        assertTrue(changed.get());
        assertTrue(diagnostics.recentEvidence().contains("status=APPLIED"));
        assertEquals(2, registry.history().size());
    }

    @Test
    void automaticExecutionIsRestrictedToExplicitlyAutoSafeActions() {
        AtomicBoolean manual = new AtomicBoolean();
        AtomicBoolean automatic = new AtomicBoolean();
        NexoraAiSafeRepairRegistry registry = new NexoraAiSafeRepairRegistry(ignored -> {});
        registry.register("cache.manual.reset", "Manual-only cache reset", false,
                () -> true, () -> manual.set(true), manual::get);
        registry.register("cache.safe.prune", "Safe expired cache pruning", true,
                () -> true, () -> automatic.set(true), automatic::get);

        var blocked = registry.executeAutomatically("cache.manual.reset");
        assertEquals(NexoraAiSafeRepairRegistry.Status.AUTOMATIC_NOT_ALLOWED, blocked.status());
        assertFalse(manual.get());

        var applied = registry.executeAutomatically("cache.safe.prune");
        assertEquals(NexoraAiSafeRepairRegistry.Status.APPLIED, applied.status());
        assertTrue(automatic.get());
    }

    @Test
    void failedPreconditionAndFailedVerificationAreReported() {
        AtomicBoolean invoked = new AtomicBoolean();
        NexoraAiSafeRepairRegistry registry = new NexoraAiSafeRepairRegistry(ignored -> {});
        registry.register("world.cache.clear", "Clear world cache", false,
                () -> false, () -> invoked.set(true), () -> true);
        registry.register("world.cache.verify", "Cache clear with failed verification", false,
                () -> true, () -> invoked.set(true), () -> false);

        assertEquals(NexoraAiSafeRepairRegistry.Status.PRECONDITION_FAILED,
                registry.execute("world.cache.clear", "server-console", true).status());
        assertFalse(invoked.get());
        assertEquals(NexoraAiSafeRepairRegistry.Status.VERIFICATION_FAILED,
                registry.execute("world.cache.verify", "server-console", true).status());
    }

    @Test
    void unknownActionsAndDuplicateRegistrationAreRejectedSafely() {
        NexoraAiSafeRepairRegistry registry = new NexoraAiSafeRepairRegistry(ignored -> {});
        var missing = registry.execute("cache.missing.action", "server-console", true);
        assertEquals(NexoraAiSafeRepairRegistry.Status.NOT_FOUND, missing.status());

        registry.register("cache.once.only", "Unique action", false,
                () -> true, () -> {}, () -> true);
        assertThrows(IllegalArgumentException.class, () -> registry.register(
                "cache.once.only", "Duplicate", false, () -> true, () -> {}, () -> true));
        assertThrows(IllegalArgumentException.class, () -> registry.register(
                "Bad ID", "Invalid ID", false, () -> true, () -> {}, () -> true));
    }
}
