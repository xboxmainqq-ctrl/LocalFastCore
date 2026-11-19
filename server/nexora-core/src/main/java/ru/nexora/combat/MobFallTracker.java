package ru.nexora.combat;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded fall-distance tracking for ground-bound mobs; applies damage only on landing. */
public final class MobFallTracker {
    public static final double SAFE_FALL_DISTANCE = 3.0;
    public static final int DEFAULT_MAX_TRACKED = 5_000;

    private final int maxTracked;
    private final Map<Long, FallState> airborne = new LinkedHashMap<>(16, 0.75f, true);
    private long completedFalls;
    private long totalDamage;
    private long evictions;

    public MobFallTracker() {
        this(DEFAULT_MAX_TRACKED);
    }

    public MobFallTracker(int maxTracked) {
        if (maxTracked < 1) throw new IllegalArgumentException("maxTracked must be positive");
        this.maxTracked = maxTracked;
    }

    public synchronized Result update(long runtimeId, double y, boolean grounded, boolean flying) {
        if (runtimeId <= 0 || !Double.isFinite(y)) {
            throw new IllegalArgumentException("Invalid mob fall observation");
        }
        if (flying) {
            airborne.remove(runtimeId);
            return new Result(0.0, 0);
        }
        if (!grounded) {
            FallState previous = airborne.get(runtimeId);
            if (previous == null) airborne.put(runtimeId, new FallState(y));
            else if (y > previous.highestY()) airborne.put(runtimeId, new FallState(y));
            trim();
            return new Result(0.0, 0);
        }

        FallState fall = airborne.remove(runtimeId);
        if (fall == null) return new Result(0.0, 0);
        double distance = Math.max(0.0, fall.highestY() - y);
        int damage = Math.max(0, (int) Math.ceil(distance - SAFE_FALL_DISTANCE));
        if (distance > 0.0) completedFalls++;
        totalDamage += damage;
        return new Result(distance, damage);
    }

    public synchronized void clear(long runtimeId) {
        airborne.remove(runtimeId);
    }

    public synchronized Stats stats() {
        return new Stats(airborne.size(), completedFalls, totalDamage, evictions);
    }

    private void trim() {
        while (airborne.size() > maxTracked) {
            Long eldest = airborne.keySet().iterator().next();
            airborne.remove(eldest);
            evictions++;
        }
    }

    private record FallState(double highestY) { }

    public record Result(double fallDistance, int damage) { }

    public record Stats(int airborne, long completedFalls, long totalDamage, long evictions) {
        public String summary() {
            return "airborne=" + airborne + ", falls=" + completedFalls
                    + ", damage=" + totalDamage + ", evictions=" + evictions;
        }
    }
}
