package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

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
    private final Map<TransportConnection, PlayerSession> playerSessions =
            new ConcurrentHashMap<>();
    private final EntityRegistry entities = new EntityRegistry();

    /** Returns the authoritative entity registry for this server world. */
    public EntityRegistry entities() {
        return entities;
    }

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
        AtomicReference<PlayerSession> activeSession = new AtomicReference<>();
        connection.setCloseListener(event -> {
            completed.set(true);
            PlayerSession session = activeSession.getAndSet(null);
            if (session != null && playerSessions.remove(connection, session)) {
                entities.remove(session.playerId);
                broadcastPlayerLeft(connection, session.playerId);
            }
        });
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
            if (worldInfo != null && worldSupplier != null && chunkLoader != null && send(connection,
                    NexoraMovementPacketCodec.encodeServerPosition(
                            new NexoraMovementValidator.Position(
                                    worldInfo.spawnX() + 0.5,
                                    worldInfo.spawnY(),
                                    worldInfo.spawnZ() + 0.5)))
                    != TransportSendResult.ACCEPTED) {
                connection.close();
                return;
            }
            if (worldSupplier == null || chunkLoader == null || worldInfo == null) {
                connection.setMessageListener(ignored -> connection.close());
            } else {
                AtomicInteger requestedChunks = new AtomicInteger();
                java.util.concurrent.atomic.AtomicLong lastMovementNanos =
                        new java.util.concurrent.atomic.AtomicLong();
                NexoraMovementValidator movementValidator = new NexoraMovementValidator();
                AtomicReference<NexoraMovementValidator.Position> playerPosition =
                        new AtomicReference<>(new NexoraMovementValidator.Position(
                                worldInfo.spawnX() + 0.5, worldInfo.spawnY(),
                                worldInfo.spawnZ() + 0.5));
                PlayerSession session = new PlayerSession(UUID.randomUUID(), hello.playerName(), hello.skinIndex(), playerPosition);
                activeSession.set(session);
                playerSessions.put(connection, session);
                NexoraMovementValidator.Position spawn = session.position.get();
                entities.spawn(WorldEntity.player(session.playerId, session.playerName,
                        spawn.x(), spawn.y(), spawn.z(), session.yaw.get(), session.skinIndex));
                synchronizeNewPlayer(connection, session);
                connection.setMessageListener(message -> {
                    if (isMovementPacket(message.payload())) {
                        handleMovement(connection, message, session,
                                movementValidator, lastMovementNanos);
                    } else {
                        handleChunkRequest(connection, message, worldInfo, requestedChunks);
                    }
                });
            }
            try {
                readyListener.accept(connection);
            } catch (RuntimeException listenerFailure) {
                connection.close();
            }
        });
    }

    private void handleMovement(
            TransportConnection connection, TransportMessage message,
            PlayerSession session,
            NexoraMovementValidator validator,
            java.util.concurrent.atomic.AtomicLong lastMovementNanos) {
        if (!connection.isOpen()) return;
        if (message.deliveryMode() != DeliveryMode.UNRELIABLE
                && message.deliveryMode() != DeliveryMode.RELIABLE_ORDERED) {
            connection.close();
            return;
        }
        long now = System.nanoTime();
        long previous = lastMovementNanos.get();
        if (previous != 0L && now - previous < 50_000_000L) {
            return;
        }
        if (!lastMovementNanos.compareAndSet(previous, now)) {
            return;
        }
        try {
            NexoraMovementValidator.Position requested =
                    NexoraMovementPacketCodec.decodeClientMove(message.payload());
            World world = Objects.requireNonNull(worldSupplier.get(), "world");
            NexoraMovementValidator.Result result =
                    validator.validate(world, session.position.get(), requested);
            if (result.accepted()) {
                NexoraMovementValidator.Position previousPosition = session.position.get();
                double dx = result.position().x() - previousPosition.x();
                double dz = result.position().z() - previousPosition.z();
                if (dx * dx + dz * dz > 0.0001) {
                    session.yaw.set(Math.toDegrees(Math.atan2(-dz, -dx)));
                }
                session.position.set(result.position());
                entities.updatePosition(session.playerId, result.position().x(),
                        result.position().y(), result.position().z(), session.yaw.get());
            }
            NexoraMovementValidator.Position authoritative = session.position.get();
            if (send(connection, NexoraMovementPacketCodec.encodeServerPosition(authoritative))
                    != TransportSendResult.ACCEPTED) {
                connection.close();
                return;
            }
            if (result.accepted()) {
                broadcastPlayerState(connection, session);
            }
        } catch (RuntimeException malformedMovement) {
            connection.close();
        }
    }

    private void synchronizeNewPlayer(TransportConnection newcomer, PlayerSession joining) {
        for (Map.Entry<TransportConnection, PlayerSession> entry : playerSessions.entrySet()) {
            TransportConnection peer = entry.getKey();
            PlayerSession session = entry.getValue();
            if (peer == newcomer || !peer.isOpen()) continue;
            if (send(newcomer, encodePlayerState(session)) != TransportSendResult.ACCEPTED) {
                newcomer.close();
                return;
            }
            if (send(peer, encodePlayerState(joining)) != TransportSendResult.ACCEPTED) {
                peer.close();
            }
        }
    }

    private void broadcastPlayerState(TransportConnection sender, PlayerSession session) {
        byte[] packet = encodePlayerState(session);
        for (TransportConnection peer : playerSessions.keySet()) {
            if (peer == sender || !peer.isOpen()) continue;
            if (send(peer, packet) != TransportSendResult.ACCEPTED) peer.close();
        }
    }

    private void broadcastPlayerLeft(TransportConnection leaver, UUID playerId) {
        byte[] packet = NexoraPlayerSyncCodec.encodePlayerLeft(playerId);
        for (TransportConnection peer : playerSessions.keySet()) {
            if (peer == leaver || !peer.isOpen()) continue;
            if (send(peer, packet) != TransportSendResult.ACCEPTED) peer.close();
        }
    }

    private static byte[] encodePlayerState(PlayerSession session) {
        NexoraMovementValidator.Position position = session.position.get();
        return NexoraPlayerSyncCodec.encodePlayerState(
                new NexoraPlayerSyncCodec.PlayerState(
                        session.playerId, session.playerName, session.skinIndex, position.x(), position.y(), position.z(), session.yaw.get()));
    }

    private static final class PlayerSession {
        private final UUID playerId;
        private final String playerName;
        private final int skinIndex;
        private final AtomicReference<NexoraMovementValidator.Position> position;
        private final AtomicReference<Double> yaw = new AtomicReference<>(0.0);

        private PlayerSession(UUID playerId, String playerName, int skinIndex,
                              AtomicReference<NexoraMovementValidator.Position> position) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.skinIndex = skinIndex;
            this.position = position;
        }
    }

    private static boolean isMovementPacket(ByteBuffer payload) {
        ByteBuffer input = payload.slice();
        return input.remaining() >= 7
                && input.get(input.position()) == 'N'
                && input.get(input.position() + 1) == 'X'
                && input.get(input.position() + 2) == 'R'
                && input.get(input.position() + 3) == 'A'
                && Byte.toUnsignedInt(input.get(input.position() + 6)) == 7;
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
