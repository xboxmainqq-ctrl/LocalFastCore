package ru.nexora.ai;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/**
 * Bounded, process-local conversation context. Player and operator histories are isolated.
 * Only completed exchanges are retained; no conversation is written to disk or logs.
 */
final class NexoraAiConversationMemory {
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(15);
    private static final int DEFAULT_MAX_EXCHANGES = 8;
    private static final int DEFAULT_MAX_CONVERSATIONS = 10_000;

    private final ConcurrentMap<String, Conversation> conversations = new ConcurrentHashMap<>();
    private final LongSupplier nanoTime;
    private final long ttlNanos;
    private final int maxExchanges;
    private final int maxConversations;

    NexoraAiConversationMemory() {
        this(System::nanoTime, DEFAULT_TTL, DEFAULT_MAX_EXCHANGES, DEFAULT_MAX_CONVERSATIONS);
    }

    NexoraAiConversationMemory(LongSupplier nanoTime, Duration ttl, int maxExchanges, int maxConversations) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative() || maxExchanges < 1 || maxConversations < 1) {
            throw new IllegalArgumentException("Invalid conversation memory limits");
        }
        this.ttlNanos = ttl.toNanos();
        this.maxExchanges = maxExchanges;
        this.maxConversations = maxConversations;
    }

    List<Message> history(String playerId, boolean admin) {
        String key = key(playerId, admin);
        long now = nanoTime.getAsLong();
        Conversation conversation = conversations.compute(key, (ignored, existing) ->
                existing == null || now - existing.lastAccessNanos >= ttlNanos
                        ? new Conversation(now) : existing);
        List<Message> result;
        synchronized (conversation) {
            conversation.lastAccessNanos = now;
            result = List.copyOf(conversation.messages);
        }
        trimIfNeeded(now);
        return new History(result, conversation);
    }

    void clear(String playerId, boolean admin) {
        conversations.remove(key(playerId, admin));
    }

    void recordExchange(String playerId, boolean admin, History history,
                        String userMessage, String assistantMessage) {
        String key = key(playerId, admin);
        Objects.requireNonNull(userMessage, "userMessage");
        Objects.requireNonNull(assistantMessage, "assistantMessage");
        if (userMessage.isBlank() || assistantMessage.isBlank()
                || userMessage.length() > 800 || assistantMessage.length() > 1200) {
            throw new IllegalArgumentException("Conversation exchange is blank or oversized");
        }
        Objects.requireNonNull(history, "history");
        if (!(history.token() instanceof Conversation expected)) {
            throw new IllegalArgumentException("Invalid conversation history token");
        }
        long now = nanoTime.getAsLong();
        conversations.computeIfPresent(key, (ignored, current) -> {
            if (current != expected) return current;
            synchronized (current) {
                if (now - current.lastAccessNanos >= ttlNanos) return null;
                current.messages.addLast(new Message("user", userMessage));
                current.messages.addLast(new Message("assistant", assistantMessage));
                while (current.messages.size() > maxExchanges * 2) {
                    current.messages.removeFirst();
                }
                current.lastAccessNanos = now;
            }
            return current;
        });
        trimIfNeeded(now);
    }

    private void trimIfNeeded(long now) {
        if (conversations.size() <= maxConversations) return;
        conversations.entrySet().removeIf(entry ->
                now - entry.getValue().lastAccessNanos >= ttlNanos);
        if (conversations.size() <= maxConversations) return;
        List<Map.Entry<String, Conversation>> oldest = new ArrayList<>(conversations.entrySet());
        oldest.sort((left, right) -> Long.compare(
                left.getValue().lastAccessNanos, right.getValue().lastAccessNanos));
        int excess = conversations.size() - maxConversations;
        for (int i = 0; i < excess && i < oldest.size(); i++) {
            conversations.remove(oldest.get(i).getKey(), oldest.get(i).getValue());
        }
    }

    private static String key(String playerId, boolean admin) {
        Objects.requireNonNull(playerId, "playerId");
        if (playerId.isBlank() || playerId.length() > 256) {
            throw new IllegalArgumentException("Invalid conversation player ID");
        }
        return (admin ? "admin:" : "player:") + playerId;
    }

    record History(List<Message> messages, Object token) {
        History {
            messages = List.copyOf(messages);
            Objects.requireNonNull(token, "token");
        }
    }

    record Message(String role, String content) {
        Message {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(content, "content");
        }
    }

    private static final class Conversation {
        private final ArrayDeque<Message> messages = new ArrayDeque<>();
        private volatile long lastAccessNanos;

        private Conversation(long lastAccessNanos) {
            this.lastAccessNanos = lastAccessNanos;
        }
    }
}
