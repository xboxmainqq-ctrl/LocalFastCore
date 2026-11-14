package ru.nexora.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Minimal standalone process lifecycle and module coordinator.
 *
 * This class deliberately has no game protocol or platform-host dependency.
 * Network, world, persistence, and gameplay modules are added as independent
 * RuntimeModule implementations as those subsystems are developed.
 */
public final class NexoraServer implements AutoCloseable {
    private final List<RuntimeModule> modules = new ArrayList<>();
    private final List<String> registeredModuleIds = new ArrayList<>();
    private final List<RuntimeModule> activeModules = new ArrayList<>();
    private ServerState state = ServerState.STOPPED;

    public synchronized void register(RuntimeModule module) {
        Objects.requireNonNull(module, "module");
        if (state != ServerState.STOPPED) {
            throw new IllegalStateException("Modules can only be registered while the server is stopped");
        }
        String id = Objects.requireNonNull(module.id(), "module.id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Module id must not be blank");
        }
        if (registeredModuleIds.contains(id)) {
            throw new IllegalArgumentException("Duplicate runtime module id: " + id);
        }
        modules.add(module);
        registeredModuleIds.add(id);
    }

    public synchronized ServerState state() {
        return state;
    }

    public synchronized List<String> moduleIds() {
        return List.copyOf(registeredModuleIds);
    }

    public synchronized void start() {
        if (state != ServerState.STOPPED) {
            throw new IllegalStateException("Cannot start NEXORA server from state " + state);
        }
        state = ServerState.STARTING;
        try {
            for (RuntimeModule module : modules) {
                module.start();
                activeModules.add(module);
            }
            state = ServerState.RUNNING;
        } catch (Exception startupFailure) {
            StopResult rollback = stopReverse(activeModules, startupFailure);
            activeModules.clear();
            activeModules.addAll(rollback.failedModules());
            state = ServerState.FAILED;
            throw new IllegalStateException("NEXORA server startup failed", startupFailure);
        }
    }

    public synchronized void stop() {
        if (state == ServerState.STOPPED) {
            return;
        }
        if (state != ServerState.RUNNING && state != ServerState.FAILED) {
            throw new IllegalStateException("Cannot stop NEXORA server from state " + state);
        }
        state = ServerState.STOPPING;
        StopResult shutdown = stopReverse(activeModules, null);
        activeModules.clear();
        activeModules.addAll(shutdown.failedModules());
        state = activeModules.isEmpty() ? ServerState.STOPPED : ServerState.FAILED;
        if (!activeModules.isEmpty()) {
            throw new IllegalStateException(
                    "One or more NEXORA modules failed to stop; a later stop() may retry them",
                    shutdown.failure());
        }
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Stops active modules in reverse order. Failed modules remain eligible
     * for a later shutdown retry; successful modules are never stopped twice.
     */
    private static StopResult stopReverse(List<RuntimeModule> active, Exception primaryFailure) {
        List<RuntimeModule> failed = new ArrayList<>();
        Exception aggregate = primaryFailure;
        for (int index = active.size() - 1; index >= 0; index--) {
            RuntimeModule module = active.get(index);
            try {
                module.stop();
            } catch (Exception stopFailure) {
                failed.add(0, module);
                if (aggregate == null) {
                    aggregate = stopFailure;
                } else {
                    aggregate.addSuppressed(stopFailure);
                }
            }
        }
        return new StopResult(List.copyOf(failed), aggregate);
    }

    private record StopResult(List<RuntimeModule> failedModules, Exception failure) {
    }
}
