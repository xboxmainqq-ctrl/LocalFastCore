package ru.nexora.runtime;

import java.util.Objects;

/**
 * Starts NetherNet signaling and routes new peers into the private NEXORA protocol.
 * This module is for the first-party Android client, not generic Bedrock clients.
 */
public final class NetherNetPrivateProtocolModule implements RuntimeModule {
    private final NetherNetPeerHost peerHost;
    private final NetworkTransport transport;
    private final NetherNetSignalingServer signalingServer;
    private final NexoraPrivateProtocolHandler protocolHandler;
    private boolean started;
    private boolean closed;

    public NetherNetPrivateProtocolModule(
            NetherNetPeerHost peerHost,
            NetworkTransport transport,
            NetherNetSignalingServer signalingServer,
            NexoraPrivateProtocolHandler protocolHandler) {
        this.peerHost = Objects.requireNonNull(peerHost, "peerHost");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.signalingServer = Objects.requireNonNull(signalingServer, "signalingServer");
        this.protocolHandler = Objects.requireNonNull(protocolHandler, "protocolHandler");
        if (peerHost != transport) {
            throw new IllegalArgumentException(
                    "NetherNet peer host and network transport must be the same host instance");
        }
        if (!signalingServer.usesPeerHost(peerHost)) {
            throw new IllegalArgumentException(
                    "NetherNet signaling server must use the same peer host instance");
        }
    }

    @Override
    public String id() {
        return "nethernet-nexora-private";
    }

    @Override
    public synchronized void start() throws Exception {
        if (closed || started) {
            throw new IllegalStateException("NEXORA private protocol module cannot be restarted");
        }
        try {
            transport.setConnectionListener(protocolHandler);
            transport.start();
            signalingServer.start();
            started = true;
        } catch (Exception failure) {
            try {
                signalingServer.stop();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            try {
                transport.stop();
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            closed = true;
            throw failure;
        }
    }

    @Override
    public synchronized void stop() {
        if (closed) {
            return;
        }
        started = false;
        RuntimeException failure = null;
        try {
            signalingServer.stop();
        } catch (RuntimeException stopFailure) {
            failure = stopFailure;
        }
        try {
            transport.stop();
        } catch (Exception stopFailure) {
            if (failure == null) {
                failure = new IllegalStateException("Could not stop NEXORA transport", stopFailure);
            } else {
                failure.addSuppressed(stopFailure);
            }
        }
        closed = true;
        if (failure != null) {
            throw failure;
        }
    }
}
