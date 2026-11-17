package ru.nexora.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Optional OpenAI-compatible chat adapter. API credentials are read only from environment variables. */
public final class NexoraAiAssistant {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions";
    private static final long COOLDOWN_NANOS = Duration.ofSeconds(5).toNanos();
    private final String apiKey;
    private final String model;
    private final URI endpoint;
    private final Duration timeout;
    private final NexoraAiDiagnostics diagnostics;
    private final HttpClient client;
    private final ConcurrentMap<String, Long> lastRequest = new ConcurrentHashMap<>();

    public NexoraAiAssistant(String apiKey, String model, URI endpoint,
                             Duration timeout, NexoraAiDiagnostics diagnostics) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        if (model == null || model.isBlank()) throw new IllegalArgumentException("model must not be blank");
        this.model = model.trim();
        this.endpoint = validateEndpoint(endpoint);
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("timeout must be between 1 ms and 2 minutes");
        }
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public static NexoraAiAssistant fromEnvironment(Map<String, String> env, NexoraAiDiagnostics diagnostics) {
        Objects.requireNonNull(env, "env");
        String key = env.getOrDefault("NEXORA_AI_API_KEY", "");
        String model = env.getOrDefault("NEXORA_AI_MODEL", "gpt-4o-mini");
        URI endpoint = URI.create(env.getOrDefault("NEXORA_AI_ENDPOINT", DEFAULT_ENDPOINT));
        long seconds = Long.parseLong(env.getOrDefault("NEXORA_AI_TIMEOUT_SECONDS", "30"));
        return new NexoraAiAssistant(key, model, endpoint, Duration.ofSeconds(seconds), diagnostics);
    }

    public boolean isEnabled() {
        return !apiKey.isBlank();
    }

    public AskResult ask(String playerId, String playerName, String prompt, Consumer<String> reply) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(reply, "reply");
        if (!isEnabled()) return AskResult.DISABLED;
        if (playerId.isBlank() || prompt.isBlank() || prompt.length() > 600) {
            throw new IllegalArgumentException("Invalid NexoraAI chat request");
        }

        long now = System.nanoTime();
        AtomicBoolean accepted = new AtomicBoolean();
        lastRequest.compute(playerId, (ignored, previous) -> {
            if (previous == null || now - previous >= COOLDOWN_NANOS) {
                accepted.set(true);
                return now;
            }
            return previous;
        });
        if (!accepted.get()) return AskResult.RATE_LIMITED;

        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", 320);
        body.put("temperature", 0.5);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt());
        messages.addObject().put("role", "user")
                .put("content", "Игрок " + playerName + " спрашивает: " + prompt);

        final String json;
        try {
            json = MAPPER.writeValueAsString(body);
        } catch (IOException exception) {
            diagnostics.recordFailure(exception);
            reply.accept("Не удалось подготовить запрос к ИИ.");
            return AskResult.ACCEPTED;
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        CompletableFuture<HttpResponse<String>> future = client.sendAsync(
                request, HttpResponse.BodyHandlers.ofString());
        future.whenComplete((response, failure) -> {
            if (failure != null) {
                diagnostics.recordFailure(failure);
                reply.accept("ИИ временно недоступен. Попробуй позже.");
                return;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                diagnostics.recordFailure(new IOException("AI provider returned HTTP " + response.statusCode()));
                reply.accept("ИИ временно недоступен (ошибка провайдера " + response.statusCode() + ").");
                return;
            }
            try {
                JsonNode root = MAPPER.readTree(response.body());
                String answer = root.path("choices").path(0).path("message").path("content").asText("");
                if (answer.isBlank()) {
                    reply.accept("ИИ вернул пустой ответ. Попробуй переформулировать вопрос.");
                    return;
                }
                answer = answer.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ").trim();
                if (answer.length() > 1200) answer = answer.substring(0, 1200) + "…";
                reply.accept(answer);
            } catch (IOException | RuntimeException exception) {
                diagnostics.recordFailure(exception);
                reply.accept("Не удалось прочитать ответ ИИ.");
            }
        });
        return AskResult.ACCEPTED;
    }

    private String systemPrompt() {
        NexoraAiDiagnostics.Snapshot s = diagnostics.inspect();
        String heap = s.heapUsageRatio() < 0 ? "unknown"
                : String.format(java.util.Locale.ROOT, "%.1f%%", s.heapUsageRatio() * 100.0);
        return "Ты NexoraAI, дружелюбный игровой помощник сервера NEXORA. Отвечай по-русски, кратко и понятно. "
                + "Помогай с командами, выживанием и механиками сервера. Не утверждай, что можешь менять мир, "
                + "выполнять команды, читать файлы или исправлять исходники. Доступные показатели JVM: health="
                + s.health() + ", heap=" + heap + ", threads=" + s.liveThreadCount()
                + ", uptimeMs=" + s.uptimeMillis() + ", reportedFailures=" + s.recordedFailures()
                + ". Это не полная диагностика TPS или игровых подсистем.";
    }

    private static URI validateEndpoint(URI uri) {
        Objects.requireNonNull(uri, "endpoint");
        String scheme = uri.getScheme();
        String host = uri.getHost();
        boolean https = "https".equalsIgnoreCase(scheme);
        boolean localHttp = "http".equalsIgnoreCase(scheme) && host != null
                && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1"));
        if ((!https && !localHttp) || host == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("AI endpoint must use HTTPS (HTTP is allowed only for localhost)");
        }
        return uri;
    }

    public enum AskResult { ACCEPTED, DISABLED, RATE_LIMITED }
}
