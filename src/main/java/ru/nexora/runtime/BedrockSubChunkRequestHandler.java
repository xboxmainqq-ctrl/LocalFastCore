package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.ToIntFunction;

/**
 * Routes authenticated in-game SubChunkRequest packets to the loaded-world
 * response builder. Non-subchunk gameplay messages are delegated unchanged.
 */
public final class BedrockSubChunkRequestHandler
        implements BiConsumer<TransportConnection, TransportMessage> {
    private final BedrockNetworkSettings networkSettings;
    private final World world;
    private final ToIntFunction<TransportConnection> playerDimension;
    private final BedrockRuntimeBlockRegistry blockRegistry;
    private final BiConsumer<TransportConnection, TransportMessage> fallback;
    private static final int MAX_REQUEST_PACKETS_PER_MESSAGE = 16;
    private final int maximumBatchBytes;

    public BedrockSubChunkRequestHandler(
            BedrockNetworkSettings networkSettings,
            World world,
            ToIntFunction<TransportConnection> playerDimension,
            BedrockRuntimeBlockRegistryProfile blockProfile,
            BiConsumer<TransportConnection, TransportMessage> fallback
    ) {
        this(networkSettings, world, playerDimension,
                Objects.requireNonNull(blockProfile, "blockProfile").registry(), fallback);
    }

    public BedrockSubChunkRequestHandler(
            BedrockNetworkSettings networkSettings,
            World world,
            ToIntFunction<TransportConnection> playerDimension,
            BedrockRuntimeBlockRegistry blockRegistry,
            BiConsumer<TransportConnection, TransportMessage> fallback
    ) {
        this(networkSettings, world, playerDimension, blockRegistry, fallback,
                BedrockPacketBatchCodec.DEFAULT_MAX_BATCH_BYTES);
    }

    public BedrockSubChunkRequestHandler(
            BedrockNetworkSettings networkSettings,
            World world,
            ToIntFunction<TransportConnection> playerDimension,
            BedrockRuntimeBlockRegistry blockRegistry,
            BiConsumer<TransportConnection, TransportMessage> fallback,
            int maximumBatchBytes
    ) {
        this.networkSettings = Objects.requireNonNull(networkSettings, "networkSettings");
        this.world = Objects.requireNonNull(world, "world");
        this.playerDimension = Objects.requireNonNull(playerDimension, "playerDimension");
        this.blockRegistry = Objects.requireNonNull(blockRegistry, "blockRegistry");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        if (maximumBatchBytes < 1
                || maximumBatchBytes > BedrockPacketBatchCodec.DEFAULT_MAX_BATCH_BYTES) {
            throw new IllegalArgumentException("Invalid maximum batch byte limit");
        }
        this.maximumBatchBytes = maximumBatchBytes;
    }

    @Override
    public void accept(TransportConnection connection, TransportMessage message) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(message, "message");
        if (!connection.isOpen()) {
            return;
        }

        final List<BedrockGamePacket> packets;
        try {
            ByteBuffer batch = BedrockBatchCompressionCodec.decodeAfterNetworkSettings(
                    message.payload(), networkSettings, maximumBatchBytes);
            packets = BedrockPacketBatchCodec.decodeUncompressed(batch);
        } catch (RuntimeException malformedFrame) {
            connection.close();
            return;
        }

        if (packets.isEmpty()) {
            connection.close();
            return;
        }
        List<BedrockGamePacket> subChunkRequests = packets.stream()
                .filter(packet -> packet.header().packetId()
                        == BedrockSubChunkRequestPacketCodec.PACKET_ID)
                .toList();
        List<BedrockGamePacket> otherPackets = packets.stream()
                .filter(packet -> packet.header().packetId()
                        != BedrockSubChunkRequestPacketCodec.PACKET_ID)
                .toList();
        if (subChunkRequests.isEmpty()) {
            fallback.accept(connection, message);
            return;
        }
        if (subChunkRequests.size() > MAX_REQUEST_PACKETS_PER_MESSAGE
                || message.deliveryMode() != DeliveryMode.RELIABLE_ORDERED) {
            connection.close();
            return;
        }

        try {
            int dimension = playerDimension.applyAsInt(connection);
            for (BedrockGamePacket packet : subChunkRequests) {
                if (packet.header().senderSubClientId() != 0
                        || packet.header().targetSubClientId() != 0) {
                    throw new IllegalArgumentException("Unexpected sub-client routing on SubChunkRequest");
                }
                BedrockSubChunkRequestPacketCodec.Request request =
                        BedrockSubChunkRequestPacketCodec.decodePayload(packet.payloadBuffer());
                List<BedrockGamePacket> responses = BedrockSubChunkResponseBuilder.buildPackets(
                        world, request, dimension, blockRegistry);
                for (BedrockGamePacket response : responses) {
                    ByteBuffer responseBatch = BedrockPacketBatchCodec.encodeUncompressed(
                            List.of(response));
                    ByteBuffer frame = BedrockBatchCompressionCodec.encodeAfterNetworkSettings(
                            responseBatch, networkSettings);
                    if (connection.send(frame, DeliveryMode.RELIABLE_ORDERED)
                            != TransportSendResult.ACCEPTED) {
                        connection.close();
                        return;
                    }
                }
            }
            if (!otherPackets.isEmpty() && connection.isOpen()) {
                ByteBuffer otherBatch = BedrockPacketBatchCodec.encodeUncompressed(otherPackets);
                ByteBuffer otherFrame = BedrockBatchCompressionCodec.encodeAfterNetworkSettings(
                        otherBatch, networkSettings);
                fallback.accept(connection, new TransportMessage(otherFrame, message.deliveryMode()));
            }
        } catch (RuntimeException invalidRequestOrResponse) {
            connection.close();
        }
    }
}
