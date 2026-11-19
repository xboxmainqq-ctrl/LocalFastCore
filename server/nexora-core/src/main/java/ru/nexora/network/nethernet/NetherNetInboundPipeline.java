package ru.nexora.network.nethernet;

import java.util.Map;
import java.util.Objects;
import ru.nexora.ai.NexoraAiGuard;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * Per-channel NetherNet framing/reassembly before the Bedrock batch decoder.
 */
public final class NetherNetInboundPipeline implements NetherNetDataChannelListener {
    public static final int MAX_INBOUND_FRAME_BYTES = 1_048_576;
    public static final int DEFAULT_MAX_TRACKED_CHANNELS = 8_192;

    private final int maxTrackedChannels;
    private final int maxFrameBytes;
    private final Map<ChannelKey, NetherNetMessageFramer.Reassembler> reassemblers =
            new ConcurrentHashMap<>();
    private final Consumer<InboundMessage> messageSink;
    private final NexoraAiGuard guard;
    private final InboundPeerBudget peerBudget;
    private final ReassemblyMemoryBudget reassemblyMemoryBudget;
    private final LongAdder inboundFrames = new LongAdder();
    private final LongAdder inboundBytes = new LongAdder();
    private final LongAdder completedMessages = new LongAdder();
    private final LongAdder failedFrames = new LongAdder();
    private final LongAdder totalProcessingNanos = new LongAdder();
    private final AtomicLong maxProcessingNanos = new AtomicLong();
    private final AtomicLong lastProcessingNanos = new AtomicLong();

    public NetherNetInboundPipeline(Consumer<InboundMessage> messageSink) {
        this(messageSink, new NexoraAiGuard());
    }

    public NetherNetInboundPipeline(Consumer<InboundMessage> messageSink, NexoraAiGuard guard) {
        this(messageSink, guard, DEFAULT_MAX_TRACKED_CHANNELS, MAX_INBOUND_FRAME_BYTES);
    }

    public NetherNetInboundPipeline(Consumer<InboundMessage> messageSink, NexoraAiGuard guard,
                                    InboundTransportConfig config) {
        this(messageSink, guard,
                Objects.requireNonNull(config, "config").createPeerBudget(),
                config.createReassemblyMemoryBudget(),
                config.maxTrackedChannels(), config.maxFrameBytes());
    }

    public NetherNetInboundPipeline(Consumer<InboundMessage> messageSink, NexoraAiGuard guard,
                                    int maxTrackedChannels, int maxFrameBytes) {
        this(messageSink, guard, new InboundPeerBudget(), new ReassemblyMemoryBudget(),
                maxTrackedChannels, maxFrameBytes);
    }

    public NetherNetInboundPipeline(Consumer<InboundMessage> messageSink, NexoraAiGuard guard,
                                    InboundPeerBudget peerBudget, int maxTrackedChannels, int maxFrameBytes) {
        this(messageSink, guard, peerBudget, new ReassemblyMemoryBudget(),
                maxTrackedChannels, maxFrameBytes);
    }

    public NetherNetInboundPipeline(Consumer<InboundMessage> messageSink, NexoraAiGuard guard,
                                    InboundPeerBudget peerBudget, ReassemblyMemoryBudget reassemblyMemoryBudget,
                                    int maxTrackedChannels, int maxFrameBytes) {
        this.messageSink = Objects.requireNonNull(messageSink, "messageSink");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.peerBudget = Objects.requireNonNull(peerBudget, "peerBudget");
        this.reassemblyMemoryBudget = Objects.requireNonNull(reassemblyMemoryBudget, "reassemblyMemoryBudget");
        if (maxTrackedChannels < 1 || maxFrameBytes < 1 || maxFrameBytes > MAX_INBOUND_FRAME_BYTES) {
            throw new IllegalArgumentException("Invalid inbound pipeline limits");
        }
        this.maxTrackedChannels = maxTrackedChannels;
        this.maxFrameBytes = maxFrameBytes;
    }

