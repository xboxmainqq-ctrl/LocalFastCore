package ru.nexora.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ServerLifecycleTest {
    @Test
    void closesResourcesInReverseRegistrationOrder() throws Exception {
        ServerLifecycle lifecycle = new ServerLifecycle();
        List<String> closed = new ArrayList<>();
        lifecycle.register(() -> closed.add("network"));
        lifecycle.register(() -> closed.add("sessions"));
        lifecycle.register(() -> closed.add("world"));

        lifecycle.close();

        assertEquals(List.of("world", "sessions", "network"), closed);
        assertTrue(lifecycle.isClosed());
    }

    @Test
    void attemptsEveryResourceAndPreservesCloseFailures() {
        ServerLifecycle lifecycle = new ServerLifecycle();
        AtomicInteger closed = new AtomicInteger();
        Exception expected = new IllegalStateException("broken resource");
        lifecycle.register(closed::incrementAndGet);
        lifecycle.register(() -> { throw expected; });
        lifecycle.register(closed::incrementAndGet);

        Exception failure = assertThrows(Exception.class, lifecycle::close);

        assertEquals(2, closed.get());
        assertEquals(1, failure.getSuppressed().length);
        assertSame(expected, failure.getSuppressed()[0]);
    }

    @Test
    void closeIsIdempotent() throws Exception {
        ServerLifecycle lifecycle = new ServerLifecycle();
        AtomicInteger closed = new AtomicInteger();
        lifecycle.register(closed::incrementAndGet);

        lifecycle.close();
        lifecycle.close();

        assertEquals(1, closed.get());
    }

    @Test
    void rejectsRegistrationAfterShutdownStarts() throws Exception {
        ServerLifecycle lifecycle = new ServerLifecycle();
        lifecycle.close();

        assertThrows(IllegalStateException.class, () -> lifecycle.register(() -> { }));
    }
}
