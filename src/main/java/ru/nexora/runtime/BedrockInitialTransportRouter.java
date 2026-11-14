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
 * Routes newly established transport peers through the initial Bedrock
 * NetworkSettings/Login handshake before handing authenticated peers to play.
 */
public final class BedrockInitialTransportRouter implements Consumer<TransportConnection>, AutoCloseable {
    private final BedrockNetworkSettings networkSettings;
    private final LoginAuthenticator loginAuthenticator;
    private final Consumer<TransportConnection> playListener;
    private final BiConsumer<TransportConnection, TransportMessage> playMessageHandler;
    private final Duration handshakeTimeout;
    private final ScheduledExecutorService timeoutExecutor;
    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public BedrockInitialTransportRouter(BedrockNetworkSettings networkSettings,
                                         LoginAuthenticator loginAuthenticator,
                                         Consumer<TransportConnection> playListener,
                                         BiConsumer<TransportConnection, TransportMessage> playMessageHandler) {
        this(networkSettings, loginAuthenticator, playListener, playMessageHandler,
                Duration.ofSeconds(60));
    }

    public BedrockInitialTransportRouter(BedrockNetworkSettings networkSettings,
                                         LoginAuthenticator loginAuthenticator,
                                         Consumer<TransportConnection> playListener,
                                         BiConsumer<TransportConnection, TransportMessage> playMessageHandler,
                                         Duration handshakeTimeout) {
        this.networkSettings = Objects.requireNonNull(networkSettings, "networkSettings");
        this.loginAuthenticator = Objects.requireNonNull(loginAuthenticator, "loginAuthenticator");
        this.playListener = Objects.requireNonNull(playListener, "playListener");
        this.playMessageHandler = Objects.requireNonNull(playMessageHandler, "playMessageHandler");
        this.handshakeTimeout = Objects.requireNonNull(handshakeTimeout, "handshakeTimeout");
        if (handshakeTimeout.isNegative() || handshakeTimeout.isZero()
                || handshakeTimeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("Handshake timeout must be between 1 ms and 10 minutes");
        }
        this.timeoutExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "nexora-bedrock-handshake-timeouts");
            thread.setDaemon(true);
            return thread;
        });
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
        final CompletionStage<Boolean> authentication;
        try {
            authentication = loginAuthenticator.authenticate(session.connection, login, connectionRequest);
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
            if (failure != null || !Boolean.TRUE.equals(accepted)) {
                close(session, "Login authentication was rejected");
                return;
            }
            synchronized (session) {
                cancelTimeout(session);
                if (session.closed.get()
                        || session.processor.markAuthenticated() != BedrockHandshakeResult.ACCEPTED) {
                    close(session, "Could not enter Bedrock play state");
                    return;
                }
                try {
                    playListener.accept(session.connection);
                } catch (RuntimeException failureToStartPlay) {
                    close(session, "Could not initialize Bedrock play session");
                }
            }
        });
    }

    private void remove(Session session) {
        if (session.closed.compareAndSet(false, true)) {
            cancelTimeout(session);
            sessions.remove(session.connection.id(), session);
            session.processor.close();
        }
    }

    private void close(Session session, String reason) {
        if (session.closed.compareAndSet(false, true)) {
            cancelTimeout(session);
            sessions.remove(session.connection.id(), session);
            session.processor.close();
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
        session.timeoutFuture = timeoutExecutor.schedule(
                () -> close(session, reason),
                handshakeTimeout.toMillis(), TimeUnit.MILLISECONDS);
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
        CompletionStage<Boolean> authenticate(TransportConnection connection,
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
