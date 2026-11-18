package ru.nexora.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticsRuntimeModuleTest {
    @Test
    void ownsDiagnosticsAndStartsTheHealthObserver() throws Exception {
        CountDownLatch inspected = new CountDownLatch(1);
        AtomicInteger observations = new AtomicInteger();
        DiagnosticsRuntimeModule module = new DiagnosticsRuntimeModule(snapshot -> {
            observations.incrementAndGet();
            inspected.countDown();
        });

        assertNotNull(module.diagnostics());
        module.start(Duration.ofHours(1));

        assertTrue(inspected.await(5, TimeUnit.SECONDS));
        assertTrue(module.isStarted());
        assertTrue(observations.get() >= 1);

        module.close();
        module.close();
        assertTrue(module.isClosed());
    }

    @Test
    void rejectsRepeatedStartAndStartAfterClose() {
        DiagnosticsRuntimeModule module = new DiagnosticsRuntimeModule(snapshot -> {});
        module.start(Duration.ofHours(1));
        assertThrows(IllegalStateException.class, module::start);
        module.close();
        assertThrows(IllegalStateException.class, module::start);
    }

    @Test
    void allowsRetryAfterInvalidInterval() {
        DiagnosticsRuntimeModule module = new DiagnosticsRuntimeModule(snapshot -> {});
        assertThrows(IllegalArgumentException.class, () -> module.start(Duration.ZERO));
        assertFalse(module.isStarted());

        module.start(Duration.ofHours(1));
        assertTrue(module.isStarted());
        module.close();
    }
}