    @Override
    public void onFrame(String networkId, String channelLabel, byte[] frame) {
        boolean reliable;
        if ("ReliableDataChannel".equals(channelLabel)) {
            reliable = true;
        } else if ("UnreliableDataChannel".equals(channelLabel)) {
            reliable = false;
        } else {
            throw new IllegalArgumentException("Unknown NetherNet channel: " + channelLabel);
        }

        long startedAt = System.nanoTime();
        try {
            Objects.requireNonNull(networkId, "networkId");
            Objects.requireNonNull(frame, "frame");
            if (networkId.isBlank()) throw new IllegalArgumentException("NetworkID must not be blank");
            inboundFrames.increment();
            inboundBytes.add(frame.length);
            if (frame.length > maxFrameBytes) {
                throw new IllegalArgumentException("NetherNet inbound frame exceeds "
                        + maxFrameBytes + " byte limit");
            }
            if (!peerBudget.tryAcquire(networkId, frame.length, startedAt)) {
                throw new IllegalStateException("NetherNet inbound peer budget exceeded");
            }
            guard.recordInboundFrame(networkId, frame.length, startedAt);
            ChannelKey key = new ChannelKey(networkId, channelLabel);
            NetherNetMessageFramer.Reassembler reassembler = reassemblerFor(key);
            Optional<byte[]> complete = reassembler.accept(frame, reliable);
            if (complete.isPresent()) {
                messageSink.accept(new InboundMessage(networkId, channelLabel, reliable, complete.get()));
                completedMessages.increment();
            }
        } catch (RuntimeException | Error failure) {
            failedFrames.increment();
            throw failure;
        } finally {
            long elapsed = Math.max(0L, System.nanoTime() - startedAt);
            totalProcessingNanos.add(elapsed);
            lastProcessingNanos.set(elapsed);
            maxProcessingNanos.accumulateAndGet(elapsed, Math::max);
        }
    }

    /**
     * Existing channels take the concurrent-map fast path. Synchronization is only
     * needed while creating a previously unseen channel and enforcing the hard cap.
     */
    private NetherNetMessageFramer.Reassembler reassemblerFor(ChannelKey key) {
        NetherNetMessageFramer.Reassembler existing = reassemblers.get(key);
        if (existing != null) return existing;

        synchronized (reassemblers) {
            existing = reassemblers.get(key);
            if (existing != null) return existing;
            if (reassemblers.size() >= maxTrackedChannels) {
                throw new IllegalStateException("NetherNet inbound channel limit reached");
            }
            NetherNetMessageFramer.Reassembler created = new NetherNetMessageFramer.Reassembler(
                    NetherNetMessageFramer.DEFAULT_MAX_MESSAGE_BYTES,
                    NetherNetMessageFramer.DEFAULT_MAX_ASSEMBLY_NANOS, reassemblyMemoryBudget);
            reassemblers.put(key, created);
            return created;
        }
    }

    @Override
    public void onDisconnected(String networkId) {
        Objects.requireNonNull(networkId, "networkId");
        synchronized (reassemblers) {
            var iterator = reassemblers.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<ChannelKey, NetherNetMessageFramer.Reassembler> entry = iterator.next();
                if (entry.getKey().networkId().equals(networkId)) {
                    entry.getValue().close();
                    iterator.remove();
                }
            }
        }
        peerBudget.onDisconnected(networkId);
        guard.onDisconnected(networkId);
    }

    public int activeChannelCount() {
        return reassemblers.size();
    }

    public InboundPeerBudget.Stats peerBudgetStats() {
        return peerBudget.stats();
    }

    public ReassemblyMemoryBudget.Stats reassemblyMemoryStats() {
        return reassemblyMemoryBudget.stats();
    }

    /** Aggregate low-overhead inbound framing/dispatch measurements; not a network throughput guarantee. */
    public InboundStats stats() {
        return new InboundStats(inboundFrames.sum(), inboundBytes.sum(), completedMessages.sum(),
                failedFrames.sum(), totalProcessingNanos.sum(), maxProcessingNanos.get(),
                lastProcessingNanos.get(), reassemblers.size());
    }

    public record InboundStats(long frames, long bytes, long completedMessages, long failedFrames,
                               long totalProcessingNanos, long maxProcessingNanos,
                               long lastProcessingNanos, int activeChannels) {
        public double averageProcessingNanos() {
            return frames == 0 ? 0.0 : totalProcessingNanos / (double) frames;
        }
    }

    public record InboundMessage(
            String networkId,
            String channelLabel,
            boolean reliable,
            byte[] payload
    ) {
        public InboundMessage {
            Objects.requireNonNull(networkId, "networkId");
            Objects.requireNonNull(channelLabel, "channelLabel");
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    private record ChannelKey(String networkId, String channelLabel) {
    }
}
