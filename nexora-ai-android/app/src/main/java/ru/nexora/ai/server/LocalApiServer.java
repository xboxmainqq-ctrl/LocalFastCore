package ru.nexora.ai.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class LocalApiServer {
    public interface Listener {
        void onStarted();
        void onFailed(String message);
        void onStopped();
    }

    private final String token;
    private final Listener listener;
    private volatile boolean running;
    private volatile ServerSocket serverSocket;
    private Thread serverThread;

    public LocalApiServer(String token, Listener listener) {
        this.token = token == null ? "" : token;
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        serverThread = new Thread(this::serve, "NEXORA-Local-API");
        serverThread.start();
    }

    public synchronized void stop() {
        boolean wasRunning = running;
        running = false;
        ServerSocket current = serverSocket;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
            }
        }
        serverSocket = null;
        serverThread = null;
        if (wasRunning && listener != null) listener.onStopped();
    }

    private void serve() {
        try (ServerSocket socket = new ServerSocket(
                8765, 16, InetAddress.getByName("127.0.0.1"))) {
            serverSocket = socket;
            if (listener != null) listener.onStarted();
            while (running) {
                try (Socket client = socket.accept()) {
                    client.setSoTimeout(5000);
                    handle(client);
                } catch (IOException error) {
                    if (!running) break;
                }
            }
        } catch (IOException error) {
            running = false;
            if (listener != null) {
                String message = error.getMessage();
                listener.onFailed(message == null ? error.getClass().getSimpleName() : message);
            }
            return;
        } finally {
            serverSocket = null;
        }
    }

    private void handle(Socket client) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                client.getInputStream(), StandardCharsets.UTF_8));
        String requestLine = reader.readLine();
        if (requestLine == null) return;

        String authorization = "";
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int separator = line.indexOf(':');
            if (separator > 0 && "authorization".equalsIgnoreCase(
                    line.substring(0, separator).trim())) {
                authorization = line.substring(separator + 1).trim();
            }
        }

        String[] parts = requestLine.split(" ");
        String method = parts.length > 0 ? parts[0] : "";
        String path = parts.length > 1 ? parts[1] : "/";
        if (!"GET".equals(method)) {
            respond(client.getOutputStream(), 405, "Method Not Allowed",
                    "{\"error\":\"method_not_allowed\"}");
            return;
        }

        String supplied = authorization.startsWith("Bearer ")
                ? authorization.substring(7) : "";
        if (!constantTimeEquals(token, supplied)) {
            respond(client.getOutputStream(), 401, "Unauthorized",
                    "{\"error\":\"unauthorized\"}");
            return;
        }

        if ("/health".equals(path)) {
            respond(client.getOutputStream(), 200, "OK",
                    "{\"status\":\"ok\",\"service\":\"NEXORA AI Host\",\"version\":\"0.1.0\"}");
        } else if ("/v1/capabilities".equals(path)) {
            respond(client.getOutputStream(), 200, "OK",
                    "{\"api\":\"0.1\",\"features\":[\"health\",\"capabilities\"],\"binding\":\"loopback_only\"}");
        } else {
            respond(client.getOutputStream(), 404, "Not Found",
                    "{\"error\":\"not_found\"}");
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (expected.isEmpty() || actual.isEmpty()) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private void respond(OutputStream output, int status, String reason, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n"
                + "Cache-Control: no-store\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.UTF_8));
        output.write(bytes);
        output.flush();
    }
}
