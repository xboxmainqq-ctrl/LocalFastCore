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
    }

    @Override
    public String id() {
        return "nethernet-bedrock";
    }

    @Override
    public synchronized void start() throws Exception {
        if (closed) {
            throw new IllegalStateException("NetherNet Bedrock module cannot be restarted after close");
        }
        if (started) {
            throw new IllegalStateException("NetherNet Bedrock module is already started");
        }
        transport.setConnectionListener(router);
        transport.start();
        try {
            signalingServer.start();
            started = true;
        } catch (Exception failure) {
            closed = true;
            router.close();
            try {
                transport.stop();
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    @Override
    public synchronized void stop() throws Exception {
        if (!started) {
            return;
        }
        started = false;
        closed = true;
        signalingServer.stop();
        router.close();
        transport.stop();
    }
}
