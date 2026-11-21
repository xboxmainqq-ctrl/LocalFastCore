package ru.nexora.modules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Deterministic module registry with dependency ordering, capability checks
 * and rollback-safe startup/shutdown.
 *
 * <p>This first implementation hosts Java modules only. Lua and Rust adapters
 * will use the same descriptor and capability contract through isolated
 * runtimes or process boundaries.</p>
 */
public final class ModuleRegistry implements AutoCloseable {
    public static final int API_VERSION = 1;

    private enum State { REGISTERING, STARTED, CLOSED }

    private final Map<String, NexoraModule> modules = new LinkedHashMap<>();
    private final Set<ModuleCapability> grantedCapabilities;
    private final Logger logger;
    private List<NexoraModule> loadOrder = List.of();
    private State state = State.REGISTERING;

    public ModuleRegistry(Set<ModuleCapability> grantedCapabilities, Logger logger) {
        this.grantedCapabilities = Set.copyOf(
                Objects.requireNonNull(grantedCapabilities, "grantedCapabilities"));
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public synchronized void register(NexoraModule module) {
        ensureRegistering();
        Objects.requireNonNull(module, "module");
        ModuleDescriptor descriptor = Objects.requireNonNull(module.descriptor(), "module descriptor");
        if (descriptor.apiVersion() != API_VERSION) {
            throw new IllegalArgumentException("Module " + descriptor.id() + " requires API "
                    + descriptor.apiVersion() + ", server provides " + API_VERSION);
        }
        if (modules.putIfAbsent(descriptor.id(), module) != null) {
            throw new IllegalArgumentException("Duplicate NEXORA module: " + descriptor.id());
        }
    }

    public synchronized List<ModuleDescriptor> descriptors() {
        return modules.values().stream().map(NexoraModule::descriptor).toList();
    }

    public synchronized void start() throws Exception {
        ensureRegistering();
        List<NexoraModule> ordered = resolveLoadOrder();
        List<NexoraModule> loaded = new ArrayList<>();
        try {
            for (NexoraModule module : ordered) {
                ModuleDescriptor descriptor = module.descriptor();
                Set<ModuleCapability> missing = new LinkedHashSet<>(descriptor.requiredCapabilities());
                missing.removeAll(grantedCapabilities);
                if (!missing.isEmpty()) {
                    throw new SecurityException("Module " + descriptor.id()
                            + " requests ungranted capabilities: " + missing);
                }
                ModuleContext context = new ModuleContext(
                        descriptor.id(), descriptor.requiredCapabilities(), logger);
                module.onLoad(context);
                loaded.add(module);
            }
            for (NexoraModule module : ordered) {
                module.onEnable();
            }
            loadOrder = List.copyOf(ordered);
            state = State.STARTED;
        } catch (Exception failure) {
            closeModules(loaded, failure);
            state = State.CLOSED;
            throw failure;
        }
    }

    public synchronized boolean isStarted() {
        return state == State.STARTED;
    }

    public synchronized int size() {
        return modules.size();
    }

    @Override
    public synchronized void close() throws Exception {
        if (state == State.CLOSED) return;
        if (state == State.REGISTERING) {
            state = State.CLOSED;
            return;
        }
        Exception failure = null;
        List<NexoraModule> reverse = new ArrayList<>(loadOrder);
        Collections.reverse(reverse);
        for (NexoraModule module : reverse) {
            try {
                module.onDisable();
            } catch (Exception exception) {
                if (failure == null) {
                    failure = new Exception("One or more NEXORA modules failed to disable");
                }
                failure.addSuppressed(exception);
            }
        }
        state = State.CLOSED;
        if (failure != null) throw failure;
    }

    private List<NexoraModule> resolveLoadOrder() {
        List<NexoraModule> ordered = new ArrayList<>();
        Set<String> visiting = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        for (String id : modules.keySet()) visit(id, visiting, visited, ordered);
        return List.copyOf(ordered);
    }

    private void visit(String id, Set<String> visiting, Set<String> visited,
                       List<NexoraModule> ordered) {
        if (visited.contains(id)) return;
        if (!visiting.add(id)) {
            throw new IllegalStateException("NEXORA module dependency cycle: " + visiting + " -> " + id);
        }
        NexoraModule module = modules.get(id);
        if (module == null) {
            throw new IllegalStateException("Missing NEXORA module dependency: " + id);
        }
        for (String dependency : module.descriptor().dependencies().stream().sorted().toList()) {
            if (!modules.containsKey(dependency)) {
                throw new IllegalStateException("Module " + id + " requires missing module " + dependency);
            }
            visit(dependency, visiting, visited, ordered);
        }
        visiting.remove(id);
        visited.add(id);
        ordered.add(module);
    }

    private static void closeModules(List<NexoraModule> loaded, Exception failure) {
        List<NexoraModule> reverse = new ArrayList<>(loaded);
        Collections.reverse(reverse);
        for (NexoraModule module : reverse) {
            try {
                module.onDisable();
            } catch (Exception closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    private void ensureRegistering() {
        if (state != State.REGISTERING) {
            throw new IllegalStateException("NEXORA modules can only be registered before startup");
        }
    }
}
