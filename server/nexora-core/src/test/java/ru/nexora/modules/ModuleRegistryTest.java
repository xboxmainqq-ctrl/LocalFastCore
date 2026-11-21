package ru.nexora.modules;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ModuleRegistryTest {
    private static final Logger LOGGER = Logger.getLogger("nexora-module-test");

    @Test
    void startsDependenciesBeforeDependentsAndStopsInReverseOrder() throws Exception {
        List<String> events = new ArrayList<>();
        ModuleRegistry registry = new ModuleRegistry(Set.of(), LOGGER);
        registry.register(module("gameplay", Set.of("base"), Set.of(), events));
        registry.register(module("base", Set.of(), Set.of(), events));

        registry.start();
        assertEquals(List.of("load:base", "load:gameplay", "enable:base", "enable:gameplay"), events);
        registry.close();
        assertEquals(List.of("load:base", "load:gameplay", "enable:base", "enable:gameplay",
                "disable:gameplay", "disable:base"), events);
    }

    @Test
    void rejectsMissingDependenciesBeforeLoadingAnything() {
        List<String> events = new ArrayList<>();
        ModuleRegistry registry = new ModuleRegistry(Set.of(), LOGGER);
        registry.register(module("gameplay", Set.of("missing"), Set.of(), events));
        assertThrows(IllegalStateException.class, registry::start);
        assertTrue(events.isEmpty());
    }

    @Test
    void rejectsDependencyCycles() {
        ModuleRegistry registry = new ModuleRegistry(Set.of(), LOGGER);
        registry.register(module("alpha", Set.of("beta"), Set.of(), new ArrayList<>()));
        registry.register(module("beta", Set.of("alpha"), Set.of(), new ArrayList<>()));
        assertThrows(IllegalStateException.class, registry::start);
    }

    @Test
    void rejectsCapabilitiesThatWereNotGranted() {
        List<String> events = new ArrayList<>();
        ModuleRegistry registry = new ModuleRegistry(Set.of(), LOGGER);
        registry.register(module("world.editor", Set.of(),
                Set.of(ModuleCapability.MODIFY_WORLD), events));
        assertThrows(SecurityException.class, registry::start);
        assertTrue(events.isEmpty());
    }

    @Test
    void rollsBackLoadedModulesWhenEnableFails() {
        List<String> events = new ArrayList<>();
        ModuleRegistry registry = new ModuleRegistry(Set.of(), LOGGER);
        registry.register(module("base", Set.of(), Set.of(), events));
        registry.register(new NexoraModule() {
            @Override public ModuleDescriptor descriptor() {
                return new ModuleDescriptor("broken", "1.0.0", ModuleRegistry.API_VERSION,
                        Set.of("base"), Set.of());
            }
            @Override public void onLoad(ModuleContext context) { events.add("load:broken"); }
            @Override public void onEnable() throws Exception { throw new Exception("expected"); }
            @Override public void onDisable() { events.add("disable:broken"); }
        });

        assertThrows(Exception.class, registry::start);
        assertEquals(List.of("load:base", "load:broken", "enable:base", "disable:broken", "disable:base"),
                events);
        assertFalse(registry.isStarted());
        assertThrows(IllegalStateException.class, registry::start);
    }

    @Test
    void contextEnforcesDeclaredCapabilities() {
        ModuleContext context = new ModuleContext("test.module",
                Set.of(ModuleCapability.READ_WORLD), LOGGER);
        assertTrue(context.has(ModuleCapability.READ_WORLD));
        assertThrows(SecurityException.class, () -> context.require(ModuleCapability.MODIFY_WORLD));
    }

    private static NexoraModule module(String id, Set<String> dependencies,
                                       Set<ModuleCapability> capabilities, List<String> events) {
        return new NexoraModule() {
            @Override public ModuleDescriptor descriptor() {
                return new ModuleDescriptor(id, "1.0.0", ModuleRegistry.API_VERSION,
                        dependencies, capabilities);
            }
            @Override public void onLoad(ModuleContext context) { events.add("load:" + id); }
            @Override public void onEnable() { events.add("enable:" + id); }
            @Override public void onDisable() { events.add("disable:" + id); }
        };
    }
}
