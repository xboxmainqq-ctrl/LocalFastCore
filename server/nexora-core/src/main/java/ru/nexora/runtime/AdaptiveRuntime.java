package ru.nexora.runtime;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * NEXORA Adaptive Runtime: a server-work admission controller.
 *
 * <p>Unlike a fixed task queue, the runtime continuously reserves a bounded
 * execution budget for critical work and spends the remainder on lower priority
 * work. It never owns gameplay state; it only decides which work is allowed to
 * enter the current execution slice.</p>
 */
public final class AdaptiveRuntime {
    public enum Lane { PLAYER, WORLD, ENTITY, AI, BACKGROUND }

    public enum Admission { ALLOW, DEFER }

    private final long tickBudgetNanos;
    private final long emergencyReserveNanos;
    private final EnumMap<Lane, Long> reservedNanos = new EnumMap<>(Lane.class);
    private final EnumMap<Lane, Long> consumedNanos = new EnumMap<>(Lane.class);

    public AdaptiveRuntime(long tickBudgetNanos, long emergencyReserveNanos) {
        if (tickBudgetNanos < 1_000_000L) throw new IllegalArgumentException("tick budget too small");
        if (emergencyReserveNanos < 0 || emergencyReserveNanos >= tickBudgetNanos) {
            throw new IllegalArgumentException("invalid emergency reserve");
        }
        this.tickBudgetNanos = tickBudgetNanos;
        this.emergencyReserveNanos = emergencyReserveNanos;
        reset();
    }

    public synchronized void reset() {
        for (Lane lane : Lane.values()) {
            reservedNanos.put(lane, 0L);
            consumedNanos.put(lane, 0L);
        }
        reservedNanos.put(Lane.PLAYER, Math.max(1L, tickBudgetNanos / 4));
        reservedNanos.put(Lane.WORLD, Math.max(1L, tickBudgetNanos / 5));
        reservedNanos.put(Lane.ENTITY, Math.max(1L, tickBudgetNanos / 10));
        reservedNanos.put(Lane.AI, Math.max(1L, tickBudgetNanos / 10));
        reservedNanos.put(Lane.BACKGROUND, 0L);
    }

    public synchronized Admission admit(Lane lane, long estimatedNanos, boolean critical) {
        Objects.requireNonNull(lane, "lane");
        if (estimatedNanos < 0) throw new IllegalArgumentException("estimatedNanos < 0");

        long consumed = totalConsumed();
        long hardLimit = tickBudgetNanos - emergencyReserveNanos;
        if (!critical && consumed + estimatedNanos > hardLimit) return Admission.DEFER;

        long laneReserve = reservedNanos.get(lane);
        long laneConsumed = consumedNanos.get(lane);
        if (!critical && laneReserve > 0 && laneConsumed + estimatedNanos > laneReserve
                && consumed + estimatedNanos > hardLimit - Math.max(0L, laneReserve - laneConsumed)) {
            return Admission.DEFER;
        }

        consumedNanos.put(lane, laneConsumed + estimatedNanos);
        return Admission.ALLOW;
    }

    public synchronized void record(Lane lane, long elapsedNanos) {
        if (elapsedNanos < 0) throw new IllegalArgumentException("elapsedNanos < 0");
        consumedNanos.put(lane, consumedNanos.get(lane) + elapsedNanos);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(tickBudgetNanos, emergencyReserveNanos,
                Map.copyOf(reservedNanos), Map.copyOf(consumedNanos), totalConsumed());
    }

    private long totalConsumed() {
        long total = 0;
        for (long value : consumedNanos.values()) {
            total += value;
        }
        return total;
    }

    public record Snapshot(long tickBudgetNanos, long emergencyReserveNanos,
                           Map<Lane, Long> reservedNanos,
                           Map<Lane, Long> consumedNanos,
                           long totalConsumedNanos) {}
}
