package ru.nexora.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NexoraAiMonitorTest {
    @Test
    void publishesSnapshotsAndCanBeClosed() throws Exception {
        AtomicReference<NexoraAiDiagnostics.Snapshot> observed = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        NexoraAiMonitor monitor = new NexoraAiMonitor(new NexoraAiDiagnostics(), snapshot -> {
            observed.set(snapshot);
            latch.countDown();
        });

        monitor.start(Duration.ofMillis(10));
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
            assertNotNull(observed.get());
        } finally {
            monitor.close();
        }
    }

    @Test
    void rejectsInvalidIntervalsAndRepeatedStart() {
        NexoraAiMonitor monitor = new NexoraAiMonitor(new NexoraAiDiagnostics(), snapshot -> {});
        try {
            assertThrows(IllegalArgumentException.class, () -> monitor.start(Duration.ZERO));
            monitor.start(Duration.ofSeconds(1));
            assertThrows(IllegalStateException.class, monitor::start);
        } finally {
            monitor.close();
        }
    }
}
