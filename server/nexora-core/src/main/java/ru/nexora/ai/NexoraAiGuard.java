package ru.nexora.ai;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Bounded, provider-independent network anomaly observer.
 *
 * It records per-peer inbound frame/byte volume in fixed monotonic windows.
 * Signals are evidence for human review only: this class never disconnects,
 * throttles, or punishes a peer.
 */
public final class NexoraAiGuard {
    public static final long DEFAULT_WINDOW_NANOS = 5_000_000_000L;
    public static final int DEFAULT_MAX_FRAMES_PER_WINDOW = 300;
    public static final long DEFAULT_MAX_BYTES_PER_WINDOW = 1_048_576L;
    public static final int DEFAULT_MAX_TRACKED_PEERS = 1_024;

    private final long windowNanos;
    private final int maxFramesPerWindow;
    private final long maxBytesPerWindow;
    private final int maxTrackedPeers;
    private final Consumer<GuardEvent> eventSink;
    private static final long MOVEMENT_WINDOW_NANOS = 10_000_000_000L;
    private static final double MOVEMENT_ERROR_THRESHOLD = 0.75;
    private static final int MOVEMENT_SIGNALS_THRESHOLD = 4;

    private final LinkedHashMap<String, PeerWindow> peers = new LinkedHashMap<>();
    private final LinkedHashMap<String, PlayerSignals> playerSignals = new LinkedHashMap<>();
    private final LinkedHashMap<String, CombatWindow> combatWindows = new LinkedHashMap<>();
    private final LinkedHashMap<String, CombatCase> combatCases = new LinkedHashMap<>();
    private static final long COMBAT_WINDOW_NANOS = 5_000_000_000L;
    private static final long CASE_TTL_NANOS = 1_800_000_000_000L;
    private static final int KILLAURA_HIT_THRESHOLD = 65;
    private static final int KILLAURA_TARGET_SWITCH_THRESHOLD = 4;
    private long totalSignals;

    public NexoraAiGuard() {
        this(DEFAULT_WINDOW_NANOS, DEFAULT_MAX_FRAMES_PER_WINDOW,
                DEFAULT_MAX_BYTES_PER_WINDOW, DEFAULT_MAX_TRACKED_PEERS, ignored -> { });
    }

    public NexoraAiGuard(long windowNanos, int maxFramesPerWindow, long maxBytesPerWindow,
                         int maxTrackedPeers, Consumer<GuardEvent> eventSink) {
        if (windowNanos < 1 || maxFramesPerWindow < 1 || maxBytesPerWindow < 1
                || maxTrackedPeers < 1) {
            throw new IllegalArgumentException("Guard limits must be positive");
        }
        this.windowNanos = windowNanos;
        this.maxFramesPerWindow = maxFramesPerWindow;
        this.maxBytesPerWindow = maxBytesPerWindow;
        this.maxTrackedPeers = maxTrackedPeers;
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
    }

    /** Records one received transport frame. The peer key is never included in aggregate summaries. */
    public void recordInboundFrame(String peerId, int frameBytes, long nowNanos) {
        Objects.requireNonNull(peerId, "peerId");
        if (peerId.isBlank() || frameBytes < 0) throw new IllegalArgumentException("Invalid inbound frame");
        GuardEvent event = null;
        synchronized (this) {
            PeerWindow window = peers.get(peerId);
            if (window == null) {
                if (peers.size() >= maxTrackedPeers) {
                    String eldest = peers.keySet().iterator().next();
                    peers.remove(eldest);
                }
                window = new PeerWindow(nowNanos);
                peers.put(peerId, window);
            } else if (nowNanos < window.startedAtNanos
                    || nowNanos - window.startedAtNanos >= windowNanos) {
                window.reset(nowNanos);
            }

            window.frames++;
            window.bytes += frameBytes;
            if (!window.signalled && (window.frames > maxFramesPerWindow
                    || window.bytes > maxBytesPerWindow)) {
                window.signalled = true;
                totalSignals++;
                String reason = window.frames > maxFramesPerWindow
                        ? "frame-rate threshold exceeded" : "inbound-byte threshold exceeded";
                event = new GuardEvent(window.frames, window.bytes, reason);
            }
        }
        publish(event);
    }

