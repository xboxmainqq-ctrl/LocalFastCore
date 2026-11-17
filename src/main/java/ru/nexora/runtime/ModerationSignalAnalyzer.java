package ru.nexora.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded, privacy-conscious moderation signal extraction.
 *
 * <p>Only message fingerprints and ticks are retained; raw chat text is never
 * stored. Results are advisory and always require human review.</p>
 */
public final class ModerationSignalAnalyzer {
    private final int maximumMessagesPerWindow;
    private final int duplicateThreshold;
    private final int windowTicks;
    private final int maximumHistoryPerPlayer;
    private final int maximumTrackedPlayers;
    private final Map<UUID, ArrayDeque<MessageSignal>> history = new LinkedHashMap<>();

    public ModerationSignalAnalyzer() {
        this(8, 3, 200, 32, 10_000);
    }

    public ModerationSignalAnalyzer(
            int maximumMessagesPerWindow,
            int duplicateThreshold,
            int windowTicks,
            int maximumHistoryPerPlayer
    ) {
        this(maximumMessagesPerWindow, duplicateThreshold, windowTicks, maximumHistoryPerPlayer, 10_000);
    }

    public ModerationSignalAnalyzer(
            int maximumMessagesPerWindow,
            int duplicateThreshold,
            int windowTicks,
            int maximumHistoryPerPlayer,
            int maximumTrackedPlayers
    ) {
        if (maximumMessagesPerWindow < 2 || maximumMessagesPerWindow > 100
                || duplicateThreshold < 2 || duplicateThreshold > maximumHistoryPerPlayer
                || windowTicks < 1 || maximumHistoryPerPlayer < maximumMessagesPerWindow
                || maximumHistoryPerPlayer > 256 || maximumTrackedPlayers < 1
                || maximumTrackedPlayers > 100_000) {
            throw new IllegalArgumentException("Invalid moderation analysis limits");
        }
        this.maximumMessagesPerWindow = maximumMessagesPerWindow;
        this.duplicateThreshold = duplicateThreshold;
        this.windowTicks = windowTicks;
        this.maximumHistoryPerPlayer = maximumHistoryPerPlayer;
        this.maximumTrackedPlayers = maximumTrackedPlayers;
    }

    public synchronized Assessment recordChatMessage(UUID playerId, String message, long serverTick) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(message, "message");
        if (message.isBlank() || message.length() > 512 || serverTick < 0) {
            throw new IllegalArgumentException("Invalid moderation event");
        }

        ArrayDeque<MessageSignal> events = history.get(playerId);
        if (events != null && !events.isEmpty() && serverTick < events.getLast().tick()) {
            throw new IllegalArgumentException("Moderation events must be ordered by server tick");
        }
        if (events == null) {
            if (history.size() >= maximumTrackedPlayers) {
                Iterator<UUID> oldest = history.keySet().iterator();
                if (oldest.hasNext()) {
                    oldest.next();
                    oldest.remove();
                }
            }
            events = new ArrayDeque<>();
        } else {
            history.remove(playerId);
        }
        // LinkedHashMap order is used as a bounded least-recently-used player cache.
        history.put(playerId, events);
        events.addLast(new MessageSignal(serverTick, fingerprint(message)));
        while (events.size() > maximumHistoryPerPlayer) {
            events.removeFirst();
        }
        while (!events.isEmpty() && serverTick - events.getFirst().tick() > windowTicks) {
            events.removeFirst();
        }

        String currentFingerprint = fingerprint(message);
        int duplicateCount = 0;
        for (MessageSignal event : events) {
            if (event.fingerprint().equals(currentFingerprint)) {
                duplicateCount++;
            }
        }

        List<String> reasons = new ArrayList<>();
        int score = 0;
        if (duplicateCount >= duplicateThreshold) {
            reasons.add("REPEATED_IDENTICAL_MESSAGES");
            score = 85;
        }
        if (events.size() >= maximumMessagesPerWindow) {
            reasons.add("HIGH_MESSAGE_RATE");
            score = Math.max(score, 65);
        }
        Risk risk = score >= 80 ? Risk.HIGH : score >= 60 ? Risk.MEDIUM : Risk.LOW;
        return new Assessment(playerId, risk, score, reasons, true);
    }

    /** Explicitly discards the retained fingerprints for a player. */
    public synchronized void forgetPlayer(UUID playerId) {
        history.remove(Objects.requireNonNull(playerId, "playerId"));
    }

    public enum Risk {
        LOW,
        MEDIUM,
        HIGH
    }

    public record Assessment(
            UUID playerId,
            Risk risk,
            int score,
            List<String> reasons,
            boolean humanReviewRequired
    ) {
        public Assessment {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(risk, "risk");
            reasons = List.copyOf(reasons);
            if (score < 0 || score > 100 || !humanReviewRequired) {
                throw new IllegalArgumentException("Moderation assessments must be bounded and reviewed by a human");
            }
        }
    }

    private static String fingerprint(String message) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(message.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record MessageSignal(long tick, String fingerprint) {
    }
}
