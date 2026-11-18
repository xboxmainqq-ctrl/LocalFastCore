package ru.nexora.network;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NetworkRuntimeModuleTest {
    @Test
    void startsDiscoveryBeforeSignalingAndClosesInDependencyOrder() throws Exception {
        List<String> events = new ArrayList<>();
        NetworkRuntimeModule module = module(events, false, false);

        module.start();
        module.start();
        module.close();
        module.close();

        assertEquals(List.of(
                "start-discovery", "start-signaling",
                "close-discovery", "close-signaling", "close-negotiator"
        ), events);
        assertTrue(module.isClosed());
    }

    @Test
    void rollsBackAlreadyStartedServicesWhenSignalingStartupFails() {
        List<String> events = new ArrayList<>();
        NetworkRuntimeModule module = module(events, true, false);

        IOException failure = assertThrows(IOException.class, module::start);

        assertEquals("signaling startup failed", failure.getMessage());
        assertEquals(List.of(
                "start-discovery", "start-signaling",
                "close-discovery", "close-signaling", "close-negotiator"
        ), events);
        assertTrue(module.isClosed());
    }

    @Test
    void attemptsEveryCloseAndPreservesSubsequentFailures() {
        List<String> events = new ArrayList<>();
        NetworkRuntimeModule module = module(events, false, true);

        Exception failure = assertThrows(Exception.class, module::close);

        assertEquals(List.of(
                "close-discovery", "close-signaling", "close-negotiator"
        ), events);
        assertEquals("discovery close failed", failure.getMessage());
        assertEquals(2, failure.getSuppressed().length);
    }


    @Test
    void drainsSessionsAfterSignalingStopsAndBeforeNegotiatorCloses() throws Exception {
        List<String> events = new ArrayList<>();
        NetworkRuntimeModule module = new NetworkRuntimeModule(
                service("discovery", events, false, false),
                service("signaling", events, false, false),
                closeable("negotiator", events, false),
                () -> events.add("drain-sessions"));

        module.start();
        module.close();

        assertEquals(List.of(
                "start-discovery", "start-signaling",
                "close-discovery", "close-signaling", "drain-sessions", "close-negotiator"
        ), events);
    }


    @Test
    void stillClosesNegotiatorWhenSessionDrainFails() {
        List<String> events = new ArrayList<>();
        NetworkRuntimeModule module = new NetworkRuntimeModule(
                service("discovery", events, false, false),
                service("signaling", events, false, false),
                closeable("negotiator", events, false),
                () -> {
                    events.add("drain-sessions");
                    throw new IOException("drain interrupted");
                });

        Exception failure = assertThrows(Exception.class, module::close);

        assertEquals("drain interrupted", failure.getMessage());
        assertEquals(List.of(
                "close-discovery", "close-signaling", "drain-sessions", "close-negotiator"
        ), events);
    }

    @Test
    void cannotStartAfterClose() throws Exception {
        NetworkRuntimeModule module = module(new ArrayList<>(), false, false);
        module.close();
        assertThrows(IllegalStateException.class, module::start);
    }

    private static NetworkRuntimeModule module(
            List<String> events,
            boolean failSignalingStart,
            boolean failClose
    ) {
        return new NetworkRuntimeModule(
                service("discovery", events, false, failClose),
                service("signaling", events, failSignalingStart, failClose),
                closeable("negotiator", events, failClose)
        );
    }

    private static NetworkRuntimeModule.ManagedService service(
            String name, List<String> events, boolean failStart, boolean failClose
    ) {
        return new NetworkRuntimeModule.ManagedService() {
            @Override
            public void start() throws Exception {
                events.add("start-" + name);
                if (failStart) throw new IOException(name + " startup failed");
            }

            @Override
            public void close() throws Exception {
                events.add("close-" + name);
                if (failClose) throw new IOException(name + " close failed");
            }
        };
    }

    private static AutoCloseable closeable(String name, List<String> events, boolean failClose) {
        return () -> {
            events.add("close-" + name);
            if (failClose) throw new IOException(name + " close failed");
        };
    }
}
