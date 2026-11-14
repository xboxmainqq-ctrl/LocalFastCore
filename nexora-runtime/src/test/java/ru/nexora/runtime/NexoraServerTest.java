package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NexoraServerTest {
    @Test
    void startsInRegistrationOrderAndStopsInReverseOrder() {
        NexoraServer server = new NexoraServer();
        List<String> calls = new ArrayList<>();
        server.register(module("network", calls));
        server.register(module("world", calls));

        server.start();
        assertEquals(ServerState.RUNNING, server.state());
        server.stop();

        assertEquals(List.of("start:network", "start:world", "stop:world", "stop:network"), calls);
        assertEquals(ServerState.STOPPED, server.state());
    }

    @Test
    void failedStartupStopsAlreadyStartedModulesAndMarksServerFailed() {
        NexoraServer server = new NexoraServer();
        List<String> calls = new ArrayList<>();
        server.register(module("first", calls));
        server.register(new RuntimeModule() {
            @Override public String id() { return "broken"; }
            @Override public void start() { calls.add("start:broken"); throw new IllegalStateException("boom"); }
            @Override public void stop() { calls.add("stop:broken"); }
        });

        assertThrows(IllegalStateException.class, server::start);
        assertEquals(List.of("start:first", "start:broken", "stop:first"), calls);
        assertEquals(ServerState.FAILED, server.state());
    }

    @Test
    void retainsShutdownFailureAndRetriesOnlyFailedModule() {
        NexoraServer server = new NexoraServer();
        List<String> calls = new ArrayList<>();
        RuntimeModule unstable = new RuntimeModule() {
            private boolean fail = true;
            @Override public String id() { return "unstable"; }
            @Override public void start() { calls.add("start:unstable"); }
            @Override public void stop() {
                calls.add("stop:unstable");
                if (fail) {
                    fail = false;
                    throw new IllegalStateException("temporary shutdown failure");
                }
            }
        };
        server.register(module("stable", calls));
        server.register(unstable);
        server.start();

        IllegalStateException failure = assertThrows(IllegalStateException.class, server::stop);
        assertEquals("temporary shutdown failure", failure.getCause().getMessage());
        assertEquals(ServerState.FAILED, server.state());

        server.stop();
        assertEquals(ServerState.STOPPED, server.state());
        assertEquals(List.of(
                "start:stable", "start:unstable",
                "stop:unstable", "stop:stable", "stop:unstable"), calls);
    }

    @Test
    void capturesModuleIdAtRegistrationTime() {
        NexoraServer server = new NexoraServer();
        List<String> calls = new ArrayList<>();
        RuntimeModule changingId = new RuntimeModule() {
            private int reads;
            @Override public String id() { return ++reads == 1 ? "initial" : "changed"; }
            @Override public void start() { calls.add("start"); }
            @Override public void stop() { calls.add("stop"); }
        };

        server.register(changingId);

        assertEquals(List.of("initial"), server.moduleIds());
        assertThrows(IllegalArgumentException.class, () -> server.register(module("initial", calls)));
        server.start();
        server.stop();
        assertEquals(List.of("initial"), server.moduleIds());
    }

    @Test
    void stopIsIdempotentWhenNoModulesAreActive() {
        NexoraServer server = new NexoraServer();
        assertDoesNotThrow(server::stop);
        server.start();
        server.stop();
        assertDoesNotThrow(server::stop);
        assertEquals(ServerState.STOPPED, server.state());
    }

    @Test
    void rejectsDuplicateIdsAndRegistrationAfterStart() {
        NexoraServer server = new NexoraServer();
        List<String> calls = new ArrayList<>();
        server.register(module("same", calls));
        assertThrows(IllegalArgumentException.class, () -> server.register(module("same", calls)));
        server.start();
        assertThrows(IllegalStateException.class, () -> server.register(module("late", calls)));
        server.stop();
    }

    private static RuntimeModule module(String id, List<String> calls) {
        return new RuntimeModule() {
            @Override public String id() { return id; }
            @Override public void start() { calls.add("start:" + id); }
            @Override public void stop() { calls.add("stop:" + id); }
        };
    }
}
