package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicInteger;

/** Negotiates the private NEXORA protocol and sends initial world metadata. */
public final class NexoraPrivateProtocolHandler implements Consumer<TransportConnection> {
    private static final int REJECT_UNSUPPORTED_VERSION = 1;
    private static final int REJECT_INVALID_HELLO = 2;

    private final UUID serverId;
    private final int maxMessageBytes;
    private final Supplier<NexoraWorldInfo> worldInfoSupplier;
    private final Consumer<TransportConnection> readyListener;
    private final Supplier<World> worldSupplier;
    private final Function<ChunkPosition, Boolean> chunkLoader;

    public NexoraPrivateProtocolHandler(UUID serverId, int maxMessageBytes,
                                        Consumer<TransportConnection> readyListener) {
        this(serverId, maxMessageBytes, () -> null, null, null, readyListener);
    }

    public NexoraPrivateProtocolHandler(UUID serverId, int maxMessageBytes,
                                        Supplier<NexoraWorldInfo> worldInfoSupplier,
                                        Consumer<TransportConnection> readyListener) {
        this(serverId, maxMessageBytes, worldInfoSupplier, null, null, readyListener);
    }

    public NexoraPrivateProtocolHandler(UUID serverId, int maxMessageBytes,
                                        Supplier<NexoraWorldInfo> worldInfoSupplier,
                                        Supplier<World> worldSupplier,
                                        Consumer<TransportConnection> readyListener) {
        this(serverId, maxMessageBytes, worldInfoSupplier, worldSupplier, null, readyListener);
    }

    public NexoraPrivateProtocolHandler(UUID serverId, int maxMessageBytes,
                                        Supplier<NexoraWorldInfo> worldInfoSupplier,
                                        Supplier<World> worldSupplier,
                                        Function<ChunkPosition, Boolean> chunkLoader,
                                        Consumer<TransportConnection> readyListener) {
        this.serverId = Objects.requireNonNull(serverId, "serverId");
        if (maxMessageBytes < 1 || maxMessageBytes > 8 * 1024 * 1024) {
            throw new IllegalArgumentException("maxMessageBytes is outside the supported range");
        }
        this.maxMessageBytes = maxMessageBytes;
        this.worldInfoSupplier = Objects.requireNonNull(worldInfoSupplier, "worldInfoSupplier");
        this.worldSupplier = worldSupplier;
        this.chunkLoader = chunkLoader;
        this.readyListener = Objects.requireNonNull(readyListener, "readyListener");
    }

    @Override
    public void accept(TransportConnection connection) {
        Objects.requireNonNull(connection, "connection");
        AtomicBoolean completed = new AtomicBoolean();
        connection.setCloseListener(event -> completed.set(true));
        connection.setMessageListener(message -> {
            if (completed.get() || !completed.compareAndSet(false, true)) {
                connection.close();
                return;
            }
            if (message.deliveryMode() != DeliveryMode.RELIABLE_ORDERED
                    || message.sizeBytes() > NexoraPrivateProtocolCodec.MAX_MESSAGE_BYTES) {
                connection.close();
                return;
            }

            final NexoraPrivateProtocolCodec.ClientHello hello;
            try {
                hello = NexoraPrivateProtocolCodec.decodeClientHello(message.payload());
            } catch (RuntimeException malformed) {
                reject(connection, REJECT_INVALID_HELLO, "Invalid NEXORA client hello");
                return;
            }
            if (hello.majorVersion() != NexoraPrivateProtocolCodec.MAJOR_VERSION
                    || hello.minorVersion() > NexoraPrivateProtocolCodec.MINOR_VERSION) {
                reject(connection, REJECT_UNSUPPORTED_VERSION,
                        "Unsupported NEXORA protocol version");
                return;
            }

            final NexoraWorldInfo worldInfo;
            try {
                worldInfo = worldInfoSupplier.get();
            } catch (RuntimeException unavailableWorld) {
                connection.close();
                return;
            }
            if (send(connection, NexoraPrivateProtocolCodec.encodeServerHello(
                    serverId, maxMessageBytes)) != TransportSendResult.ACCEPTED) {
                connection.close();
                return;
            }
            if (worldInfo != null && send(connection,
                    NexoraPrivateProtocolCodec.encodeWorldInfo(worldInfo))
                    != TransportSendResult.ACCEPTED) {
                connection.close();
                return;
            }
            if (worldSupplier == null || chunkLoader == null || worldInfo == null) {
                connection.setMessageListener(ignored -> connection.close());
            } else {
                AtomicInteger requestedChunks = new AtomicInteger();
                connection.setMessageListener(message ->
                        handleChunkRequest(connection, message, worldInfo, requestedChunks));
            }
            try {
                readyListener.accept(connection);
            } catch (RuntimeException listenerFailure) {
                connection.close();
            }
        });
    }

    private void handleChunkRequest(TransportConnection connection, TransportMessage message,
                                    NexoraWorldInfo worldInfo, AtomicInteger requestedChunks) {
        if (!connection.isOpen()) return;
        if (message.deliveryMode() != DeliveryMode.RELIABLE_ORDERED
                || message.sizeBytes() > NexoraChunkDataCodec.MAX_MESSAGE_BYTES) {
            connection.close();
            return;
        }
        try {
            NexoraChunkDataCodec.ChunkRequest request =
                    NexoraChunkDataCodec.decodeChunkRequest(message.payload());
            if (requestedChunks.incrementAndGet() > 9) {
                connection.close();
                return;
            }
            int spawnChunkX = Math.floorDiv(worldInfo.spawnX(), 16);
            int spawnChunkZ = Math.floorDiv(worldInfo.spawnZ(), 16);
            if (Math.abs((long) request.chunkX() - spawnChunkX) > 1
                    || Math.abs((long) request.chunkZ() - spawnChunkZ) > 1) {
                connection.close();
                return;
            }
            ChunkPosition position = new ChunkPosition(request.chunkX(), request.chunkZ());
            if (!Boolean.TRUE.equals(chunkLoader.apply(position))) {
                connection.close();
                return;
            }
            World world = Objects.requireNonNull(worldSupplier.get(), "world");
            NexoraChunkData chunk = NexoraChunkData.volume(
                    world, request.chunkX(), request.chunkZ());
            if (send(connection, NexoraChunkDataCodec.encodeChunkData(chunk))
                    != TransportSendResult.ACCEPTED) {
                connection.close();
            }
        } catch (RuntimeException invalidRequestOrWorld) {
            connection.close();
        }
    }

    private static TransportSendResult send(TransportConnection connection, byte[] payload) {
        return connection.send(ByteBuffer.wrap(payload), DeliveryMode.RELIABLE_ORDERED);
    }

    private static void reject(TransportConnection connection, int code, String reason) {
        connection.setMessageListener(ignored -> connection.close());
        if (send(connection, NexoraPrivateProtocolCodec.encodeReject(code, reason))
                != TransportSendResult.ACCEPTED) {
            connection.close();
        }
    }
}
