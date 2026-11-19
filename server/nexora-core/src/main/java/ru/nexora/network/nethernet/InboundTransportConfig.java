package ru.nexora.network.nethernet;

import java.util.Map;
import java.util.Objects;

/** Validated operator-facing ingress limits loaded from the process environment. */
public record InboundTransportConfig(
        long windowNanos,
        int maxFramesPerWindow,
        long maxBytesPerWindow,
        int maxTrackedPeers,
        long maxReassemblyBytes,
        int maxTrackedChannels,
        int maxFrameBytes
) {
    public static final String WINDOW_MS = "NEXORA_INBOUND_WINDOW_MS";
    public static final String MAX_FRAMES = "NEXORA_INBOUND_MAX_FRAMES";
    public static final String MAX_BYTES = "NEXORA_INBOUND_MAX_BYTES";
    public static final String MAX_PEERS = "NEXORA_INBOUND_MAX_PEERS";
    public static final String MAX_REASSEMBLY_BYTES = "NEXORA_INBOUND_MAX_REASSEMBLY_BYTES";
    public static final String MAX_CHANNELS = "NEXORA_INBOUND_MAX_CHANNELS";
    public static final String MAX_FRAME_BYTES = "NEXORA_INBOUND_MAX_FRAME_BYTES";

    public InboundTransportConfig {
        if (windowNanos < 100_000_000L || windowNanos > 60_000_000_000L) {
            throw new IllegalArgumentException("Inbound window must be between 100 ms and 60 seconds");
        }
        if (maxFramesPerWindow < 1 || maxFramesPerWindow > 1_000_000) {
            throw new IllegalArgumentException("Inbound frame limit must be 1..1000000");
        }
        if (maxBytesPerWindow < 1_024L || maxBytesPerWindow > 512L * 1024L * 1024L) {
            throw new IllegalArgumentException("Inbound byte limit must be 1 KiB..512 MiB");
        }
        if (maxTrackedPeers < 1 || maxTrackedPeers > 100_000) {
            throw new IllegalArgumentException("Inbound peer limit must be 1..100000");
        }
        if (maxReassemblyBytes < 1L * 1024L * 1024L || maxReassemblyBytes > 256L * 1024L * 1024L) {
            throw new IllegalArgumentException("Reassembly memory limit must be 1..256 MiB");
        }
        if (maxTrackedChannels < 1 || maxTrackedChannels > 65_536) {
            throw new IllegalArgumentException("Inbound channel limit must be 1..65536");
        }
        if (maxFrameBytes < 1_024 || maxFrameBytes > NetherNetInboundPipeline.MAX_INBOUND_FRAME_BYTES) {
            throw new IllegalArgumentException("Inbound frame size must be 1 KiB..1 MiB");
        }
    }

    public static InboundTransportConfig defaults() {
        return new InboundTransportConfig(
                InboundPeerBudget.DEFAULT_WINDOW_NANOS,
                InboundPeerBudget.DEFAULT_MAX_FRAMES_PER_WINDOW,
                InboundPeerBudget.DEFAULT_MAX_BYTES_PER_WINDOW,
                InboundPeerBudget.DEFAULT_MAX_TRACKED_PEERS,
                ReassemblyMemoryBudget.DEFAULT_MAX_BYTES,
                NetherNetInboundPipeline.DEFAULT_MAX_TRACKED_CHANNELS,
                NetherNetInboundPipeline.MAX_INBOUND_FRAME_BYTES
        );
    }

    public static InboundTransportConfig fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        InboundTransportConfig defaults = defaults();
        long windowMillis = longValue(environment, WINDOW_MS, defaults.windowNanos / 1_000_000L);
        if (windowMillis > Long.MAX_VALUE / 1_000_000L) {
            throw new IllegalArgumentException("Invalid " + WINDOW_MS + ": value is too large");
        }
        return new InboundTransportConfig(
                windowMillis * 1_000_000L,
                intValue(environment, MAX_FRAMES, defaults.maxFramesPerWindow),
                longValue(environment, MAX_BYTES, defaults.maxBytesPerWindow),
                intValue(environment, MAX_PEERS, defaults.maxTrackedPeers),
                longValue(environment, MAX_REASSEMBLY_BYTES, defaults.maxReassemblyBytes),
                intValue(environment, MAX_CHANNELS, defaults.maxTrackedChannels),
                intValue(environment, MAX_FRAME_BYTES, defaults.maxFrameBytes)
        );
    }

    public InboundPeerBudget createPeerBudget() {
        return new InboundPeerBudget(windowNanos, maxFramesPerWindow, maxBytesPerWindow, maxTrackedPeers);
    }

    public ReassemblyMemoryBudget createReassemblyMemoryBudget() {
        return new ReassemblyMemoryBudget(maxReassemblyBytes);
    }

    private static int intValue(Map<String, String> environment, String key, int fallback) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid " + key + ": " + value, exception);
        }
    }

    private static long longValue(Map<String, String> environment, String key, long fallback) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) return fallback;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid " + key + ": " + value, exception);
        }
    }
}
