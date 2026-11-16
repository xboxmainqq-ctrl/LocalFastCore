package ru.nexora.network.bedrock;

import ru.nexora.network.nethernet.NetherNetDataChannelListener;
import ru.nexora.network.nethernet.NetherNetInboundPipeline;
import ru.nexora.network.nethernet.NetherNetMessageFramer;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * First Bedrock game-protocol stage after NetherNet data channels open:
 * RequestNetworkSettings -> NetworkSettings -> compressed packet batches.
 */
public final class BedrockSessionManager implements NetherNetDataChannelListener, AutoCloseable {
    public static final int LOGIN_FAILED_CLIENT_OLD = 1;
    public static final int LOGIN_FAILED_SERVER_OLD = 2;
    public static final int DEFAULT_COMPRESSION_THRESHOLD = 256;
    public static final int SCTP_MAX_MESSAGE_BYTES = 262_144;

    private final int serverProtocol;
    private final int compressionThreshold;
    private final OutboundFrameSender outbound;
    private final Consumer<PacketEvent> packetSink;
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final NetherNetInboundPipeline inbound;

    public BedrockSessionManager(
            int serverProtocol,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink
    ) {
        this(serverProtocol, DEFAULT_COMPRESSION_THRESHOLD, outbound, packetSink);
    }

    public BedrockSessionManager(
            int serverProtocol,
            int compressionThreshold,
            OutboundFrameSender outbound,
            Consumer<PacketEvent> packetSink
    ) {
        if (serverProtocol < 1) throw new IllegalArgumentException("serverProtocol must be positive");
        if (compressionThreshold < 1 || compressionThreshold > 0xffff) {
            throw new IllegalArgumentException("compressionThreshold must be 1..65535");
        }
        this.serverProtocol = serverProtocol;
        this.compressionThreshold = compressionThreshold;
        this.outbound = Objects.requireNonNull(outbound, "outbound");
        this.packetSink = Objects.requireNonNull(packetSink, "packetSink");
        this.inbound = new NetherNetInboundPipeline(this::handleCompleteMessage);
    }

    @Override
    public void onFrame(String networkId, String channelLabel, byte[] frame) {
        inbound.onFrame(networkId, channelLabel, frame);
    }

    @Override
    public void onDisconnected(String networkId) {
        sessions.remove(networkId);
        inbound.onDisconnected(networkId);
    }

    public int activeSessionCount() {
        return sessions.size();
    }

    private void handleCompleteMessage(NetherNetInboundPipeline.InboundMessage message) {
        SessionState state = sessions.computeIfAbsent(message.networkId(), ignored -> new SessionState());
        synchronized (state) {
            if (state.rejected) return;

            if (!state.compressionNegotiated) {
                if (!message.reliable()) {
                    throw new IllegalArgumentException("RequestNetworkSettings must arrive on ReliableDataChannel");
                }
                List<BedrockPacket> packets = BedrockBatchCodec.decode(message.payload(), false);
                if (packets.size() != 1) {
                    throw new IllegalArgumentException("Initial Bedrock batch must contain only RequestNetworkSettings");
                }
                int clientProtocol = BedrockBatchCodec.readRequestedProtocol(packets.getFirst());
                if (clientProtocol != serverProtocol) {
                    int status = clientProtocol < serverProtocol
                            ? LOGIN_FAILED_CLIENT_OLD
                            : LOGIN_FAILED_SERVER_OLD;
                    sendRawPacket(message.networkId(),
                            BedrockBatchCodec.createPlayStatus(status));
                    state.rejected = true;
                    System.out.printf("NEXORA: rejected Bedrock protocol %d; server requires %d%n",
                            clientProtocol, serverProtocol);
                    return;
                }

                sendRawPacket(message.networkId(), BedrockBatchCodec.createNetworkSettings(
                        compressionThreshold, BedrockBatchCodec.COMPRESSION_ZLIB));
                state.compressionNegotiated = true;
                state.clientProtocol = clientProtocol;
                System.out.printf("NEXORA: negotiated Bedrock protocol %d for %s%n",
                        clientProtocol, message.networkId());
                return;
            }

            List<BedrockPacket> packets = BedrockBatchCodec.decode(message.payload(), true);
            for (BedrockPacket packet : packets) {
                if (packet.packetId() == 1) state.loginPacketReceived = true;
                packetSink.accept(new PacketEvent(
                        message.networkId(), message.channelLabel(), packet));
            }
        }
    }

    private void sendRawPacket(String networkId, BedrockPacket packet) {
        byte[] batch = BedrockBatchCodec.encode(List.of(packet), false, compressionThreshold);
        List<byte[]> frames = NetherNetMessageFramer.fragment(
                batch, SCTP_MAX_MESSAGE_BYTES, true);
        try {
            for (byte[] frame : frames) {
                outbound.sendFrame(networkId, "ReliableDataChannel", frame);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to send initial Bedrock handshake packet", exception);
        }
    }

    @Override
    public void close() {
        sessions.clear();
    }

    @FunctionalInterface
    public interface OutboundFrameSender {
        void sendFrame(String networkId, String channelLabel, byte[] frame) throws Exception;
    }

    public record PacketEvent(String networkId, String channelLabel, BedrockPacket packet) {
        public PacketEvent {
            Objects.requireNonNull(networkId, "networkId");
            Objects.requireNonNull(channelLabel, "channelLabel");
            Objects.requireNonNull(packet, "packet");
        }
    }

    private static final class SessionState {
        private boolean compressionNegotiated;
        private boolean loginPacketReceived;
        private boolean rejected;
        private int clientProtocol;
    }
}
