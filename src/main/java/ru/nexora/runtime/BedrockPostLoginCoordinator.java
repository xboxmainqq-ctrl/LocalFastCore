package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Coordinates the protocol-2225 post-LoginSuccess exchange.
 *
 * <p>It sends ResourcePacksInfo and ResourcePackStack packets,
 * validates the corresponding client responses, then delegates StartGame and
 * initial world delivery to a world-startup implementation. PLAY is entered
 * only after that implementation completes successfully.</p>
 */
public final class BedrockPostLoginCoordinator implements
        BiConsumer<TransportConnection, BedrockAuthenticatedPlayer>, AutoCloseable {

    @FunctionalInterface
    public interface WorldStartupHandler {
        /**
         * Starts the world session. Completion means StartGame and all required
         * initial world packets have been accepted by the transport.
         */
        CompletionStage<Void> start(
                TransportConnection connection,
                BedrockAuthenticatedPlayer player
        );
    }

    private enum Phase {
        WAITING_FOR_PACK_RESPONSE,
        WAITING_FOR_STACK_RESPONSE,
        STARTING_WORLD
    }

    private final BedrockNetworkSettings networkSettings;
    private final String baseGameVersion;
    private final WorldStartupHandler worldStartupHandler;
    private final BiConsumer<TransportConnection, TransportMessage> playMessageHandler;
    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
    private volatile Function<TransportConnection, BedrockHandshakeResult> playReadyTransition;
    private volatile boolean closed;

    public BedrockPostLoginCoordinator(
            BedrockNetworkSettings networkSettings,
            String baseGameVersion,
            WorldStartupHandler worldStartupHandler,
            BiConsumer<TransportConnection, TransportMessage> playMessageHandler
    ) {
        this.networkSettings = Objects.requireNonNull(networkSettings, "networkSettings");
        this.baseGameVersion = Objects.requireNonNull(baseGameVersion, "baseGameVersion");
        if (baseGameVersion.isBlank()
                || baseGameVersion.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 64) {
            throw new IllegalArgumentException("baseGameVersion must be nonblank and at most 64 UTF-8 bytes");
        }
        this.worldStartupHandler = Objects.requireNonNull(worldStartupHandler, "worldStartupHandler");
        this.playMessageHandler = Objects.requireNonNull(playMessageHandler, "playMessageHandler");
    }

    /**
     * Wires the coordinator into its router. Call immediately after router
     * construction and before the transport module starts accepting peers.
     */
    public void attachTo(BedrockInitialTransportRouter router) {
        Objects.requireNonNull(router, "router");
        router.setConnectionCloseListener(this::onConnectionClosed);
        bindPlayReadyTransition(router::markPlayReady);
    }

    /**
     * Bind this to router::markPlayReady after constructing the router and
     * before accepting any connections.
     */
    public synchronized void bindPlayReadyTransition(
            Function<TransportConnection, BedrockHandshakeResult> transition
    ) {
        Objects.requireNonNull(transition, "transition");
        if (closed || !sessions.isEmpty() || playReadyTransition != null) {
            throw new IllegalStateException("Play-ready transition must be bound exactly once before use");
        }
        playReadyTransition = transition;
    }

    @Override
    public void accept(TransportConnection connection, BedrockAuthenticatedPlayer player) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(player, "player");
        if (closed || !connection.isOpen() || playReadyTransition == null) {
            connection.close();
            return;
        }
        Session session = new Session(connection, player);
        if (sessions.putIfAbsent(connection.id(), session) != null) {
            connection.close();
            return;
        }
        synchronized (session) {
            if (!sendPacket(connection, BedrockResourcePackCodec.RESOURCE_PACKS_INFO_PACKET_ID,
                    BedrockResourcePackCodec.encodeEmptyResourcePacksInfoPayload())) {
                fail(session);
            }
        }
    }

    public void handleMessage(TransportConnection connection, TransportMessage message) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(message, "message");
        Session session = sessions.get(connection.id());
        if (session == null) {
            if (connection.isOpen()) {
                playMessageHandler.accept(connection, message);
            }
            return;
        }
        synchronized (session) {
            if (session.closed || !connection.isOpen()) {
                fail(session);
                return;
            }
            if (message.deliveryMode() != DeliveryMode.RELIABLE_ORDERED) {
                fail(session);
                return;
            }
            if (session.phase == Phase.STARTING_WORLD) {
                // StartGame may cause the client to request subchunks before
                // the startup stage completes. Route those packets through
                // the normal gameplay handler instead of parsing them as
                // ResourcePackClientResponse.
                try {
                    playMessageHandler.accept(connection, message);
                } catch (RuntimeException gameplayFailure) {
                    fail(session);
                }
                return;
            }
            final BedrockResourcePackCodec.ClientResponse response;
            try {
                response = decodeResponse(message.payload());
            } catch (RuntimeException malformed) {
                fail(session);
                return;
            }
            switch (response.type()) {
                case REFUSED -> fail(session);
                case SEND_PACKS -> {
                    // Until a server pack set is configured, no pack identifiers
                    // can be requested by the client.
                    if (!response.packIds().isEmpty()
                            || session.phase != Phase.WAITING_FOR_PACK_RESPONSE) {
                        fail(session);
                    }
                }
                case HAVE_ALL_PACKS -> {
                    if (session.phase != Phase.WAITING_FOR_PACK_RESPONSE
                            || !response.packIds().isEmpty()) {
                        fail(session);
                        return;
                    }
                    session.phase = Phase.WAITING_FOR_STACK_RESPONSE;
                    if (!sendPacket(connection, BedrockResourcePackCodec.RESOURCE_PACK_STACK_PACKET_ID,
                            BedrockResourcePackCodec.encodeEmptyResourcePackStackPayload(baseGameVersion))) {
                        fail(session);
                    }
                }
                case COMPLETED -> {
                    if (session.phase != Phase.WAITING_FOR_STACK_RESPONSE
                            || !response.packIds().isEmpty()) {
                        fail(session);
                        return;
                    }
                    session.phase = Phase.STARTING_WORLD;
                    startWorld(session);
                }
            }
        }
    }

    /** Called by the router's close listener to release per-connection state. */
    public void onConnectionClosed(TransportConnection connection) {
        if (connection != null) {
            Session session = sessions.remove(connection.id());
            if (session != null) {
                synchronized (session) {
                    session.closed = true;
                }
            }
        }
    }

    private void startWorld(Session session) {
        final CompletionStage<Void> startup;
        try {
            startup = worldStartupHandler.start(session.connection, session.player);
            if (startup == null) {
                throw new IllegalStateException("World startup handler returned no completion stage");
            }
        } catch (RuntimeException failure) {
            fail(session);
            return;
        }
        startup.whenComplete((ignored, failure) -> {
            if (failure != null || !session.connection.isOpen()) {
                fail(session);
                return;
            }
            synchronized (session) {
                if (session.closed || sessions.get(session.connection.id()) != session) {
                    return;
                }
            }

            // Do not hold the coordinator session lock while entering the
            // router: router callbacks can arrive while holding its own lock.
            final BedrockHandshakeResult readyResult;
            try {
                Function<TransportConnection, BedrockHandshakeResult> transition =
                        playReadyTransition;
                readyResult = transition == null
                        ? BedrockHandshakeResult.INVALID_STATE
                        : transition.apply(session.connection);
            } catch (RuntimeException transitionFailure) {
                fail(session);
                return;
            }

            synchronized (session) {
                if (session.closed || sessions.get(session.connection.id()) != session) {
                    return;
                }
                if (readyResult != BedrockHandshakeResult.ACCEPTED) {
                    fail(session);
                    return;
                }
                session.closed = true;
                sessions.remove(session.connection.id(), session);
            }
        });
    }

    private BedrockResourcePackClientResponse decodeResponse(ByteBuffer frame) {
        ByteBuffer batch = BedrockBatchCompressionCodec.decodeAfterNetworkSettings(
                frame, networkSettings, BedrockPacketBatchCodec.DEFAULT_MAX_BATCH_BYTES);
        List<BedrockGamePacket> packets = BedrockPacketBatchCodec.decodeUncompressed(batch);
        if (packets.size() != 1) {
            throw new IllegalArgumentException("Expected exactly one resource-pack response packet");
        }
        BedrockGamePacket packet = packets.get(0);
        if (packet.header().packetId() != BedrockResourcePackCodec.RESOURCE_PACK_CLIENT_RESPONSE_PACKET_ID
                || packet.header().senderSubClientId() != 0
                || packet.header().targetSubClientId() != 0) {
            throw new IllegalArgumentException("Unexpected Bedrock packet during resource-pack negotiation");
        }
        return BedrockResourcePackClientResponsePacketCodec.decodePayload(packet.payloadBuffer());
    }

    private boolean sendPacket(
            TransportConnection connection,
            int packetId,
            ByteBuffer payload
    ) {
        BedrockGamePacket packet = new BedrockGamePacket(
                new BedrockPacketHeader(packetId, 0, 0),
                bytes(payload));
        ByteBuffer batch = BedrockPacketBatchCodec.encodeUncompressed(List.of(packet));
        ByteBuffer frame = BedrockBatchCompressionCodec.encodeAfterNetworkSettings(batch, networkSettings);
        return connection.send(frame, DeliveryMode.RELIABLE_ORDERED) == TransportSendResult.ACCEPTED;
    }

    private void fail(Session session) {
        synchronized (session) {
            if (session.closed) {
                return;
            }
            session.closed = true;
            sessions.remove(session.connection.id(), session);
        }
        session.connection.close();
    }

    private static byte[] bytes(ByteBuffer source) {
        byte[] value = new byte[source.remaining()];
        source.get(value);
        return value;
    }

    @Override
    public void close() {
        closed = true;
        for (Session session : sessions.values()) {
            fail(session);
        }
        sessions.clear();
    }

    private static final class Session {
        private final TransportConnection connection;
        private final BedrockAuthenticatedPlayer player;
        private Phase phase = Phase.WAITING_FOR_PACK_RESPONSE;
        private boolean closed;

        private Session(TransportConnection connection, BedrockAuthenticatedPlayer player) {
            this.connection = connection;
            this.player = player;
        }
    }
}
