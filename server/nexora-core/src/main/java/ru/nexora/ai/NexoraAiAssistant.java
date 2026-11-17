package ru.nexora.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Arrays;
import java.util.stream.Collectors;
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
    private final Set<String> adminXuids;
    private final HttpClient client;
    private final ConcurrentMap<String, Long> lastRequest = new ConcurrentHashMap<>();

    public NexoraAiAssistant(String apiKey, String model, URI endpoint,
                             Duration timeout, NexoraAiDiagnostics diagnostics) {
        this(apiKey, model, endpoint, timeout, diagnostics, Set.of());
    }

    public NexoraAiAssistant(String apiKey, String model, URI endpoint,
                             Duration timeout, NexoraAiDiagnostics diagnostics, Set<String> adminXuids) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        if (model == null || model.isBlank()) throw new IllegalArgumentException("model must not be blank");
        this.model = model.trim();
        this.endpoint = validateEndpoint(endpoint);
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("timeout must be between 1 ms and 2 minutes");
        }
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.adminXuids = Set.copyOf(Objects.requireNonNull(adminXuids, "adminXuids"));
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public static NexoraAiAssistant fromEnvironment(Map<String, String> env, NexoraAiDiagnostics diagnostics) {
        Objects.requireNonNull(env, "env");
        try {
            String key = env.getOrDefault("NEXORA_AI_API_KEY", "");
            String model = env.getOrDefault("NEXORA_AI_MODEL", "gpt-4o-mini");
            URI endpoint = URI.create(env.getOrDefault("NEXORA_AI_ENDPOINT", DEFAULT_ENDPOINT));
            long seconds = Long.parseLong(env.getOrDefault("NEXORA_AI_TIMEOUT_SECONDS", "30"));
            Set<String> admins = Arrays.stream(env.getOrDefault("NEXORA_AI_ADMIN_XUIDS", "").split(","))
                    .map(String::trim).filter(value -> !value.isEmpty()).collect(Collectors.toUnmodifiableSet());
            return new NexoraAiAssistant(key, model, endpoint, Duration.ofSeconds(seconds), diagnostics, admins);
        } catch (RuntimeException configurationFailure) {
            diagnostics.recordFailure(configurationFailure);
            return new NexoraAiAssistant("", "gpt-4o-mini", URI.create(DEFAULT_ENDPOINT),
                    Duration.ofSeconds(30), diagnostics);
        }
    }

    public boolean isEnabled() {
        return !apiKey.isBlank();
    }

    /** Checks the authenticated Xbox XUID against the server-side operator allowlist. */
    public boolean isAdmin(String xuid) {
        return xuid != null && !xuid.isBlank() && adminXuids.contains(xuid);
    }

    /** Read-only operator snapshot; no command execution or repair is performed. */
    public String adminStatus() {
        NexoraAiDiagnostics.Snapshot s = diagnostics.inspect();
        String heap = s.heapUsageRatio() < 0 ? "unknown"
                : String.format(java.util.Locale.ROOT, "%.1f%%", s.heapUsageRatio() * 100.0);
        String load = s.systemLoadAverage() < 0 ? "unknown"
                : String.format(java.util.Locale.ROOT, "%.2f", s.systemLoadAverage());
        return "Состояние ядра: " + s.health() + "; heap " + heap
                + "; потоки " + s.liveThreadCount() + "; CPU " + s.availableProcessors()
                + "; load " + load + "; uptime " + s.uptimeMillis() + " ms"
                + "; ошибок " + s.recordedFailures() + "; последняя: " + s.lastFailure();
    }

    public AskResult ask(String playerId, String playerName, String prompt, Consumer<String> reply) {
        return ask(playerId, playerName, prompt, false, reply);
    }

    public AskResult ask(String playerId, String playerName, String prompt,
                         boolean admin, Consumer<String> reply) {
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
        if (lastRequest.size() > 10_000) {
            long cutoff = now - Duration.ofMinutes(5).toNanos();
            lastRequest.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        }

        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", 320);
        body.put("temperature", 0.5);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt(admin));
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
        CompletableFuture<HttpResponse<InputStream>> future = client.sendAsync(
                request, HttpResponse.BodyHandlers.ofInputStream());
        future.whenComplete((response, failure) -> {
            if (failure != null) {
                diagnostics.recordFailure(failure);
                reply.accept("ИИ временно недоступен. Попробуй позже.");
                return;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                try (InputStream ignored = response.body()) {
                    diagnostics.recordFailure(new IOException("AI provider returned HTTP " + response.statusCode()));
                    reply.accept("ИИ временно недоступен (ошибка провайдера " + response.statusCode() + ").");
                } catch (IOException closeFailure) {
                    diagnostics.recordFailure(closeFailure);
                    reply.accept("ИИ временно недоступен.");
                }
                return;
            }
            try (InputStream bodyStream = response.body()) {
                byte[] body = bodyStream.readNBytes(65_537);
                if (body.length > 65_536) {
                    diagnostics.recordFailure(new IOException("AI provider response exceeded 64 KiB"));
                    reply.accept("Ответ ИИ превысил допустимый размер.");
                    return;
                }
                JsonNode root = MAPPER.readTree(body);
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

    private String systemPrompt(boolean admin) {
        NexoraAiDiagnostics.Snapshot s = diagnostics.inspect();
        String heap = s.heapUsageRatio() < 0 ? "unknown"
                : String.format(java.util.Locale.ROOT, "%.1f%%", s.heapUsageRatio() * 100.0);
        return "Ты NexoraAI, дружелюбный игровой помощник сервера NEXORA. Отвечай по-русски, кратко и понятно. "
                + "Помогай с командами, выживанием и механиками сервера. Не утверждай, что можешь менять мир, "
                + "выполнять команды, читать файлы или исправлять исходники. Доступные показатели JVM: health="
                + s.health() + ", heap=" + heap + ", threads=" + s.liveThreadCount()
                + ", uptimeMs=" + s.uptimeMillis() + ", reportedFailures=" + s.recordedFailures()
                + ". Это не полная диагностика TPS или игровых подсистем."
                + (admin ? " Ты отвечаешь администратору сервера. Можно подробно объяснять показатели диагностики, "
                + "но нельзя выполнять команды, менять настройки, файлы, мир или процессы." : "");
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
