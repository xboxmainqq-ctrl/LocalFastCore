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
    private final Map<ChannelKey, NetherNetMessageFramer.Reassembler> reassemblers =
            new ConcurrentHashMap<>();
    private final Consumer<InboundMessage> messageSink;
    private final NexoraAiGuard guard;
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
        this.messageSink = Objects.requireNonNull(messageSink, "messageSink");
        this.guard = Objects.requireNonNull(guard, "guard");
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
        inboundFrames.increment();
        inboundBytes.add(frame.length);
        try {
            guard.recordInboundFrame(networkId, frame.length, startedAt);
            ChannelKey key = new ChannelKey(networkId, channelLabel);
            NetherNetMessageFramer.Reassembler reassembler =
                    reassemblers.computeIfAbsent(key, ignored -> new NetherNetMessageFramer.Reassembler());
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

    @Override
    public void onDisconnected(String networkId) {
        reassemblers.keySet().removeIf(key -> key.networkId().equals(networkId));
        guard.onDisconnected(networkId);
    }

    public int activeChannelCount() {
        return reassemblers.size();
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
