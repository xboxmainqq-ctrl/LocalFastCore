package ru.nexora.runtime;

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * Owns the startup order for the WebRTC transport, Bedrock handshake router,
 * and NetherNet HTTP signaling endpoint.
 */
public final class NetherNetServerTransportStack implements RuntimeModule {
    private final WebRtcJavaNetherNetPeerHost peerHost;
    private final NetherNetSignalingServer signalingServer;
    private final BedrockInitialTransportRouter handshakeRouter;
    private boolean started;
    private boolean stopped;

    public NetherNetServerTransportStack(WebRtcJavaNetherNetPeerHost peerHost,
                                          NetherNetSignalingServer signalingServer,
                                          BedrockInitialTransportRouter handshakeRouter) {
        this.peerHost = Objects.requireNonNull(peerHost, "peerHost");
        this.signalingServer = Objects.requireNonNull(signalingServer, "signalingServer");
        this.handshakeRouter = Objects.requireNonNull(handshakeRouter, "handshakeRouter");
        this.peerHost.setConnectionListener(handshakeRouter);
    }

    @Override
    public String id() {
        return "nethernet-server-stack";
    }

    @Override
    public synchronized void start() throws Exception {
        if (started || stopped) {
            throw new IllegalStateException("NEXORA NetherNet stack cannot be started in its current state");
        }
        peerHost.start();
        try {
            signalingServer.start();
            started = true;
        } catch (Exception failure) {
            peerHost.stop();
            handshakeRouter.close();
            stopped = true;
            throw failure;
        }
    }

    public synchronized InetSocketAddress localAddress() {
        if (!started) {
            throw new IllegalStateException("NEXORA NetherNet stack is not started");
        }
        return signalingServer.localAddress();
    }

    @Override
    public synchronized void stop() throws Exception {
        if (stopped) {
            return;
        }
        stopped = true;
        handshakeRouter.close();
        signalingServer.stop();
        peerHost.stop();
        started = false;
    }
}
