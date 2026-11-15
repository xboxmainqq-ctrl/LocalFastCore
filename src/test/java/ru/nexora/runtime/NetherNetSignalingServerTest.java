package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetSignalingServerTest {
    private static final String FINGERPRINT =
            "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:"
            + "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99";

    @Test
    void servesMetadataAndReturnsSignedSdpAnswerAfterOfferValidation() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair operator = generator.generateKeyPair();

        String offer = createDummyIdentitySdp(operator);
        String answer = "v=0\r\no=- 2 2 IN IP4 0.0.0.0\r\ns=-\r\nt=0 0\r\n"
                + "a=fingerprint:sha-256 " + FINGERPRINT + "\r\n"
                + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n";

        AtomicReference<String> remoteDescription = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean();
        NetherNetPeerHost peerHost = new NetherNetPeerHost() {
            @Override
            public void setConnectionListener(java.util.function.Consumer<TransportConnection> listener) {
            }

            @Override
            public CompletableFuture<String> createAnswer(String networkId, String webRtcOfferSdp) {
                assertEquals("network-1", networkId);
                remoteDescription.set(webRtcOfferSdp);
                return CompletableFuture.completedFuture(answer);
            }

            @Override
            public CompletableFuture<String> createAnswer(String networkId, String webRtcOfferSdp,
                    NetherNetIdentityValidator.VerifiedIdentity identity) {
                assertEquals("verified-xuid", identity.xuid());
                return createAnswer(networkId, webRtcOfferSdp);
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };

        NetherNetOfferValidator validator = new NetherNetOfferValidator() {
            @Override
            public void validate(String networkId, NetherNetSdpDocument document) {
                assertEquals("network-1", networkId);
                assertEquals(offer, document.originalSdp());
            }

            @Override
            public NetherNetIdentityValidator.VerifiedIdentity validateAndGetIdentity(
                    String networkId, NetherNetSdpDocument document) {
                validate(networkId, document);
                return new NetherNetIdentityValidator.VerifiedIdentity(
                        "minecraft-services.net", "verified-xuid", "VerifiedPlayer",
                        (java.security.interfaces.ECPublicKey) operator.getPublic(),
                        NetherNetIdentityValidator.tokenSha256("test-game-server-token"));
            }
        };
        NetherNetServerInfo info = new NetherNetServerInfo(
                "NEXORA", 2225, "1.26.60", "NEXORA World", 0, 20, 0);
        NetherNetSignalingServer server = new NetherNetSignalingServer(
                new InetSocketAddress("127.0.0.1", 0), info, validator, peerHost,
                "nexora.example", operator,
                Clock.fixed(Instant.ofEpochSecond(2000), ZoneOffset.UTC),
                Duration.ofDays(30), 256 * 1024, 5000L);
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.localAddress().getPort());
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> metadata = client.send(
                    HttpRequest.newBuilder(base.resolve("/v1/join")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, metadata.statusCode());
            assertTrue(metadata.body().contains("\"protocol\":2225"));

            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(base.resolve("/v1/join/network-1"))
                            .header("Content-Type", "application/sdp")
                            .POST(HttpRequest.BodyPublishers.ofString(offer))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElse("")
                    .startsWith("application/sdp"));
            assertFalse(remoteDescription.get().contains("a=identity:"));
            assertTrue(response.body().contains("a=identity:"));

            NetherNetServerIdentityVerifier.VerifiedOperatorIdentity verified =
                    new NetherNetServerIdentityVerifier(
                            Clock.fixed(Instant.ofEpochSecond(2001), ZoneOffset.UTC), 0L)
                            .verify(response.body(), false, fingerprint -> false);
            assertEquals(NetherNetServerIdentityVerifier.TrustStatus.FIRST_USE_CONFIRMATION_REQUIRED,
                    verified.trustStatus());
        } finally {
            server.stop();
        }
        assertTrue(closed.get());
    }

    @Test
    void allowsExplicitDevelopmentOfferWithoutClientIdentity() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair operator = generator.generateKeyPair();

        String offer = "v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\ns=-\r\nt=0 0\r\n"
                + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"
                + "a=fingerprint:sha-256 " + FINGERPRINT + "\r\n";
        String answer = "v=0\r\no=- 2 2 IN IP4 0.0.0.0\r\ns=-\r\nt=0 0\r\n"
                + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"
                + "a=fingerprint:sha-256 " + FINGERPRINT + "\r\n";
        AtomicReference<String> remoteDescription = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean();
        NetherNetPeerHost peerHost = new NetherNetPeerHost() {
            @Override
            public void setConnectionListener(java.util.function.Consumer<TransportConnection> listener) {
            }

            @Override
            public CompletableFuture<String> createAnswer(String networkId, String webRtcOfferSdp) {
                remoteDescription.set(webRtcOfferSdp);
                return CompletableFuture.completedFuture(answer);
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };

        NetherNetSignalingServer server = new NetherNetSignalingServer(
                new InetSocketAddress("127.0.0.1", 0),
                new NetherNetServerInfo("NEXORA", 2225, "1.26.60", "Dev", 0, 10, 0),
                new NetherNetDevelopmentOfferValidator(true),
                peerHost, "nexora.example", operator,
                Clock.fixed(Instant.ofEpochSecond(2000), ZoneOffset.UTC),
                Duration.ofDays(30), 256 * 1024, 5000L);
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.localAddress().getPort());
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(base.resolve("/v1/join/android-dev"))
                            .header("Content-Type", "application/sdp")
                            .POST(HttpRequest.BodyPublishers.ofString(offer))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals(offer, remoteDescription.get());
            NetherNetSdpDocument parsedAnswer = NetherNetSdpDocument.parseOffer(response.body());
            assertNotNull(parsedAnswer.identityEnvelopeJson());
            assertTrue(parsedAnswer.webRtcSdp().contains("m=application"));
            assertTrue(parsedAnswer.webRtcSdp().contains("a=fingerprint:sha-256"));
        } finally {
            server.stop();
        }
        assertTrue(closed.get());
    }

    @Test
    void cancelsPeerWhenWebRtcAnswerTimesOut() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair operator = generator.generateKeyPair();
        AtomicBoolean cancelled = new AtomicBoolean();
        NetherNetPeerHost peerHost = new NetherNetPeerHost() {
            @Override
            public void setConnectionListener(java.util.function.Consumer<TransportConnection> listener) {
            }

            @Override
            public CompletableFuture<String> createAnswer(String networkId, String webRtcOfferSdp) {
                return new CompletableFuture<>();
            }

            @Override
            public void cancel(String networkId) {
                cancelled.set(true);
            }

            @Override
            public void close() {
            }
        };
        String offer = "v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\ns=-\r\nt=0 0\r\n"
                + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"
                + "a=fingerprint:sha-256 " + FINGERPRINT + "\r\n";
        NetherNetSignalingServer server = new NetherNetSignalingServer(
                new InetSocketAddress("127.0.0.1", 0),
                new NetherNetServerInfo("NEXORA", 2225, "1.26.60", "Dev", 0, 10, 0),
                new NetherNetDevelopmentOfferValidator(true),
                peerHost, "nexora.example", operator,
                Clock.fixed(Instant.ofEpochSecond(2000), ZoneOffset.UTC),
                Duration.ofDays(30), null, 256 * 1024, 50L);
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.localAddress().getPort());
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(base.resolve("/v1/join/timeout-test"))
                            .header("Content-Type", "application/sdp")
                            .POST(HttpRequest.BodyPublishers.ofString(offer))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(504, response.statusCode());
            assertTrue(cancelled.get());
        } finally {
            server.stop();
        }
    }

    private static String createDummyIdentitySdp(KeyPair keyPair) throws Exception {
        String plain = "v=0\na=fingerprint:sha-256 " + FINGERPRINT + "\nm=application 9 x";
        return NetherNetServerIdentitySigner.addIdentity(plain, "nexora.example", keyPair,
                Instant.ofEpochSecond(1000), Instant.ofEpochSecond(5000));
    }
}
