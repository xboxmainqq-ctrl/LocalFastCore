package ru.nexora.network.nethernet;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Per-channel NetherNet framing/reassembly before the Bedrock batch decoder.
 */
public final class NetherNetInboundPipeline implements NetherNetDataChannelListener {
    private final Map<ChannelKey, NetherNetMessageFramer.Reassembler> reassemblers =
            new ConcurrentHashMap<>();
    private final Consumer<InboundMessage> messageSink;

    public NetherNetInboundPipeline(Consumer<InboundMessage> messageSink) {
        this.messageSink = Objects.requireNonNull(messageSink, "messageSink");
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

        ChannelKey key = new ChannelKey(networkId, channelLabel);
        NetherNetMessageFramer.Reassembler reassembler =
                reassemblers.computeIfAbsent(key, ignored -> new NetherNetMessageFramer.Reassembler());
        Optional<byte[]> complete = reassembler.accept(frame, reliable);
        complete.ifPresent(payload -> messageSink.accept(
                new InboundMessage(networkId, channelLabel, reliable, payload)));
    }

    @Override
    public void onDisconnected(String networkId) {
        reassemblers.keySet().removeIf(key -> key.networkId().equals(networkId));
    }

    public int activeChannelCount() {
        return reassemblers.size();
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
