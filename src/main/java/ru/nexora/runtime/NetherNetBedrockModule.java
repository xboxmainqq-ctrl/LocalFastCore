package ru.nexora.runtime;

import java.util.Objects;

/**
 * Owns the complete desktop NetherNet-to-Bedrock ingress stack.
 * Register this module with {@link NexoraServer}; it installs the transport
 * listener, starts WebRTC first, then exposes HTTP signaling.
 */
public final class NetherNetBedrockModule implements RuntimeModule {
    private final NetherNetPeerHost peerHost;
    private final NetworkTransport transport;
    private final NetherNetSignalingServer signalingServer;
    private final BedrockInitialTransportRouter router;
    private boolean started;
    private boolean shutdownRequested;
    private boolean closed;

    public NetherNetBedrockModule(
            NetherNetPeerHost peerHost,
            NetworkTransport transport,
            NetherNetSignalingServer signalingServer,
            BedrockInitialTransportRouter router
    ) {
        this.peerHost = Objects.requireNonNull(peerHost, "peerHost");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.signalingServer = Objects.requireNonNull(signalingServer, "signalingServer");
        this.router = Objects.requireNonNull(router, "router");
        if (peerHost != transport) {
            throw new IllegalArgumentException(
                    "NetherNet peer host and network transport must be the same host instance");
        }
        if (!this.signalingServer.usesPeerHost(this.peerHost)) {
            throw new IllegalArgumentException(
                    "NetherNet signaling server must use the same peer host instance");
        }
    }

    @Override
    public String id() {
        return "nethernet-bedrock";
    }

    @Override
    public synchronized void start() throws Exception {
        if (closed || shutdownRequested) {
            throw new IllegalStateException("NetherNet Bedrock module cannot be restarted after shutdown");
        }
        if (started) {
            throw new IllegalStateException("NetherNet Bedrock module is already started");
        }
        try {
            transport.setConnectionListener(router);
            transport.start();
            signalingServer.start();
            started = true;
        } catch (Exception failure) {
            shutdownRequested = true;
            router.close();
            boolean cleanupSucceeded = true;
            try {
                signalingServer.stop();
            } catch (RuntimeException cleanupFailure) {
                cleanupSucceeded = false;
                failure.addSuppressed(cleanupFailure);
            }
            try {
                transport.stop();
            } catch (Exception cleanupFailure) {
                cleanupSucceeded = false;
                failure.addSuppressed(cleanupFailure);
            }
            closed = cleanupSucceeded;
            throw failure;
        }
    }

    @Override
    public synchronized void stop() throws Exception {
        if (closed || (!started && !shutdownRequested)) {
            return;
        }
        started = false;
        shutdownRequested = true;
        Exception failure = null;
        try {
            signalingServer.stop();
        } catch (RuntimeException stopFailure) {
            failure = stopFailure;
        }
        router.close();
        try {
            transport.stop();
        } catch (Exception stopFailure) {
            if (failure == null) {
                failure = stopFailure;
            } else {
                failure.addSuppressed(stopFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
        closed = true;
    }
}
