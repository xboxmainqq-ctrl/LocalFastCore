package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Connects established NetherNet transport peers to the Bedrock initial
 * handshake processor. All transport callbacks are handled without mutating
 * world state; gameplay integration remains a later tick-thread stage.
 */
public final class BedrockConnectionRouter implements RuntimeModule {
    private final NetworkTransport transport;
    private final BedrockNetworkSettings networkSettings;
    private final Consumer<BedrockPendingLogin> authenticationListener;
    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
    private volatile boolean started;

    public BedrockConnectionRouter(
            NetworkTransport transport,
            BedrockNetworkSettings networkSettings,
            Consumer<BedrockPendingLogin> authenticationListener
    ) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.networkSettings = Objects.requireNonNull(networkSettings, "networkSettings");
        this.authenticationListener = Objects.requireNonNull(
                authenticationListener, "authenticationListener");
    }

    @Override
    public String id() {
        return "bedrock-connection-router";
    }

    @Override
    public synchronized void start() throws Exception {
        if (started) {
            throw new IllegalStateException("Bedrock connection router is already started");
        }
        transport.setConnectionListener(this::accept);
        transport.start();
        started = true;
    }

    @Override
    public synchronized void stop() throws Exception {
        if (!started) {
            return;
        }
        started = false;
        for (Session session : sessions.values()) {
            session.close();
        }
        sessions.clear();
        transport.stop();
    }

    private void accept(TransportConnection connection) {
        Objects.requireNonNull(connection, "connection");
        if (!started || !connection.isOpen()) {
            connection.close();
            return;
        }
        Session session = new Session(connection);
        Session previous = sessions.putIfAbsent(connection.id(), session);
        if (previous != null) {
            connection.close();
            return;
        }
        connection.setCloseListener(event -> remove(session));
        connection.setMessageListener(session::receive);
        if (!connection.isOpen()) {
            remove(session);
        }
    }

    private void remove(Session session) {
        if (sessions.remove(session.connection.id(), session)) {
            session.close();
        }
    }

    private final class Session {
        private final TransportConnection connection;
        private final BedrockInitialHandshakeProcessor processor =
                new BedrockInitialHandshakeProcessor(networkSettings);
        private final AtomicBoolean closed = new AtomicBoolean();

        private Session(TransportConnection connection) {
            this.connection = connection;
        }

        private void receive(TransportMessage message) {
            if (closed.get()) {
                return;
            }
            if (message.deliveryMode() != DeliveryMode.RELIABLE_ORDERED) {
                close();
                return;
            }

            BedrockHandshakeFrameResult result = processor.receiveFrame(message.payload());
            switch (result.status()) {
                case NETWORK_SETTINGS_RESPONSE_READY -> sendNetworkSettings(result);
                case AUTHENTICATION_REQUIRED -> handOffAuthentication();
                case REJECTED_PACKET, MALFORMED_FRAME, UNSUPPORTED_COMPRESSION,
                        INVALID_STATE, SESSION_CLOSED -> close();
            }
        }

        private void sendNetworkSettings(BedrockHandshakeFrameResult result) {
            ByteBuffer frame = result.outboundFrame().orElse(null);
            if (frame == null || connection.send(frame, DeliveryMode.RELIABLE_ORDERED)
                    != TransportSendResult.ACCEPTED) {
                close();
                return;
            }
            if (processor.markNetworkSettingsSent() != BedrockHandshakeResult.ACCEPTED) {
                close();
            }
        }

        private void handOffAuthentication() {
            BedrockLoginPacket login = processor.pendingLogin().orElse(null);
            if (login == null) {
                close();
                return;
            }
            try {
                authenticationListener.accept(
                        new BedrockPendingLogin(connection, login, processor));
            } catch (RuntimeException failure) {
                close();
            }
        }

        private void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            processor.close();
            sessions.remove(connection.id(), this);
            if (connection.isOpen()) {
                connection.close();
            }
        }
    }
}
