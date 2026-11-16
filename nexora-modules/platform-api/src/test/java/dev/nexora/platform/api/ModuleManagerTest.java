package dev.nexora.platform.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ModuleManagerTest {
    @Test
    void loadsAndEnablesDependenciesFirstAndDisablesInReverse() throws Exception {
        List<String> events = new ArrayList<>();
        ModuleManager manager = new ModuleManager(context());

        manager.register(module("nexora.gameplay", List.of("nexora.identity"), events));
        manager.register(module("nexora.identity", List.of(), events));

        manager.loadAll();
        manager.enableAll();
        assertEquals(ModuleState.ENABLED, manager.stateOf("nexora.gameplay"));

        manager.disableAll();

        assertEquals(List.of(
                "load:nexora.identity", "load:nexora.gameplay",
                "enable:nexora.identity", "enable:nexora.gameplay",
                "disable:nexora.gameplay", "disable:nexora.identity"
        ), events);
        assertEquals(ModuleState.DISABLED, manager.stateOf("nexora.identity"));
    }

    @Test
    void rejectsMissingDependenciesBeforeCallingLifecycle() {
        List<String> events = new ArrayList<>();
        ModuleManager manager = new ModuleManager(context());
        manager.register(module("nexora.gameplay", List.of("nexora.identity"), events));

        assertThrows(IllegalStateException.class, manager::loadAll);
        assertTrue(events.isEmpty());
    }

    @Test
    void rejectsDependencyCycles() {
        ModuleManager manager = new ModuleManager(context());
        manager.register(module("nexora.alpha", List.of("nexora.beta"), new ArrayList<>()));
        manager.register(module("nexora.beta", List.of("nexora.alpha"), new ArrayList<>()));

        assertThrows(IllegalStateException.class, manager::loadAll);
    }

    @Test
    void failedEnableRollsBackPreviouslyEnabledModules() {
        List<String> events = new ArrayList<>();
        ModuleManager manager = new ModuleManager(context());
        manager.register(module("nexora.first", List.of(), events));
        manager.register(new NexoraModule() {
            @Override
            public ModuleDescriptor descriptor() {
                return descriptor("nexora.second", List.of());
            }

            @Override
            public void onEnable() {
                throw new IllegalStateException("expected test failure");
            }
        });

        assertDoesNotThrow(manager::loadAll);
        assertThrows(ModuleLifecycleException.class, manager::enableAll);
        assertEquals(List.of("load:nexora.first", "enable:nexora.first",
                "disable:nexora.first"), events);
        assertEquals(ModuleState.DISABLED, manager.stateOf("nexora.first"));
        assertEquals(ModuleState.FAILED, manager.stateOf("nexora.second"));
    }

    private static NexoraModule module(String id, List<String> dependencies,
                                       List<String> events) {
        return new NexoraModule() {
            @Override
            public ModuleDescriptor descriptor() {
                return descriptor(id, dependencies);
            }

            @Override
            public void onLoad(ModuleContext context) {
                events.add("load:" + id);
            }

            @Override
            public void onEnable() {
                events.add("enable:" + id);
            }

            @Override
            public void onDisable() {
                events.add("disable:" + id);
            }
        };
    }

    private static ModuleDescriptor descriptor(String id, List<String> dependencies) {
        return new ModuleDescriptor(id, id, "1.0.0", "0.1", dependencies);
    }

    private static ModuleContext context() {
        NexoraServer server = new NexoraServer() {
            public String serverId() { return "test"; }
            public String platformVersion() { return "test"; }
            public long uptimeMillis() { return 0; }
        };
        ServiceRegistry services = new ServiceRegistry() {
            public <T> void register(Class<T> type, T service) {
                throw new UnsupportedOperationException();
            }
            public <T> Optional<T> find(Class<T> type) {
                return Optional.empty();
            }
        };
        NexoraLogger logger = new NexoraLogger() {
            public void info(String message) { }
            public void warn(String message) { }
            public void error(String message, Throwable cause) { }
        };
        return new ModuleContext(server, services, logger);
    }
}
