package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.Objects;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Routes NetherNet peers through NetworkSettings and Login, then holds the
 * authenticated session in LOGIN_SUCCESS_SENT while resource packs and world
 * startup are completed by the post-login handler. A session enters PLAY only
 * when the host explicitly calls {@link #markPlayReady(TransportConnection)}.
 */
public final class BedrockInitialTransportRouter implements Consumer<TransportConnection>, AutoCloseable {
    private final BedrockNetworkSettings networkSettings;
    private final LoginAuthenticator loginAuthenticator;
    private final java.util.function.BiConsumer<TransportConnection, BedrockAuthenticatedPlayer> loginSuccessListener;
    private final BiConsumer<TransportConnection, TransportMessage> playMessageHandler;
    private final Duration handshakeTimeout;
    private final ScheduledExecutorService timeoutExecutor;
    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Consumer<TransportConnection> connectionCloseListener = ignored -> { };

    public BedrockInitialTransportRouter(BedrockNetworkSettings networkSettings,
                                         LoginAuthenticator loginAuthenticator,
                                         java.util.function.BiConsumer<TransportConnection, BedrockAuthenticatedPlayer> loginSuccessListener,
                                         BiConsumer<TransportConnection, TransportMessage> playMessageHandler) {
        this(networkSettings, loginAuthenticator, loginSuccessListener, playMessageHandler,
                Duration.ofSeconds(60));
    }

    public BedrockInitialTransportRouter(BedrockNetworkSettings networkSettings,
                                         LoginAuthenticator loginAuthenticator,
                                         java.util.function.BiConsumer<TransportConnection, BedrockAuthenticatedPlayer> loginSuccessListener,
                                         BiConsumer<TransportConnection, TransportMessage> playMessageHandler,
                                         Duration handshakeTimeout) {
        this.networkSettings = Objects.requireNonNull(networkSettings, "networkSettings");
        this.loginAuthenticator = Objects.requireNonNull(loginAuthenticator, "loginAuthenticator");
        this.loginSuccessListener = Objects.requireNonNull(loginSuccessListener, "loginSuccessListener");
        this.playMessageHandler = Objects.requireNonNull(playMessageHandler, "playMessageHandler");
        this.handshakeTimeout = Objects.requireNonNull(handshakeTimeout, "handshakeTimeout");
        if (handshakeTimeout.isNegative() || handshakeTimeout.isZero()
                || handshakeTimeout.toMillis() < 1
                || handshakeTimeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("Handshake timeout must be between 1 ms and 10 minutes");
        }
        this.timeoutExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "nexora-bedrock-handshake-timeouts");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Installs a lifecycle observer before the router accepts connections. */
    public synchronized void setConnectionCloseListener(Consumer<TransportConnection> listener) {
        Objects.requireNonNull(listener, "listener");
        if (!sessions.isEmpty()) {
            throw new IllegalStateException("Connection close listener must be installed before accepting peers");
        }
        this.connectionCloseListener = listener;
    }

    private void notifyConnectionClosed(TransportConnection connection) {
        try {
            connectionCloseListener.accept(connection);
        } catch (RuntimeException ignored) {
            // Lifecycle observers must not prevent transport/session cleanup.
        }
    }

    @Override
    public void accept(TransportConnection connection) {
        Objects.requireNonNull(connection, "connection");
        if (closed.get() || !connection.isOpen()) {
            connection.close();
            return;
        }
        Session session = new Session(connection,
                new BedrockInitialHandshakeProcessor(networkSettings));
        if (sessions.putIfAbsent(connection.id(), session) != null) {
            connection.close();
            return;
        }
        connection.setCloseListener(event -> remove(session));
        connection.setMessageListener(message -> receive(session, message));
    }

    private void receive(Session session, TransportMessage message) {
        if (session.closed.get()) {
            return;
        }
        synchronized (session) {
            if (session.closed.get()) {
                return;
            }
            if (session.processor.state() == BedrockHandshakeState.LOGIN_SUCCESS_SENT) {
                if (message.deliveryMode() != DeliveryMode.RELIABLE_ORDERED) {
                    close(session, "Post-login initialization packet arrived on unreliable channel");
                    return;
                }
                try {
                    // This handler owns resource-pack and world-startup responses
                    // until the host explicitly marks the session PLAY-ready.
                    playMessageHandler.accept(session.connection, message);
                } catch (RuntimeException failure) {
                    close(session, "Post-login initialization handler failed");
                }
                return;
            }
            if (session.processor.state() == BedrockHandshakeState.PLAY) {
                try {
                    playMessageHandler.accept(session.connection, message);
                } catch (RuntimeException failure) {
                    close(session, "Play message handler failed");
                }
                return;
            }
            if (message.deliveryMode() != DeliveryMode.RELIABLE_ORDERED) {
                close(session, "Bedrock handshake packet arrived on unreliable channel");
                return;
            }

            BedrockHandshakeFrameResult result;
            try {
                result = session.processor.receiveFrame(message.payload());
            } catch (RuntimeException malformed) {
                close(session, "Bedrock handshake processing failed");
                return;
            }

            Optional<ByteBuffer> outbound = result.outboundFrame();
            if (result.status() == BedrockHandshakeFrameStatus.NETWORK_SETTINGS_RESPONSE_READY
                    && outbound.isEmpty()) {
                close(session, "NetworkSettings response frame is missing");
                return;
            }
            if (outbound.isPresent()) {
                TransportSendResult sent = session.connection.send(
                        outbound.get(), DeliveryMode.RELIABLE_ORDERED);
                if (sent != TransportSendResult.ACCEPTED) {
                    close(session, "Could not enqueue NetworkSettings response");
                    return;
                }
                if (result.status() == BedrockHandshakeFrameStatus.NETWORK_SETTINGS_RESPONSE_READY) {
                    if (session.processor.markNetworkSettingsSent()
                            != BedrockHandshakeResult.ACCEPTED) {
                        close(session, "NetworkSettings state transition failed");
                        return;
                    }
                    scheduleTimeout(session, "Bedrock Login packet timed out");
                }
            }

            if (result.status() == BedrockHandshakeFrameStatus.AUTHENTICATION_REQUIRED) {
                authenticate(session);
            } else if (result.status() != BedrockHandshakeFrameStatus.NETWORK_SETTINGS_RESPONSE_READY) {
                close(session, "Bedrock initial handshake rejected: " + result.status());
            }
        }
    }

    private void authenticate(Session session) {
        scheduleTimeout(session, "Bedrock Login authentication timed out");
        BedrockLoginPacket login = session.processor.pendingLogin().orElse(null);
        if (login == null) {
            close(session, "Login packet was not retained for authentication");
            return;
        }
        final BedrockConnectionRequest connectionRequest;
        try {
            connectionRequest = login.parseConnectionRequest();
        } catch (RuntimeException malformedRequest) {
            close(session, "Bedrock Connection Request JSON is invalid");
            return;
        }
        final CompletionStage<BedrockAuthenticatedPlayer> authentication;
        try {
            java.util.Optional<NetherNetIdentityValidator.VerifiedIdentity> netherNetIdentity =
                    session.connection instanceof NetherNetAuthenticatedTransportConnection authenticated
                            ? authenticated.authenticatedIdentity()
                            : java.util.Optional.empty();
            if (netherNetIdentity.isPresent()) {
                NetherNetIdentityValidator.VerifiedIdentity identity = netherNetIdentity.get();
                if (!connectionRequest.isTokenPayload()
                        || !identity.gameServerTokenSha256().equals(
                                NetherNetIdentityValidator.tokenSha256(connectionRequest.token()))) {
                    throw new SecurityException(
                            "Bedrock Login token does not match the authenticated NetherNet offer");
                }
                if (identity.xuid() == null || identity.xuid().isBlank()
                        || identity.displayName() == null || identity.displayName().isBlank()) {
                    throw new SecurityException("Verified NetherNet identity is missing player claims");
                }
                String clientDataJson = BedrockClientDataJwtVerifier.verifyAndReadPayload(
                        login.clientDataJwt(), identity.clientPublicKey());
                authentication = java.util.concurrent.CompletableFuture.completedFuture(
                        new BedrockAuthenticatedPlayer(identity.xuid(), identity.displayName(),
                                identity.clientPublicKey(), clientDataJson));
            } else {
                authentication = loginAuthenticator.authenticate(
                        session.connection, login, connectionRequest);
            }
            if (authentication == null) {
                close(session, "Login authenticator returned no result");
                return;
            }
        } catch (Exception failure) {
            close(session, "Login authentication failed");
            return;
        }
        authentication.whenComplete((accepted, failure) -> {
            if (session.closed.get()) {
                return;
            }
            if (failure != null || accepted == null) {
                close(session, "Login authentication was rejected");
                return;
            }
            synchronized (session) {
                cancelTimeout(session);
                if (session.closed.get()) {
                    return;
                }
                ByteBuffer loginSuccess = encodeLoginSuccessFrame();
                TransportSendResult sent = session.connection.send(
                        loginSuccess, DeliveryMode.RELIABLE_ORDERED);
                if (sent != TransportSendResult.ACCEPTED) {
                    close(session, "Could not send Bedrock LoginSuccess status");
                    return;
                }
                if (session.processor.markAuthenticated() != BedrockHandshakeResult.ACCEPTED) {
                    close(session, "Could not record Bedrock LoginSuccess state");
                    return;
                }
                scheduleTimeout(session, "Bedrock resource-pack/world initialization timed out");
                try {
                    loginSuccessListener.accept(session.connection, accepted);
                } catch (RuntimeException failureToStartPlay) {
                    close(session, "Could not initialize Bedrock post-login session");
                }
            }
        });
    }

    /**
     * Marks the session playable only after resource-pack negotiation and the
     * complete StartGame/world initialization sequence have been sent.
     */
    public BedrockHandshakeResult markPlayReady(TransportConnection connection) {
        Objects.requireNonNull(connection, "connection");
        Session session = sessions.get(connection.id());
        if (session == null || session.connection != connection) {
            return BedrockHandshakeResult.INVALID_STATE;
        }
        synchronized (session) {
            if (session.closed.get()) {
                return BedrockHandshakeResult.SESSION_CLOSED;
            }
            BedrockHandshakeResult result = session.processor.markPlayReady();
            if (result == BedrockHandshakeResult.ACCEPTED) {
                cancelTimeout(session);
            }
            return result;
        }
    }

    private ByteBuffer encodeLoginSuccessFrame() {
        BedrockGamePacket packet = new BedrockGamePacket(
                new BedrockPacketHeader(BedrockPlayStatusPacketCodec.PACKET_ID, 0, 0),
                bytes(BedrockPlayStatusPacketCodec.encodePayload(BedrockPlayStatus.LOGIN_SUCCESS)));
        ByteBuffer batch = BedrockPacketBatchCodec.encodeUncompressed(java.util.List.of(packet));
        return BedrockBatchCompressionCodec.encodeAfterNetworkSettings(batch, networkSettings);
    }

    private static byte[] bytes(ByteBuffer source) {
        byte[] value = new byte[source.remaining()];
        source.get(value);
        return value;
    }

    private void remove(Session session) {
        if (session.closed.compareAndSet(false, true)) {
            cancelTimeout(session);
            sessions.remove(session.connection.id(), session);
            session.processor.close();
            notifyConnectionClosed(session.connection);
        }
    }

    private void close(Session session, String reason) {
        if (session.closed.compareAndSet(false, true)) {
            cancelTimeout(session);
            sessions.remove(session.connection.id(), session);
            session.processor.close();
            notifyConnectionClosed(session.connection);
            session.connection.close();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (Session session : sessions.values()) {
            close(session, "Bedrock transport router stopped");
        }
        sessions.clear();
        timeoutExecutor.shutdownNow();
    }

    private void scheduleTimeout(Session session, String reason) {
        if (session.closed.get() || closed.get()) {
            return;
        }
        cancelTimeout(session);
        try {
            session.timeoutFuture = timeoutExecutor.schedule(
                    () -> close(session, reason),
                    handshakeTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException stopping) {
            close(session, "Bedrock transport router stopped");
        }
    }

    private static void cancelTimeout(Session session) {
        ScheduledFuture<?> future = session.timeoutFuture;
        session.timeoutFuture = null;
        if (future != null) {
            future.cancel(false);
        }
    }

    @FunctionalInterface
    public interface LoginAuthenticator {
        CompletionStage<BedrockAuthenticatedPlayer> authenticate(TransportConnection connection,
                                              BedrockLoginPacket login,
                                              BedrockConnectionRequest connectionRequest);
    }

    private static final class Session {
        private final TransportConnection connection;
        private final BedrockInitialHandshakeProcessor processor;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile ScheduledFuture<?> timeoutFuture;

        private Session(TransportConnection connection,
                        BedrockInitialHandshakeProcessor processor) {
            this.connection = connection;
            this.processor = processor;
        }
    }
}
