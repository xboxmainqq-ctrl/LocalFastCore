package ru.nexora.network.nethernet;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthetic ingress micro-load for the real NetherNet framing/reassembly pipeline.
 * It does not include native WebRTC, encryption, Bedrock batch decoding or live clients.
 */
class NetherNetInboundPipelineLoadBenchmarkTest {
    private static final int WARMUP_FRAMES = 2_000;
    private static final int MEASURED_FRAMES = 20_000;
    private static final int PEERS = 64;
    private static final int PAYLOAD_BYTES = 512;

    @Test
    void reportsInboundPipelineCostAcrossManySyntheticPeers() {
        LongAdder delivered = new LongAdder();
        NetherNetInboundPipeline pipeline = new NetherNetInboundPipeline(message -> delivered.increment());
        byte[] frame = new byte[PAYLOAD_BYTES + 1];
        frame[0] = 0;

        for (int i = 0; i < WARMUP_FRAMES; i++) {
            pipeline.onFrame("load-" + (i % PEERS), "ReliableDataChannel", frame);
        }

        long[] samples = new long[MEASURED_FRAMES];
        long started = System.nanoTime();
        for (int i = 0; i < MEASURED_FRAMES; i++) {
            long frameStarted = System.nanoTime();
            pipeline.onFrame("load-" + ((i / 2) % PEERS),
                    (i & 1) == 0 ? "ReliableDataChannel" : "UnreliableDataChannel", frame);
            samples[i] = Math.max(0L, System.nanoTime() - frameStarted);
        }
        long elapsed = Math.max(1L, System.nanoTime() - started);
        java.util.Arrays.sort(samples);

        assertEquals(WARMUP_FRAMES + MEASURED_FRAMES, delivered.sum());
        assertEquals(PEERS * 2, pipeline.activeChannelCount());
        assertEquals(WARMUP_FRAMES + MEASURED_FRAMES, pipeline.stats().completedMessages());
        assertEquals(0, pipeline.stats().failedFrames());

        double framesPerSecond = MEASURED_FRAMES * 1_000_000_000.0 / elapsed;
        long p50 = percentile(samples, 0.50);
        long p95 = percentile(samples, 0.95);
        long max = samples[samples.length - 1];
        assertTrue(framesPerSecond > 0);
        System.out.printf(
                "NEXORA NetherNet inbound synthetic: peers=%d channels=%d frames=%d payload_bytes=%d frames_per_second=%.0f frame_p50_us=%.3f frame_p95_us=%.3f frame_max_us=%.3f%n",
                PEERS, pipeline.activeChannelCount(), MEASURED_FRAMES, PAYLOAD_BYTES,
                framesPerSecond, p50 / 1_000.0, p95 / 1_000.0, max / 1_000.0);
    }

    @Test
    void reportsConcurrentIngressLoadAcrossIndependentPeers() throws Exception {
        int concurrentPeers = 32;
        int framesPerPeer = 2_000;
        LongAdder delivered = new LongAdder();
        NetherNetInboundPipeline pipeline = new NetherNetInboundPipeline(message -> delivered.increment());
        byte[] frame = new byte[PAYLOAD_BYTES + 1];
        frame[0] = 0;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService workers =
                java.util.concurrent.Executors.newFixedThreadPool(concurrentPeers);
        try {
            java.util.List<java.util.concurrent.Callable<Void>> tasks = new java.util.ArrayList<>();
            for (int peer = 0; peer < concurrentPeers; peer++) {
                final int peerIndex = peer;
                tasks.add(() -> {
                    start.await();
                    for (int i = 0; i < framesPerPeer; i++) {
                        pipeline.onFrame("parallel-" + peerIndex,
                                (i & 1) == 0 ? "ReliableDataChannel" : "UnreliableDataChannel", frame);
                    }
                    return null;
                });
            }
            long started = System.nanoTime();
            start.countDown();
            for (var result : workers.invokeAll(tasks)) result.get();
            long elapsed = Math.max(1L, System.nanoTime() - started);
            long totalFrames = (long) concurrentPeers * framesPerPeer;
            assertEquals(totalFrames, delivered.sum());
            assertEquals(concurrentPeers * 2, pipeline.activeChannelCount());
            assertEquals(0, pipeline.stats().failedFrames());
            System.out.printf(
                    "NEXORA NetherNet concurrent ingress synthetic: peers=%d frames=%d frames_per_second=%.0f%n",
                    concurrentPeers, totalFrames, totalFrames * 1_000_000_000.0 / elapsed);
        } finally {
            workers.shutdownNow();
        }
    }


    private static long percentile(long[] sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }
}
