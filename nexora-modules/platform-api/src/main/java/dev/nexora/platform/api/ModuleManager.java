package dev.nexora.platform.api;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic lifecycle coordinator for NEXORA modules.
 *
 * Modules are loaded and enabled in dependency order, then disabled in reverse
 * order. Discovery (finding module classes or jars) is intentionally outside
 * this class so the same manager can be used by tests, embedded hosts and a
 * future Nukkit-MOT adapter.
 */
public final class ModuleManager {
    private final ModuleContext context;
    private final Map<String, Entry> modules = new LinkedHashMap<>();
    private List<Entry> lifecycleOrder = List.of();

    public ModuleManager(ModuleContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public synchronized void register(NexoraModule module) {
        Objects.requireNonNull(module, "module");
        ModuleDescriptor descriptor = Objects.requireNonNull(
                module.descriptor(), "module descriptor");
        if (!lifecycleOrder.isEmpty()) {
            throw new IllegalStateException("Cannot register modules after lifecycle startup");
        }
        Entry previous = modules.putIfAbsent(descriptor.id(), new Entry(module, descriptor));
        if (previous != null) {
            throw new IllegalArgumentException("Duplicate module id: " + descriptor.id());
        }
    }

    public synchronized void loadAll() throws ModuleLifecycleException {
        if (!lifecycleOrder.isEmpty()) {
            throw new IllegalStateException("Module loading has already started");
        }
        lifecycleOrder = resolveOrder();
        for (Entry entry : lifecycleOrder) {
            try {
                entry.module.onLoad(context);
                entry.state = ModuleState.LOADED;
            } catch (Exception exception) {
                entry.state = ModuleState.FAILED;
                throw new ModuleLifecycleException(
                        "Failed to load module " + entry.descriptor.id(), exception);
            }
        }
    }

    public synchronized void enableAll() throws ModuleLifecycleException {
        if (lifecycleOrder.isEmpty() && !modules.isEmpty()) {
            throw new IllegalStateException("Call loadAll() before enableAll()");
        }
        for (Entry entry : lifecycleOrder) {
            if (entry.state != ModuleState.LOADED) {
                throw new IllegalStateException("Module is not loaded: " + entry.descriptor.id());
            }
            try {
                entry.module.onEnable();
                entry.state = ModuleState.ENABLED;
            } catch (Exception exception) {
                entry.state = ModuleState.FAILED;
                disableEnabledModules();
                throw new ModuleLifecycleException(
                        "Failed to enable module " + entry.descriptor.id(), exception);
            }
        }
    }

    public synchronized void disableAll() throws ModuleLifecycleException {
        ModuleLifecycleException failure = disableEnabledModules();
        if (failure != null) {
            throw failure;
        }
    }

    public synchronized ModuleState stateOf(String moduleId) {
        Entry entry = modules.get(Objects.requireNonNull(moduleId, "moduleId"));
        if (entry == null) {
            throw new IllegalArgumentException("Unknown module: " + moduleId);
        }
        return entry.state;
    }

    public synchronized Collection<ModuleDescriptor> descriptors() {
        return modules.values().stream().map(entry -> entry.descriptor).toList();
    }

    private List<Entry> resolveOrder() {
        Map<String, Visit> visits = new LinkedHashMap<>();
        java.util.ArrayList<Entry> ordered = new java.util.ArrayList<>();
        for (Entry entry : modules.values()) {
            visit(entry, visits, ordered);
        }
        return List.copyOf(ordered);
    }

    private void visit(Entry entry, Map<String, Visit> visits, List<Entry> ordered) {
        Visit visit = visits.get(entry.descriptor.id());
        if (visit == Visit.COMPLETE) {
            return;
        }
        if (visit == Visit.ACTIVE) {
            throw new IllegalStateException(
                    "Circular module dependency involving " + entry.descriptor.id());
        }
        visits.put(entry.descriptor.id(), Visit.ACTIVE);
        for (String dependencyId : entry.descriptor.dependencies()) {
            Entry dependency = modules.get(dependencyId);
            if (dependency == null) {
                throw new IllegalStateException("Module " + entry.descriptor.id()
                        + " requires missing module " + dependencyId);
            }
            visit(dependency, visits, ordered);
        }
        visits.put(entry.descriptor.id(), Visit.COMPLETE);
        ordered.add(entry);
    }

    private ModuleLifecycleException disableEnabledModules() {
        ModuleLifecycleException failure = null;
        for (int index = lifecycleOrder.size() - 1; index >= 0; index--) {
            Entry entry = lifecycleOrder.get(index);
            if (entry.state != ModuleState.ENABLED) {
                continue;
            }
            try {
                entry.module.onDisable();
                entry.state = ModuleState.DISABLED;
            } catch (Exception exception) {
                entry.state = ModuleState.FAILED;
                if (failure == null) {
                    failure = new ModuleLifecycleException(
                            "One or more modules failed to disable", exception);
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        return failure;
    }

    private enum Visit {
        ACTIVE,
        COMPLETE
    }

    private static final class Entry {
        private final NexoraModule module;
        private final ModuleDescriptor descriptor;
        private ModuleState state = ModuleState.REGISTERED;

        private Entry(NexoraModule module, ModuleDescriptor descriptor) {
            this.module = module;
            this.descriptor = descriptor;
        }
    }
}