    /**
     * Observes server-authoritative movement reconciliation. Repeated large errors
     * are review signals only; a single correction is never treated as cheating.
     */
    public void recordMovementDiscrepancy(
            String playerKey, double horizontalError, double verticalError, long nowNanos
    ) {
        Objects.requireNonNull(playerKey, "playerKey");
        if (playerKey.isBlank() || !Double.isFinite(horizontalError)
                || !Double.isFinite(verticalError) || horizontalError < 0 || verticalError < 0) {
            throw new IllegalArgumentException("Invalid movement evidence");
        }
        GuardEvent event = null;
        synchronized (this) {
            PlayerSignals signals = playerSignals.get(playerKey);
            if (signals == null) {
                if (playerSignals.size() >= maxTrackedPeers) {
                    playerSignals.remove(playerSignals.keySet().iterator().next());
                }
                signals = new PlayerSignals(nowNanos);
                playerSignals.put(playerKey, signals);
            } else if (nowNanos < signals.movementStartedAtNanos
                    || nowNanos - signals.movementStartedAtNanos >= MOVEMENT_WINDOW_NANOS) {
                signals.resetMovement(nowNanos);
            }

            String reason = null;
            if (horizontalError > MOVEMENT_ERROR_THRESHOLD && !signals.horizontalSignalled) {
                signals.horizontalCorrections++;
                if (signals.horizontalCorrections >= MOVEMENT_SIGNALS_THRESHOLD) {
                    signals.horizontalSignalled = true;
                    reason = "repeated horizontal movement discrepancy (possible speed)";
                }
            }
            if (verticalError > MOVEMENT_ERROR_THRESHOLD && !signals.verticalSignalled) {
                signals.verticalCorrections++;
                if (signals.verticalCorrections >= MOVEMENT_SIGNALS_THRESHOLD) {
                    signals.verticalSignalled = true;
                    reason = "repeated vertical movement discrepancy (possible fly)";
                }
            }
            if (reason != null) {
                totalSignals++;
                event = new GuardEvent(signals.horizontalCorrections + signals.verticalCorrections,
                        0, reason);
            }
        }
        publish(event);
    }

    /** Records repeated attack attempts that exceed the server's configured weapon reach. */
    public void recordOutOfReachAttack(
            String playerKey, double observedDistance, double allowedReach, long nowNanos
    ) {
        Objects.requireNonNull(playerKey, "playerKey");
        if (playerKey.isBlank() || !Double.isFinite(observedDistance)
                || !Double.isFinite(allowedReach) || observedDistance < 0 || allowedReach < 0) {
            throw new IllegalArgumentException("Invalid reach evidence");
        }
        GuardEvent event = null;
        synchronized (this) {
            PlayerSignals signals = playerSignals.get(playerKey);
            if (signals == null) {
                if (playerSignals.size() >= maxTrackedPeers) {
                    playerSignals.remove(playerSignals.keySet().iterator().next());
                }
                signals = new PlayerSignals(nowNanos);
                playerSignals.put(playerKey, signals);
            } else if (nowNanos < signals.movementStartedAtNanos
                    || nowNanos - signals.movementStartedAtNanos >= MOVEMENT_WINDOW_NANOS) {
                signals.resetMovement(nowNanos);
            }
            if (observedDistance > allowedReach + MOVEMENT_ERROR_THRESHOLD
                    && !signals.reachSignalled) {
                signals.reachAttempts++;
                if (signals.reachAttempts >= 5) {
                    signals.reachSignalled = true;
                    totalSignals++;
                    event = new GuardEvent(signals.reachAttempts, 0,
                            "repeated out-of-range attack attempts (possible reach)");
                }
            }
        }
        publish(event);
    }

    /**
     * Records a server-validated hit after cooldown, reach and line-of-sight checks.
     * A KillAura review signal requires an unusually high hit rate and repeated
     * target switching in the same monotonic window; it never applies punishment.
     */
    public void recordSuccessfulAttack(
            String playerKey, String displayName, String targetKey, long nowNanos
    ) {
        Objects.requireNonNull(playerKey, "playerKey");
        Objects.requireNonNull(targetKey, "targetKey");
        if (playerKey.isBlank() || targetKey.isBlank()) {
            throw new IllegalArgumentException("Attack identities must not be blank");
        }
        GuardEvent event = null;
        synchronized (this) {
            CombatWindow window = combatWindows.get(playerKey);
            if (window == null) {
                if (combatWindows.size() >= maxTrackedPeers) {
                    combatWindows.remove(combatWindows.keySet().iterator().next());
                }
                window = new CombatWindow(nowNanos);
                combatWindows.put(playerKey, window);
            } else if (nowNanos < window.startedAtNanos
                    || nowNanos - window.startedAtNanos >= COMBAT_WINDOW_NANOS) {
                window.reset(nowNanos);
            }
            window.hits++;
            if (window.lastTarget != null && !window.lastTarget.equals(targetKey)) {
                window.targetSwitches++;
            }
            window.lastTarget = targetKey;
            if (!window.signalled && window.hits >= KILLAURA_HIT_THRESHOLD
                    && window.targetSwitches >= KILLAURA_TARGET_SWITCH_THRESHOLD) {
                window.signalled = true;
                totalSignals++;
                String safeName = sanitizePlayerName(displayName);
                combatCases.put(playerKey, new CombatCase(safeName, window.hits,
                        window.targetSwitches, nowNanos));
                while (combatCases.size() > 64) {
                    combatCases.remove(combatCases.keySet().iterator().next());
                }
                event = new GuardEvent(window.hits, 0,
                        "high-rate multi-target attacks (possible KillAura)");
            }
        }
        publish(event);
    }

