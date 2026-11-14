package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetBedrockModuleTest {
    @Test
    void startsWebRtcBeforeSignalingAndStopsTheWholeStack() throws Exception {
        FakeHost host = new FakeHost();
        BedrockInitialTransportRouter router = new BedrockInitialTransportRouter(
                new BedrockNetworkSettings(0, BedrockNetworkSettings.CompressionAlgorithm.NONE,
                        false, 0, 0.0f),
                (connection, login) -> CompletableFuture.completedFuture(false),
                connection -> fail("No peer should enter play in this lifecycle test"),
                (connection, message) -> fail("No play message expected"));

        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair operatorKey = generator.generateKeyPair();
        NetherNetSignalingServer signaling = new NetherNetSignalingServer(
                new InetSocketAddress("127.0.0.1", 0),
                new NetherNetServerInfo("NEXORA", 2225, "1.26.60", "World", 0, 20, 0),
                (networkId, offer) -> { },
                host,
                "nexora.example",
                operatorKey);

        NetherNetBedrockModule module = new NetherNetBedrockModule(
                host, host, signaling, router);
        module.start();

        assertTrue(host.started);
        assertNotNull(signaling.localAddress());
        module.stop();

        assertFalse(host.started);
        assertTrue(host.closed);
    }

    private static final class FakeHost implements NetherNetPeerHost, NetworkTransport {
        private Consumer<TransportConnection> listener;
        private boolean started;
        private boolean closed;

        @Override public String id() { return "fake-nethernet-host"; }
        @Override public TransportCapabilities capabilities() {
            return new TransportCapabilities(true, true, true, false);
        }
        @Override public void setConnectionListener(Consumer<TransportConnection> listener) {
            this.listener = listener;
        }
        @Override public void start() {
            assertNotNull(listener);
            started = true;
        }
        @Override public void stop() {
            started = false;
            close();
        }
        @Override public java.util.concurrent.CompletionStage<String> createAnswer(
                String networkId, String webRtcOfferSdp) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("test host"));
        }
        @Override public void close() {
            started = false;
            closed = true;
        }
    }
}
