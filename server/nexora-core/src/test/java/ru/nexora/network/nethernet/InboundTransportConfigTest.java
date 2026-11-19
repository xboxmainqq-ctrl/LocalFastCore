package ru.nexora.network.nethernet;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InboundTransportConfigTest {
    @Test
    void appliesSafeDefaults() {
        InboundTransportConfig config = InboundTransportConfig.fromEnvironment(Map.of());
        assertEquals(5_000_000_000L, config.windowNanos());
        assertEquals(4_096, config.maxFramesPerWindow());
        assertEquals(16L * 1024L * 1024L, config.maxBytesPerWindow());
        assertEquals(1_024, config.maxTrackedPeers());
        assertEquals(64L * 1024L * 1024L, config.maxReassemblyBytes());
        assertEquals(8_192, config.maxTrackedChannels());
        assertEquals(1_048_576, config.maxFrameBytes());
    }

    @Test
    void parsesOperatorOverridesAndCreatesMatchingBudgets() {
        InboundTransportConfig config = InboundTransportConfig.fromEnvironment(Map.of(
                InboundTransportConfig.WINDOW_MS, "2500",
                InboundTransportConfig.MAX_FRAMES, "9000",
                InboundTransportConfig.MAX_BYTES, "33554432",
                InboundTransportConfig.MAX_PEERS, "512",
                InboundTransportConfig.MAX_REASSEMBLY_BYTES, "134217728",
                InboundTransportConfig.MAX_CHANNELS, "4096",
                InboundTransportConfig.MAX_FRAME_BYTES, "524288"
        ));

        assertEquals(2_500_000_000L, config.windowNanos());
        assertEquals(9_000, config.maxFramesPerWindow());
        assertEquals(32L * 1024L * 1024L, config.maxBytesPerWindow());
        assertEquals(512, config.maxTrackedPeers());
        assertEquals(128L * 1024L * 1024L, config.createReassemblyMemoryBudget().stats().maxBytes());
        assertEquals(524_288, config.maxFrameBytes());
        assertEquals(0, config.createPeerBudget().stats().trackedPeers());
    }

    @Test
    void rejectsOutOfRangeAndMalformedOverrides() {
        assertThrows(IllegalArgumentException.class, () ->
                InboundTransportConfig.fromEnvironment(Map.of(InboundTransportConfig.WINDOW_MS, "20")));
        assertThrows(IllegalArgumentException.class, () ->
                InboundTransportConfig.fromEnvironment(Map.of(InboundTransportConfig.MAX_REASSEMBLY_BYTES, "0")));
        assertThrows(IllegalArgumentException.class, () ->
                InboundTransportConfig.fromEnvironment(Map.of(InboundTransportConfig.MAX_FRAME_BYTES, "n/a")));
        assertThrows(IllegalArgumentException.class, () ->
                InboundTransportConfig.fromEnvironment(Map.of(InboundTransportConfig.MAX_PEERS, "100001")));
    }

    @Test
    void pipelineUsesConfiguredProcessWideReassemblyCap() {
        InboundTransportConfig config = InboundTransportConfig.fromEnvironment(Map.of(
                InboundTransportConfig.MAX_REASSEMBLY_BYTES, "1048576"));
        NetherNetInboundPipeline pipeline = new NetherNetInboundPipeline(
                message -> { }, new ru.nexora.ai.NexoraAiGuard(), config);

        assertEquals(1_048_576, pipeline.reassemblyMemoryStats().maxBytes());
    }
}
