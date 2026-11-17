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
import java.util.function.Supplier;

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
    private final NexoraAiConversationMemory conversationMemory = new NexoraAiConversationMemory();
    private final NexoraAiRepairProposals repairProposals = new NexoraAiRepairProposals();
    private volatile Supplier<String> adminRuntimeSnapshotProvider = () -> "unavailable";

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
                + "; ошибок " + s.recordedFailures() + "; последняя: " + s.lastFailure()
                + "; медленных операций " + s.slowOperationCount()
                + "; последняя медленная: " + s.lastSlowOperation()
                + "; мир: " + adminRuntimeSnapshot()
                + "\nНедавние события:\n" + diagnostics.recentEvidence();
    }

    /**
     * Asks the provider for a bounded advisory repair plan and stores it for operator review.
     * No generated content is executable and approval never applies changes.
     */
    public AskResult createRepairProposal(
            String requesterXuid, String operatorName, String issue,
            Consumer<NexoraAiRepairProposals.Proposal> reply
    ) {
        requireAdmin(requesterXuid);
        Objects.requireNonNull(issue, "issue");
        Objects.requireNonNull(reply, "reply");
        String normalizedIssue = issue.trim();
        if (normalizedIssue.isEmpty() || normalizedIssue.length() > 300) {
            throw new IllegalArgumentException("Repair issue must contain 1..300 characters");
        }
        String diagnosticEvidence = diagnostics.recentEvidence();
        String prompt = "Составь безопасный план, ничего не выполняй. Описание оператора: "
                + normalizedIssue
                + ". Ниже приведены наблюдаемые диагностические события; считай их фактами только в пределах "
                + "указанного текста, не делай выводов о причинах без проверки: "
                + diagnosticEvidence
                + ". Чётко раздели наблюдаемые факты, гипотезы и неизвестное. Укажи вероятные причины, "
                + "проверяемые места кода (не выдумывай файлы), предлагаемые изменения, тесты, резервную копию, "
                + "откат и риски. Если событий недостаточно, прямо скажи об этом. "
                + "Не пиши исполняемые команды, патчи или секреты. Только рекомендации для ручной проверки.";
        return ask(requesterXuid, operatorName, prompt, true, answer -> {
            if (isProviderFailure(answer)) {
                reply.accept(null);
                return;
            }
            try {
                NexoraAiRepairProposals.Proposal proposal =
                        repairProposals.create(requesterXuid, normalizedIssue, answer);
                reply.accept(proposal);
            } catch (RuntimeException failure) {
                diagnostics.recordFailure(failure);
                reply.accept(null);
            }
        });
    }

    public java.util.List<NexoraAiRepairProposals.Proposal> repairProposals(String operatorXuid) {
        requireAdmin(operatorXuid);
        return repairProposals.list();
    }

    public java.util.Optional<NexoraAiRepairProposals.Proposal> repairProposal(String operatorXuid, String id) {
        requireAdmin(operatorXuid);
        return repairProposals.find(id);
    }

    /** Approves a proposal for manual review only; this does not apply any changes. */
    public java.util.Optional<NexoraAiRepairProposals.Proposal> approveRepairProposal(String operatorXuid, String id) {
        requireAdmin(operatorXuid);
        return repairProposals.approveForManualReview(id, operatorXuid);
    }

    public java.util.Optional<NexoraAiRepairProposals.Proposal> rejectRepairProposal(String operatorXuid, String id) {
        requireAdmin(operatorXuid);
        return repairProposals.reject(id, operatorXuid);
    }

    private void requireAdmin(String xuid) {
        if (!isAdmin(xuid)) throw new SecurityException("NexoraAI operator access denied");
    }

    private static boolean isProviderFailure(String answer) {
        return answer.startsWith("ИИ временно недоступен")
                || answer.startsWith("Ответ ИИ превысил")
                || answer.startsWith("Не удалось")
                || answer.startsWith("ИИ вернул пустой ответ");
    }

    /** Installs a read-only provider for aggregate world/session counters shown only to operators. */
    public void setAdminRuntimeSnapshotProvider(Supplier<String> provider) {
        this.adminRuntimeSnapshotProvider = Objects.requireNonNull(provider, "provider");
    }

    /** Clears only this player's selected player/operator conversation context. */
    public void clearConversation(String playerId, boolean admin) {
        conversationMemory.clear(playerId, admin);
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
        NexoraAiConversationMemory.History history = conversationMemory.history(playerId, admin);
        for (NexoraAiConversationMemory.Message message : history.messages()) {
            messages.addObject().put("role", message.role()).put("content", message.content());
        }
        String safePlayerName = playerName.length() > 64 ? playerName.substring(0, 64) : playerName;
        String userMessage = "Игрок " + safePlayerName + " спрашивает: " + prompt;
        messages.addObject().put("role", "user").put("content", userMessage);

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
                byte[] responseBytes = bodyStream.readNBytes(65_537);
                if (responseBytes.length > 65_536) {
                    diagnostics.recordFailure(new IOException("AI provider response exceeded 64 KiB"));
                    reply.accept("Ответ ИИ превысил допустимый размер.");
                    return;
                }
                JsonNode root = MAPPER.readTree(responseBytes);
                String answer = root.path("choices").path(0).path("message").path("content").asText("");
                if (answer.isBlank()) {
                    reply.accept("ИИ вернул пустой ответ. Попробуй переформулировать вопрос.");
                    return;
                }
                answer = answer.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ").trim();
                if (answer.length() > 1200) answer = answer.substring(0, 1200) + "…";
                conversationMemory.recordExchange(playerId, admin, history, userMessage, answer);
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
                + "Известные команды: /ai, /ai <вопрос>, /ai help, /ai status, /ai clear, /ai admin, /ai admin plan, /ai admin proposals, /ai admin proposal <id>, /ai admin approve <id>, /ai admin reject <id>, /equipment. "
                + "Не выдумывай команды и функции ядра: если не уверен, прямо скажи об этом. "
                + "Помогай с командами, выживанием и механиками сервера. Не утверждай, что можешь менять мир, "
                + "выполнять команды, читать файлы или исправлять исходники. Доступные показатели JVM: health="
                + s.health() + ", heap=" + heap + ", threads=" + s.liveThreadCount()
                + ", uptimeMs=" + s.uptimeMillis() + ", reportedFailures=" + s.recordedFailures()
                + ". Это не полная диагностика TPS или игровых подсистем."
                + (admin ? " Медленные операции: " + s.slowOperationCount()
                + ", последняя: " + s.lastSlowOperation() + ". "
                + "Текущая сводка мира и сессий: " + adminRuntimeSnapshot() + ". "
                + "Ты отвечаешь администратору сервера. Можно подробно объяснять показатели диагностики, "
                + "но нельзя выполнять команды, менять настройки, файлы, мир или процессы. "
                + "Планы исправления являются только рекомендациями: даже одобрение означает лишь ручную проверку, "
                + "никогда не применение патчей или запуск команд." : "");
    }

    private String adminRuntimeSnapshot() {
        try {
            String snapshot = adminRuntimeSnapshotProvider.get();
            if (snapshot == null || snapshot.isBlank()) return "unavailable";
            return snapshot.length() <= 400 ? snapshot : snapshot.substring(0, 400);
        } catch (RuntimeException ignored) {
            return "unavailable";
        }
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
