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
            pipeline.onFrame("load-" + (i % PEERS),
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

    private static long percentile(long[] sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }
}
