package ru.nexora.network.nethernet;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP signaling endpoint for the modern Bedrock NetherNet transport.
 *
 * The actual WebRTC implementation is supplied through SdpNegotiator so the
 * HTTP layer remains independent from a particular native WebRTC binding.
 */
public final class NetherNetSignalingServer implements AutoCloseable {
    public static final int MAX_SDP_BYTES = 1_048_576;

    private final InetSocketAddress bindAddress;
    private final NetherNetServerMetadata metadata;
    private final SdpNegotiator negotiator;
    private HttpServer server;
    private ExecutorService executor;

    public NetherNetSignalingServer(
            InetSocketAddress bindAddress,
            NetherNetServerMetadata metadata,
            SdpNegotiator negotiator
    ) {
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.negotiator = Objects.requireNonNull(negotiator, "negotiator");
    }

    public synchronized void start() throws IOException {
        if (server != null) return;
        HttpServer created = HttpServer.create(bindAddress, 64);
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "nexora-nethernet-http-" + threadNumber.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
        executor = Executors.newFixedThreadPool(
                Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors())), threadFactory);
        created.setExecutor(executor);
        created.createContext("/v1/join", this::handleJoin);
        created.start();
        server = created;
    }

    public InetSocketAddress localAddress() {
        HttpServer current = server;
        if (current == null) throw new IllegalStateException("Signaling server is not started");
        return current.getAddress();
    }

    private void handleJoin(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            if ("GET".equals(method) && "/v1/join".equals(path)) {
                send(exchange, 200, "application/json; charset=utf-8",
                        metadata.toJson().getBytes(StandardCharsets.UTF_8));
                return;
            }

            String prefix = "/v1/join/";
            if (!"POST".equals(method) || !path.startsWith(prefix) || path.length() == prefix.length()) {
                sendError(exchange, 404, "Unknown NetherNet signaling endpoint");
                return;
            }

            String networkId = path.substring(prefix.length());
            if (networkId.length() > 256 || networkId.indexOf('/') >= 0) {
                sendError(exchange, 400, "Invalid NetworkID path");
                return;
            }

            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.split(";", 2)[0].trim()
                    .equalsIgnoreCase("application/sdp")) {
                sendError(exchange, 415, "Content-Type must be application/sdp");
                return;
            }

            byte[] offerBytes = exchange.getRequestBody().readNBytes(MAX_SDP_BYTES + 1);
            if (offerBytes.length == 0 || offerBytes.length > MAX_SDP_BYTES) {
                sendError(exchange, 413, "SDP offer is empty or exceeds the 1 MiB limit");
                return;
            }

            String offer = new String(offerBytes, StandardCharsets.UTF_8);
            if (!offer.startsWith("v=0")) {
                sendError(exchange, 400, "Malformed SDP offer");
                return;
            }

            try {
                String answer = negotiator.createAnswer(networkId, offer);
                if (answer == null || !answer.startsWith("v=0")) {
                    sendError(exchange, 502, "WebRTC negotiator returned an invalid SDP answer");
                    return;
                }
                send(exchange, 200, "application/sdp; charset=utf-8",
                        answer.getBytes(StandardCharsets.UTF_8));
            } catch (Exception exception) {
                System.err.println("NEXORA: NetherNet negotiation failed: " + exception.getMessage());
                sendError(exchange, 503, "Unable to establish a NetherNet session");
            }
        }
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        String json = "{\"error\":\"" + escapeJson(message) + "\"}";
        send(exchange, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(
            HttpExchange exchange,
            int status,
            String contentType,
            byte[] body
    ) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static String escapeJson(String value) {
        StringBuilder result = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> result.append("\\\\"");
                case '\\\\' -> result.append("\\\\\\\\");
                case '\\b' -> result.append("\\\\b");
                case '\\f' -> result.append("\\\\f");
                case '\\n' -> result.append("\\\\n");
                case '\\r' -> result.append("\\\\r");
                case '\\t' -> result.append("\\\\t");
                default -> {
                    if (c < 0x20) result.append(String.format("\\\\u%04x", (int) c));
                    else result.append(c);
                }
            }
        }
        return result.toString();
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(1);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
