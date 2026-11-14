package ru.nexora.runtime;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Bounded HTTP signaling endpoint for NetherNet.
 *
 * <p>This is the signaling plane only. A real {@link NetherNetPeerHost} must
 * provide WebRTC negotiation and the validator must perform cryptographic
 * client authentication. No permissive default implementations are supplied.</p>
 */
public final class NetherNetSignalingServer implements RuntimeModule {
    public static final int DEFAULT_MAX_SDP_BYTES = 256 * 1024;
    private static final String JOIN_PREFIX = "/v1/join/";

    private final InetSocketAddress bindAddress;
    private final NetherNetServerInfo serverInfo;
    private final NetherNetOfferValidator validator;
    private final NetherNetPeerHost peerHost;
    private final String partnerDomain;
    private final KeyPair operatorKeyPair;
    private final Clock clock;
    private final Duration identityTokenLifetime;
    private final int maxSdpBytes;
    private final long answerTimeoutMillis;

    private HttpServer server;
    private ExecutorService executor;

    public NetherNetSignalingServer(InetSocketAddress bindAddress,
                                    NetherNetServerInfo serverInfo,
                                    NetherNetOfferValidator validator,
                                    NetherNetPeerHost peerHost,
                                    String partnerDomain,
                                    KeyPair operatorKeyPair) {
        this(bindAddress, serverInfo, validator, peerHost, partnerDomain, operatorKeyPair,
                Clock.systemUTC(), Duration.ofDays(30), DEFAULT_MAX_SDP_BYTES, 15_000L);
    }

    public NetherNetSignalingServer(InetSocketAddress bindAddress,
                                    NetherNetServerInfo serverInfo,
                                    NetherNetOfferValidator validator,
                                    NetherNetPeerHost peerHost,
                                    String partnerDomain,
                                    KeyPair operatorKeyPair,
                                    Clock clock,
                                    Duration identityTokenLifetime,
                                    int maxSdpBytes,
                                    long answerTimeoutMillis) {
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
        this.serverInfo = Objects.requireNonNull(serverInfo, "serverInfo");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.peerHost = Objects.requireNonNull(peerHost, "peerHost");
        this.partnerDomain = Objects.requireNonNull(partnerDomain, "partnerDomain");
        this.operatorKeyPair = Objects.requireNonNull(operatorKeyPair, "operatorKeyPair");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.identityTokenLifetime = Objects.requireNonNull(identityTokenLifetime, "identityTokenLifetime");
        if (identityTokenLifetime.isNegative() || identityTokenLifetime.isZero()
                || identityTokenLifetime.compareTo(Duration.ofDays(3650)) > 0
                || maxSdpBytes < 1 || answerTimeoutMillis < 1) {
            throw new IllegalArgumentException("Identity lifetime, SDP limit, or answer timeout is invalid");
        }
        this.maxSdpBytes = maxSdpBytes;
        this.answerTimeoutMillis = answerTimeoutMillis;
    }

    @Override
    public String id() {
        return "nethernet-signaling";
    }

    @Override
    public synchronized void start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("NetherNet signaling server is already started");
        }
        HttpServer created = HttpServer.create(bindAddress, 64);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 8, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(128),
                runnable -> {
                    Thread thread = new Thread(runnable, "nexora-nethernet-http");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        try {
            created.createContext("/v1/join", this::handle);
            created.setExecutor(pool);
            created.start();
            this.executor = pool;
            this.server = created;
        } catch (RuntimeException | IOException failure) {
            created.stop(0);
            pool.shutdownNow();
            throw failure;
        }
    }

    public synchronized InetSocketAddress localAddress() {
        if (server == null) {
            throw new IllegalStateException("NetherNet signaling server is not started");
        }
        return server.getAddress();
    }

    @Override
    public synchronized void stop() {
        HttpServer current = server;
        ExecutorService currentExecutor = executor;
        server = null;
        executor = null;
        if (current != null) {
            current.stop(1);
        }
        if (currentExecutor != null) {
            currentExecutor.shutdownNow();
        }
        peerHost.close();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(method) && "/v1/join".equals(path)) {
                send(exchange, 200, "application/json; charset=utf-8",
                        serverInfo.toJson().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (!"POST".equals(method)) {
                exchange.getResponseHeaders().set("Allow", "GET, POST");
                sendText(exchange, 405, "Method not allowed");
                return;
            }
            if (path == null || !path.startsWith(JOIN_PREFIX)) {
                sendText(exchange, 404, "Not found");
                return;
            }
            String networkId = path.substring(JOIN_PREFIX.length());
            if (!validNetworkId(networkId)) {
                sendText(exchange, 400, "Invalid NetworkID");
                return;
            }
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT)
                    .startsWith("application/sdp")) {
                sendText(exchange, 415, "Content-Type must be application/sdp");
                return;
            }

            byte[] body = readBounded(exchange.getRequestBody(), maxSdpBytes);
            String offerSdp = decodeUtf8(body);
            NetherNetSdpDocument parsedOffer = NetherNetSdpDocument.parseOffer(
                    offerSdp, maxSdpBytes, Math.min(maxSdpBytes, 16 * 1024));

            // This is deliberately before WebRTC allocation. The validator is
            // mandatory and must perform cryptographic verification.
            validator.validate(networkId, parsedOffer);

            String answer = peerHost.createAnswer(networkId, parsedOffer.webRtcSdp())
                    .toCompletableFuture().get(answerTimeoutMillis, TimeUnit.MILLISECONDS);
            if (answer == null || answer.isBlank()) {
                throw new IllegalStateException("WebRTC host returned an empty SDP answer");
            }
            Instant issuedAt = clock.instant();
            String signedAnswer = NetherNetServerIdentitySigner.addIdentity(
                    answer, partnerDomain, operatorKeyPair,
                    issuedAt, issuedAt.plus(identityTokenLifetime));
            byte[] answerBytes = signedAnswer.getBytes(StandardCharsets.UTF_8);
            if (answerBytes.length > maxSdpBytes) {
                throw new IllegalStateException("Signed SDP answer exceeds configured byte limit");
            }
            // Parse the generated assertion again before returning it to the client.
            NetherNetSdpDocument.parseOffer(signedAnswer, maxSdpBytes,
                    Math.min(maxSdpBytes, 16 * 1024));
            send(exchange, 200, "application/sdp; charset=utf-8", answerBytes);
        } catch (java.util.concurrent.TimeoutException timeout) {
            sendTextIfOpen(exchange, 504, "WebRTC answer timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            sendTextIfOpen(exchange, 503, "Signaling interrupted");
        } catch (Exception failure) {
            sendTextIfOpen(exchange, 401, "Signaling rejected");
        }
    }

    private static boolean validNetworkId(String value) {
        if (value == null || value.isEmpty() || value.length() > 256
                || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) {
            return false;
        }
        return value.chars().noneMatch(Character::isISOControl);
    }

    private static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("SDP request exceeds configured byte limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String decodeUtf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new IOException("SDP body is not valid UTF-8", invalid);
        }
    }

    private static void sendText(HttpExchange exchange, int status, String message)
            throws IOException {
        send(exchange, status, "text/plain; charset=utf-8",
                message.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendTextIfOpen(HttpExchange exchange, int status, String message) {
        try {
            sendText(exchange, status, message);
        } catch (IOException ignored) {
            // Client may have disconnected while WebRTC negotiation was running.
        }
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
