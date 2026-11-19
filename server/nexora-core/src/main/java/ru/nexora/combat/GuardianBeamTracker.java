package ru.nexora.combat;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Server-side charge/cooldown state for guardian beam attacks.
 * A beam must keep the same target in range and in line of sight for the full charge.
 */
public final class GuardianBeamTracker {
    public static final long CHARGE_MILLIS = 1_500L;
    public static final long RECOVERY_MILLIS = 5_000L;
    public static final int MAX_TRACKED_GUARDIANS = 5_000;

    private final Map<Long, State> states = new HashMap<>();

    public synchronized Result tick(long guardianRuntimeId, String targetNetworkId,
                                    boolean canSeeTarget, long nowMillis) {
        if (guardianRuntimeId <= 0 || nowMillis < 0) {
            throw new IllegalArgumentException("Invalid guardian beam tick");
        }
        if (targetNetworkId == null || targetNetworkId.isBlank() || !canSeeTarget) {
            states.remove(guardianRuntimeId);
            return Result.IDLE;
        }
        State state = states.get(guardianRuntimeId);
        if (state == null || !state.targetNetworkId().equals(targetNetworkId)) {
            if (state == null && states.size() >= MAX_TRACKED_GUARDIANS) {
                return Result.IDLE;
            }
            states.put(guardianRuntimeId, new State(targetNetworkId, nowMillis, 0L));
            return new Result(true, false, 0.0);
        }
        if (nowMillis < state.nextAttackAtMillis()) {
            return new Result(false, false, 1.0);
        }
        long chargeElapsed = Math.max(0L, nowMillis - state.chargeStartedAtMillis());
        if (chargeElapsed < CHARGE_MILLIS) {
            return new Result(true, false, Math.min(1.0, (double) chargeElapsed / CHARGE_MILLIS));
        }
        long nextAttack = nowMillis > Long.MAX_VALUE - RECOVERY_MILLIS
                ? Long.MAX_VALUE : nowMillis + RECOVERY_MILLIS;
        states.put(guardianRuntimeId, new State(targetNetworkId, nowMillis, nextAttack));
        return new Result(false, true, 1.0);
    }

    public synchronized void clear(long guardianRuntimeId) {
        states.remove(guardianRuntimeId);
    }

    public synchronized void clearAll() {
        states.clear();
    }

    private record State(String targetNetworkId, long chargeStartedAtMillis, long nextAttackAtMillis) {
        private State {
            Objects.requireNonNull(targetNetworkId, "targetNetworkId");
        }
    }

    public record Result(boolean charging, boolean fired, double progress) {
        private static final Result IDLE = new Result(false, false, 0.0);
        public Result {
            if (!Double.isFinite(progress) || progress < 0.0 || progress > 1.0
                    || (charging && fired)) {
                throw new IllegalArgumentException("Invalid guardian beam result");
            }
        }
    }
}
