package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Completes the empty-resource-pack login exchange used by the initial NEXORA
 * world bootstrap. It does not download or serve non-empty resource packs.
 *
 * <p>Wire it as the router's login-success listener and post-login message
 * handler. Register {@link #remove(TransportConnection)} with the router's
 * additional connection-close listeners.</p>
 */
public final class BedrockResourcePackNegotiator implements AutoCloseable {
    private final BedrockNetworkSettings networkSettings;
    private final String gameVersion;
    private final Consumer<TransportConnection> readyListener;
    private final BiConsumer<TransportConnection, TransportMessage> playMessageHandler;
    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public BedrockResourcePackNegotiator(BedrockNetworkSettings networkSettings,
                                         String gameVersion,
                                         Consumer<TransportConnection> readyListener,
                                         BiConsumer<TransportConnection, TransportMessage> playMessageHandler) {
        this.networkSettings = Objects.requireNonNull(networkSettings, "networkSettings");
        this.gameVersion = Objects.requireNonNull(gameVersion, "gameVersion");
        this.readyListener = Objects.requireNonNull(readyListener, "readyListener");
        this.playMessageHandler = Objects.requireNonNull(playMessageHandler, "playMessageHandler");
    }

    /** Starts the no-pack resource exchange immediately after LoginSuccess. */
    public void onLoginSuccess(TransportConnection connection, BedrockAuthenticatedPlayer player) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(player, "player");
        if (closed.get() || !connection.isOpen()) {
            connection.close();
            return;
        }
        Session session = new Session(connection);
        if (sessions.putIfAbsent(connection.id(), session) != null) {
            connection.close();
            return;
        }
        if (!sendPacket(connection, BedrockResourcePacksInfoPacketCodec.PACKET_ID,
                BedrockResourcePacksInfoPacketCodec.encodeEmptyPayload())) {
            remove(connection);
            connection.close();
        }
    }

    /** Handles ResourcePackClientResponse until the pack stack is completed. */
    public void onMessage(TransportConnection connection, TransportMessage message) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(message, "message");
        Session session = sessions.get(connection.id());
        if (session == null) {
            playMessageHandler.accept(connection, message);
            return;
        }
        synchronized (session) {
            if (session.closed || !connection.isOpen()) {
                remove(connection);
                return;
            }
            if (message.deliveryMode() != DeliveryMode.RELIABLE_ORDERED) {
                reject(session);
                return;
            }
            final BedrockResourcePackClientResponse response;
            try {
                ByteBuffer batch = BedrockBatchCompressionCodec.decodeAfterNetworkSettings(
                        message.payload(), networkSettings);
                List<BedrockGamePacket> packets = BedrockPacketBatchCodec.decode(batch);
                if (packets.size() != 1) {
                    throw new IllegalArgumentException("Expected one resource-pack response packet");
                }
                BedrockGamePacket packet = packets.get(0);
                if (packet.header().packetId() != BedrockResourcePackClientResponsePacketCodec.PACKET_ID
                        || packet.header().senderSubClientId() != 0
                        || packet.header().targetSubClientId() != 0) {
                    throw new IllegalArgumentException("Unexpected resource-pack response packet header");
                }
                response = BedrockResourcePackClientResponsePacketCodec.decodePayload(
                        packet.payloadBuffer());
            } catch (RuntimeException malformed) {
                reject(session);
                return;
            }

            if (session.stage == Stage.AWAITING_HAVE_ALL_PACKS) {
                if (response.status() != BedrockResourcePackClientResponse.Status.HAVE_ALL_PACKS
                        || !response.packIds().isEmpty()) {
                    reject(session);
                    return;
                }
                if (!sendPacket(connection, BedrockResourcePackStackPacketCodec.PACKET_ID,
                        BedrockResourcePackStackPacketCodec.encodeEmptyPayload(gameVersion))) {
                    reject(session);
                    return;
                }
                session.stage = Stage.AWAITING_COMPLETED;
                return;
            }

            if (session.stage == Stage.AWAITING_COMPLETED
                    && response.status() == BedrockResourcePackClientResponse.Status.COMPLETED
                    && response.packIds().isEmpty()) {
                session.closed = true;
                sessions.remove(connection.id(), session);
                try {
                    readyListener.accept(connection);
                } catch (RuntimeException failure) {
                    connection.close();
                }
                return;
            }
            reject(session);
        }
    }

    /** Removes per-peer negotiation state when the transport closes. */
    public void remove(TransportConnection connection) {
        if (connection == null) {
            return;
        }
        Session session = sessions.remove(connection.id());
        if (session != null) {
            synchronized (session) {
                session.closed = true;
            }
        }
    }

    private boolean sendPacket(TransportConnection connection, int packetId, ByteBuffer payload) {
        BedrockGamePacket packet = new BedrockGamePacket(
                new BedrockPacketHeader(packetId, 0, 0), bytes(payload));
        ByteBuffer batch = BedrockPacketBatchCodec.encodeUncompressed(List.of(packet));
        ByteBuffer envelope = BedrockBatchCompressionCodec.encodeAfterNetworkSettings(
                batch, networkSettings);
        return connection.send(envelope, DeliveryMode.RELIABLE_ORDERED)
                == TransportSendResult.ACCEPTED;
    }

    private void reject(Session session) {
        session.closed = true;
        sessions.remove(session.connection.id(), session);
        session.connection.close();
    }

    private static byte[] bytes(ByteBuffer source) {
        byte[] result = new byte[source.remaining()];
        source.duplicate().get(result);
        return result;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (Session session : sessions.values()) {
            session.closed = true;
        }
        sessions.clear();
    }

    private enum Stage {
        AWAITING_HAVE_ALL_PACKS,
        AWAITING_COMPLETED
    }

    private static final class Session {
        private final TransportConnection connection;
        private Stage stage = Stage.AWAITING_HAVE_ALL_PACKS;
        private boolean closed;

        private Session(TransportConnection connection) {
            this.connection = connection;
        }
    }
}
