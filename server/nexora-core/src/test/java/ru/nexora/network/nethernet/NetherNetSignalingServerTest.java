package ru.nexora.network.nethernet;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetSignalingServerTest {
    private static final String OFFER = "v=0\r\ns=-\r\nt=0 0\r\n";
    private static final String ANSWER = "v=0\r\ns=NEXORA\r\nt=0 0\r\n";

    @Test
    void getJoinReturnsServerMetadataAsJson() throws Exception {
        NetherNetServerMetadata metadata = metadata();
        try (NetherNetSignalingServer server = server(metadata, (id, offer) -> ANSWER)) {
            server.start();
            HttpResponse<String> response = get(server, "/v1/join");

            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElse("")
                    .startsWith("application/json"));
            assertTrue(response.body().contains("\"name\":\"NEXORA \\\"RPG\\\"\""));
            assertTrue(response.body().contains("\"protocol\":2225"));
            assertTrue(response.body().contains("\"maxPlayers\":20"));
        }
    }

    @Test
    void getJoinReflectsLivePlayerCount() throws Exception {
        AtomicInteger players = new AtomicInteger();
        try (NetherNetSignalingServer server = new NetherNetSignalingServer(
                new InetSocketAddress("127.0.0.1", 0),
                () -> new NetherNetServerMetadata("NEXORA", 2225, "1.26.62",
                        "NEXORA World", players.get(), 20, 0),
                (id, offer) -> ANSWER)) {
            server.start();
            assertTrue(get(server, "/v1/join").body().contains("\"players\":0"));

            players.set(3);
            assertTrue(get(server, "/v1/join").body().contains("\"players\":3"));
        }
    }

    @Test
    void postJoinPassesNetworkIdAndOfferToNegotiator() throws Exception {
        AtomicReference<String> receivedId = new AtomicReference<>();
        AtomicReference<String> receivedOffer = new AtomicReference<>();
        try (NetherNetSignalingServer server = server(metadata(), (id, offer) -> {
            receivedId.set(id);
            receivedOffer.set(offer);
            return ANSWER;
        })) {
            server.start();
            HttpRequest request = HttpRequest.newBuilder(uri(server, "/v1/join/client-123"))
                    .header("Content-Type", "application/sdp")
                    .POST(HttpRequest.BodyPublishers.ofString(OFFER, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(3))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals("application/sdp", response.headers().firstValue("Content-Type")
                    .orElseThrow().split(";", 2)[0]);
            assertEquals(ANSWER, response.body());
            assertEquals("client-123", receivedId.get());
            assertEquals(OFFER, receivedOffer.get());
        }
    }

    @Test
    void rejectsWrongContentTypeBeforeNegotiation() throws Exception {
        try (NetherNetSignalingServer server = server(metadata(), (id, offer) -> {
            fail("Negotiator must not be called for a wrong content type");
            return ANSWER;
        })) {
            server.start();
            HttpRequest request = HttpRequest.newBuilder(uri(server, "/v1/join/client-123"))
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString(OFFER))
                    .timeout(Duration.ofSeconds(3))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    request, HttpResponse.BodyHandlers.ofString());

            assertEquals(415, response.statusCode());
        }
    }

    @Test
    void rejectsNewSessionsWhenAdmissionIsClosedButKeepsMetadataAvailable() throws Exception {
        AtomicInteger negotiationCalls = new AtomicInteger();
        try (NetherNetSignalingServer server = new NetherNetSignalingServer(
                new InetSocketAddress("127.0.0.1", 0),
                () -> metadata(),
                (id, offer) -> {
                    negotiationCalls.incrementAndGet();
                    return ANSWER;
                },
                () -> false)) {
            server.start();

            assertEquals(200, get(server, "/v1/join").statusCode());

            HttpRequest request = HttpRequest.newBuilder(uri(server, "/v1/join/client-123"))
                    .header("Content-Type", "application/sdp")
                    .POST(HttpRequest.BodyPublishers.ofString(OFFER))
                    .timeout(Duration.ofSeconds(3))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    request, HttpResponse.BodyHandlers.ofString());

            assertEquals(503, response.statusCode());
            assertTrue(response.body().contains("not accepting new sessions"));
            assertEquals(0, negotiationCalls.get());
        }
    }

    @Test
    void returnsServiceUnavailableWhenNegotiationFails() throws Exception {
        try (NetherNetSignalingServer server = server(metadata(), (id, offer) -> {
            throw new IllegalStateException("test failure");
        })) {
            server.start();
            HttpRequest request = HttpRequest.newBuilder(uri(server, "/v1/join/client-123"))
                    .header("Content-Type", "application/sdp")
                    .POST(HttpRequest.BodyPublishers.ofString(OFFER))
                    .timeout(Duration.ofSeconds(3))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    request, HttpResponse.BodyHandlers.ofString());

            assertEquals(503, response.statusCode());
            assertTrue(response.body().contains("Unable to establish"));
        }
    }

    private static NetherNetServerMetadata metadata() {
        return new NetherNetServerMetadata("NEXORA \"RPG\"", 2225, "1.26.62",
                "NEXORA World", 0, 20, 0);
    }

    private static NetherNetSignalingServer server(
            NetherNetServerMetadata metadata,
            SdpNegotiator negotiator
    ) {
        return new NetherNetSignalingServer(
                new InetSocketAddress("127.0.0.1", 0), metadata, negotiator);
    }

    private static HttpResponse<String> get(NetherNetSignalingServer server, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(server, path))
                .GET().timeout(Duration.ofSeconds(3)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(NetherNetSignalingServer server, String path) {
        return URI.create("http://127.0.0.1:" + server.localAddress().getPort() + path);
    }
}