    /** Read-only, identifier-safe moderator view of recent KillAura review cases. */
    public synchronized String combatReviewSummary(long nowNanos) {
        combatCases.entrySet().removeIf(entry -> nowNanos < entry.getValue().lastSeenNanos
                || nowNanos - entry.getValue().lastSeenNanos >= CASE_TTL_NANOS);
        if (combatCases.isEmpty()) {
            return "KillAura review: no confirmed high-rate multi-target patterns. "
                    + "Only server-validated hits are counted; no automatic punishment.";
        }
        StringBuilder result = new StringBuilder("KillAura review cases (review-only):");
        combatCases.values().stream()
                .sorted((left, right) -> Long.compare(right.lastSeenNanos, left.lastSeenNanos))
                .limit(8)
                .forEach(value -> result.append("\n")
                        .append(value.displayName)
                        .append(" — ").append(value.hits).append(" hits / 5s, ")
                        .append(value.targetSwitches).append(" target switches"));
        return result.append("\nNo automatic punishment is applied.").toString();
    }

    private static String sanitizePlayerName(String displayName) {
        if (displayName == null || displayName.isBlank()) return "unknown player";
        StringBuilder cleaned = new StringBuilder();
        displayName.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint) && codePoint != '§')
                .limit(24)
                .forEach(cleaned::appendCodePoint);
        String safe = cleaned.toString().trim();
        return safe.isEmpty() ? "unknown player" : safe;
    }

    private void publish(GuardEvent event) {
        if (event == null) return;
        try {
            eventSink.accept(event);
        } catch (RuntimeException ignored) {
            // Security telemetry must never interrupt normal packet processing.
        }
    }

    public synchronized void onDisconnected(String peerId) {
        if (peerId != null) {
            peers.remove(peerId);
            playerSignals.remove(peerId);
            combatWindows.remove(peerId);
        }
    }

    public synchronized void onPlayerDisconnected(String playerKey) {
        if (playerKey != null) {
            playerSignals.remove(playerKey);
            combatWindows.remove(playerKey);
        }
    }

    /** Aggregate-only status suitable for operator diagnostics; no peer identifiers are exposed. */
    public synchronized Snapshot snapshot() {
        int signalledPeers = 0;
        int flaggedPlayers = 0;
        long frames = 0;
        long bytes = 0;
        for (PeerWindow peer : peers.values()) {
            frames += peer.frames;
            bytes += peer.bytes;
            if (peer.signalled) signalledPeers++;
        }
        for (PlayerSignals player : playerSignals.values()) {
            if (player.horizontalSignalled || player.verticalSignalled || player.reachSignalled) flaggedPlayers++;
        }
        return new Snapshot(peers.size(), signalledPeers, playerSignals.size(),
                flaggedPlayers, frames, bytes, totalSignals);
    }

    public record GuardEvent(long framesInWindow, long bytesInWindow, String reason) {
        public GuardEvent {
            Objects.requireNonNull(reason, "reason");
        }
    }

    public record Snapshot(int trackedPeers, int signalledPeers, int trackedPlayers,
                           int flaggedPlayers, long framesInCurrentWindows,
                           long bytesInCurrentWindows, long totalSignalsSinceStart) {
        public String summary() {
            return "NexoraAI Guard network observer: tracked=" + trackedPeers
                    + ", flagged=" + signalledPeers
                    + ", trackedPlayers=" + trackedPlayers
                    + ", suspiciousPlayers=" + flaggedPlayers
                    + ", frames(current windows)=" + framesInCurrentWindows
                    + ", bytes(current windows)=" + bytesInCurrentWindows
                    + ", signals since start=" + totalSignalsSinceStart
                    + ". Signals are review-only; no automatic punishment is applied.";
        }
    }

    private record CombatCase(String displayName, int hits, int targetSwitches, long lastSeenNanos) { }

    private static final class CombatWindow {
        private long startedAtNanos;
        private int hits;
        private int targetSwitches;
        private String lastTarget;
        private boolean signalled;

        private CombatWindow(long startedAtNanos) { this.startedAtNanos = startedAtNanos; }
        private void reset(long nowNanos) {
            startedAtNanos = nowNanos;
            hits = 0;
            targetSwitches = 0;
            lastTarget = null;
            signalled = false;
        }
    }

    private static final class PlayerSignals {
        private long movementStartedAtNanos;
        private int horizontalCorrections;
        private int verticalCorrections;
        private int reachAttempts;
        private boolean horizontalSignalled;
        private boolean verticalSignalled;
        private boolean reachSignalled;

        private PlayerSignals(long startedAtNanos) {
            movementStartedAtNanos = startedAtNanos;
        }

        private void resetMovement(long nowNanos) {
            movementStartedAtNanos = nowNanos;
            horizontalCorrections = 0;
            verticalCorrections = 0;
            reachAttempts = 0;
            horizontalSignalled = false;
            verticalSignalled = false;
            reachSignalled = false;
        }
    }

    private static final class PeerWindow {
        private long startedAtNanos;
        private long frames;
        private long bytes;
        private boolean signalled;

        private PeerWindow(long startedAtNanos) {
            this.startedAtNanos = startedAtNanos;
        }

        private void reset(long nowNanos) {
            startedAtNanos = nowNanos;
            frames = 0;
            bytes = 0;
            signalled = false;
        }
    }
}
