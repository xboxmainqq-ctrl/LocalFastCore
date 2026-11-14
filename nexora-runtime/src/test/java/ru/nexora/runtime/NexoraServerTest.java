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
