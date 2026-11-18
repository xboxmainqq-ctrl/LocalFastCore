package ru.nexora.gameplay;

import ru.nexora.ai.NexoraAiAssistant;
import ru.nexora.ai.NexoraAiDiagnostics;
import ru.nexora.network.bedrock.BedrockSessionManager;
import ru.nexora.network.bedrock.BedrockVanillaItemCatalog;
import ru.nexora.inventory.VanillaArmorSlotCatalog;
import ru.nexora.inventory.NexoraMagicEquipmentCatalog;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the Bedrock gameplay/session runtime and applies gameplay-facing
 * registries, authoritative movement, and the optional operator assistant.
 *
 * <p>Transport negotiation is deliberately owned by NetworkRuntimeModule.
 * This module closes only after transport admission and negotiation have stopped.</p>
 */
public final class GameplayRuntimeModule implements AutoCloseable {
    private final BedrockSessionManager sessions;
    private final NexoraAiAssistant assistant;
    private final AtomicBoolean closed = new AtomicBoolean();

    public GameplayRuntimeModule(
            BedrockSessionManager sessions,
            NexoraAiDiagnostics diagnostics,
            Map<String, String> environment
    ) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        Objects.requireNonNull(diagnostics, "diagnostics");
        Objects.requireNonNull(environment, "environment");
        this.assistant = NexoraAiAssistant.fromEnvironment(environment, diagnostics);

        sessions.setAiDiagnostics(diagnostics);
        assistant.setAdminRuntimeSnapshotProvider(() -> sessions.adminRuntimeSummary()
                + "\n" + sessions.guardSummary());
        sessions.setAiAssistant(assistant);
        sessions.enableServerAuthoritativeMovement();
        sessions.setItemRegistry(BedrockVanillaItemCatalog.createRegistry());
        sessions.setArmorSlotRegistry(VanillaArmorSlotCatalog.createRegistry());
        sessions.setEquipmentSlotRegistry(NexoraMagicEquipmentCatalog.createRegistry());
    }

    public BedrockSessionManager sessions() {
        if (closed.get()) throw new IllegalStateException("Gameplay runtime module is closed");
        return sessions;
    }

    public NexoraAiAssistant assistant() {
        return assistant;
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        sessions.close();
    }
}
