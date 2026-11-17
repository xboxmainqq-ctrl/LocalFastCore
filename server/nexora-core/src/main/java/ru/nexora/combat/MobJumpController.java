package ru.nexora.combat;

import ru.nexora.world.World;
import ru.nexora.world.WorldCollision;
import ru.nexora.world.WorldLivingEntity;
import ru.nexora.world.WorldPathfinder;

import java.util.LinkedHashMap;
import java.util.Map;

/** Conservative one-block obstacle jumping for ground-bound mobs. */
public final class MobJumpController {
    public static final double JUMP_VELOCITY = 0.42;
    public static final double JUMP_CLEARANCE = 1.05;
    public static final long JUMP_COOLDOWN_MILLIS = 600L;
    public static final int DEFAULT_MAX_TRACKED = 5_000;

    private final int maxTracked;
    private final Map<Long, Long> nextJumpAt = new LinkedHashMap<>(16, 0.75f, true);
    private long jumps;
    private long rejected;
    private long evictions;

    public MobJumpController() {
        this(DEFAULT_MAX_TRACKED);
    }

    public MobJumpController(int maxTracked) {
        if (maxTracked < 1) throw new IllegalArgumentException("maxTracked must be positive");
        this.maxTracked = maxTracked;
    }

    public synchronized boolean tryJump(World world, WorldLivingEntity mob,
                                         double nextX, double nextZ, long nowMillis) {
        if (world == null || mob == null || !Double.isFinite(nextX) || !Double.isFinite(nextZ)
                || nowMillis < 0) throw new IllegalArgumentException("Invalid mob jump observation");
        if (!WorldPathfinder.isMobFootprintLoaded(world, mob.x(), mob.z())
                || LivingEntityAiService.isFlyingMob(mob.identifier())
                || nowMillis < nextJumpAt.getOrDefault(mob.runtimeId(), 0L)
                || !WorldCollision.isOnGround(world, mob.x(), mob.y(), mob.z())) {
            rejected++;
            return false;
        }

        double raisedY = mob.y() + JUMP_CLEARANCE;
        if (!WorldPathfinder.isMobMovementAreaLoaded(world, mob.x(), mob.z(), mob.x(), mob.z())
                || !WorldPathfinder.isMobMovementAreaLoaded(world, mob.x(), mob.z(), nextX, nextZ)
                || !WorldCollision.canMove(world, mob.x(), mob.y(), mob.z(),
                        mob.x(), raisedY, mob.z())
                || !WorldCollision.canMove(world, mob.x(), raisedY, mob.z(),
                        nextX, raisedY, nextZ)) {
            rejected++;
            return false;
        }

        if (world.livingEntities().setVelocity(mob.runtimeId(),
                mob.velocityX(), JUMP_VELOCITY, mob.velocityZ()).isEmpty()) {
            rejected++;
            return false;
        }
        nextJumpAt.put(mob.runtimeId(), safeNextJumpAt(nowMillis));
        jumps++;
        trim();
        return true;
    }

    public synchronized void clear(long runtimeId) {
        nextJumpAt.remove(runtimeId);
    }

    public synchronized Stats stats() {
        return new Stats(nextJumpAt.size(), jumps, rejected, evictions);
    }

    private static long safeNextJumpAt(long nowMillis) {
        return nowMillis > Long.MAX_VALUE - JUMP_COOLDOWN_MILLIS
                ? Long.MAX_VALUE : nowMillis + JUMP_COOLDOWN_MILLIS;
    }

    private void trim() {
        while (nextJumpAt.size() > maxTracked) {
            Long eldest = nextJumpAt.keySet().iterator().next();
            nextJumpAt.remove(eldest);
            evictions++;
        }
    }

    public record Stats(int tracked, long jumps, long rejected, long evictions) {
        public String summary() {
            return "tracked=" + tracked + ", jumps=" + jumps
                    + ", rejected=" + rejected + ", evictions=" + evictions;
        }
    }
}
