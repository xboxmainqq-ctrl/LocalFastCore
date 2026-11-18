package ru.nexora.network;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the network-facing runtime and its dependent Bedrock session state.
 *
 * <p>Startup opens discovery before HTTP signaling. Shutdown reverses the
 * dependency chain: stop discovery, stop signaling admission, close WebRTC,
 * then persist and clear active Bedrock sessions.</p>
 */
public final class NetworkRuntimeModule implements AutoCloseable {
    @FunctionalInterface
    public interface StartAction {
        void start() throws Exception;
    }

    public interface ManagedService extends AutoCloseable {
        void start() throws Exception;
        @Override
        void close() throws Exception;
    }

    private final ManagedService discovery;
    private final ManagedService signaling;
    private final AutoCloseable negotiator;
    private final AutoCloseable sessions;
    private final AtomicBoolean closed = new AtomicBoolean();
    private boolean started;

    public NetworkRuntimeModule(
            ManagedService discovery,
            ManagedService signaling,
            AutoCloseable negotiator,
            AutoCloseable sessions
    ) {
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.signaling = Objects.requireNonNull(signaling, "signaling");
        this.negotiator = Objects.requireNonNull(negotiator, "negotiator");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    public static ManagedService service(StartAction start, AutoCloseable close) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(close, "close");
        return new ManagedService() {
            @Override
            public void start() throws Exception {
                start.start();
            }

            @Override
            public void close() throws Exception {
                close.close();
            }
        };
    }

    public synchronized void start() throws Exception {
        if (closed.get()) throw new IllegalStateException("Network runtime module is closed");
        if (started) return;
        try {
            discovery.start();
            signaling.start();
            started = true;
        } catch (Exception failure) {
            try {
                close();
            } catch (Exception closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public synchronized void close() throws Exception {
        if (!closed.compareAndSet(false, true)) return;
        Exception failure = null;
        failure = close(discovery, failure);
        failure = close(signaling, failure);
        failure = close(negotiator, failure);
        failure = close(sessions, failure);
        if (failure != null) throw failure;
    }

    private static Exception close(AutoCloseable resource, Exception failure) {
        try {
            resource.close();
        } catch (Exception exception) {
            if (failure == null) return exception;
            failure.addSuppressed(exception);
        }
        return failure;
    }
}
