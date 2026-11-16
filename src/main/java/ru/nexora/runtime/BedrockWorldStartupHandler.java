package ru.nexora.runtime;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.v2225.Bedrock_v2225;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;

/**
 * Sends StartGame followed by skeleton LevelChunk packets for preloaded spawn
 * chunks. The SubChunkRequest handler supplies the actual section contents.
 */
public final class BedrockWorldStartupHandler
        implements BedrockPostLoginCoordinator.WorldStartupHandler {
    private static final int MAX_INITIAL_CHUNKS = 128;

    private final BedrockNetworkSettings networkSettings;
    private final World world;
    private final int dimension;
    private final int clientRequestSubChunkLimit;
    private final List<ChunkPosition> initialChunks;
    private final BedrockRuntimeBlockRegistryProfile blockRegistryProfile;
    private final BedrockItemRegistry itemRegistry;
    private final BiFunction<TransportConnection, BedrockAuthenticatedPlayer,
            BedrockStartGamePacketCodec.Settings> settingsFactory;

    public BedrockWorldStartupHandler(
            BedrockNetworkSettings networkSettings,
            World world,
            int dimension,
            int clientRequestSubChunkLimit,
            List<ChunkPosition> initialChunks,
            BedrockRuntimeBlockRegistryProfile blockRegistryProfile,
            BiFunction<TransportConnection, BedrockAuthenticatedPlayer,
                    BedrockStartGamePacketCodec.Settings> settingsFactory
    ) {
        this.networkSettings = Objects.requireNonNull(networkSettings, "networkSettings");
        this.world = Objects.requireNonNull(world, "world");
        if (dimension < -1 || dimension > 2) {
            throw new IllegalArgumentException("Unsupported Bedrock dimension");
        }
        if (clientRequestSubChunkLimit < -1 || clientRequestSubChunkLimit > 4096) {
            throw new IllegalArgumentException("Client subchunk request limit is outside the allowed range");
        }
        this.dimension = dimension;
        this.clientRequestSubChunkLimit = clientRequestSubChunkLimit;
        this.initialChunks = List.copyOf(Objects.requireNonNull(initialChunks, "initialChunks"));
        this.blockRegistryProfile = Objects.requireNonNull(blockRegistryProfile, "blockRegistryProfile");
        this.itemRegistry = BedrockItemRegistry.loadBundled();
        if (this.initialChunks.isEmpty() || this.initialChunks.size() > MAX_INITIAL_CHUNKS) {
            throw new IllegalArgumentException("Initial chunk count must be between 1 and 128");
        }
        Set<ChunkPosition> unique = new HashSet<>();
        for (ChunkPosition position : this.initialChunks) {
            Objects.requireNonNull(position, "initial chunk");
            if (!unique.add(position)) {
                throw new IllegalArgumentException("Duplicate initial chunk: " + position);
            }
        }
        this.settingsFactory = Objects.requireNonNull(settingsFactory, "settingsFactory");
    }

    @Override
    public CompletionStage<Void> start(
            TransportConnection connection,
            BedrockAuthenticatedPlayer player
    ) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(player, "player");
        if (!connection.isOpen()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Transport connection is closed"));
        }

        try {
            for (ChunkPosition position : initialChunks) {
                if (!world.isChunkLoaded(position)) {
                    throw new IllegalStateException("Initial spawn chunk is not loaded: " + position);
                }
            }

            BedrockStartGamePacketCodec.Settings startSettings =
                    Objects.requireNonNull(settingsFactory.apply(connection, player), "StartGame settings");
            if (startSettings.levelSettings().dimension() != dimension) {
                throw new IllegalArgumentException("StartGame dimension does not match startup dimension");
            }
            blockRegistryProfile.validateStartGameSettings(startSettings);

            List<BedrockGamePacket> packets = new ArrayList<>(initialChunks.size() + 2);
            packets.add(BedrockStartGamePacketCodec.encodePacket(startSettings));
            packets.add(encodeProtocolPacket(itemRegistry.createPacket()));
            for (ChunkPosition position : initialChunks) {
                packets.add(BedrockLevelChunkPacketCodec.encodeSkeletonPacket(
                        new BedrockLevelChunkPacketCodec.ChunkPosition(position.x(), position.z()),
                        dimension,
                        clientRequestSubChunkLimit));
            }

            ByteBuffer batch = BedrockPacketBatchCodec.encodeUncompressed(packets);
            ByteBuffer frame = BedrockBatchCompressionCodec.encodeAfterNetworkSettings(
                    batch, networkSettings);
            if (connection.send(frame, DeliveryMode.RELIABLE_ORDERED)
                    != TransportSendResult.ACCEPTED) {
                throw new IllegalStateException("Transport rejected initial StartGame/chunk batch");
            }
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static BedrockGamePacket encodeProtocolPacket(BedrockPacket packet) {
        ByteBuf payload = Unpooled.buffer();
        try {
            Bedrock_v2225.CODEC.tryEncode(Bedrock_v2225.CODEC.createHelper(), payload, packet);
            int packetId = Bedrock_v2225.CODEC.getPacketDefinition(packet.getClass()).id();
            byte[] bytes = new byte[payload.readableBytes()];
            payload.readBytes(bytes);
            return new BedrockGamePacket(new BedrockPacketHeader(packetId, 0, 0), bytes);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not encode Bedrock startup packet "
                    + packet.getClass().getSimpleName(), failure);
        } finally {
            payload.release();
        }
    }
}
