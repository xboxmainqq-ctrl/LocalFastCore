package ru.nexora.combat;

import ru.nexora.world.WorldLivingEntity;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic server-authoritative behavior for living mobs.
 * Hostile mobs pursue players; non-hostile mobs flee nearby players and otherwise wander.
 */
public final class LivingEntityAiService {
    public static final double ACQUIRE_RANGE = 16.0;
    public static final double ATTACK_RANGE = 1.8;
    public static final double WALK_SPEED_PER_TICK = 0.23;
    public static final double FLEE_RANGE = 6.0;
    public static final long ATTACK_COOLDOWN_MILLIS = 1_000;
    public static final long WANDER_INTERVAL_MILLIS = 3_000;
    public static final float BASE_ATTACK_DAMAGE = 3.0f;

    public Decision decide(WorldLivingEntity mob, List<PlayerTarget> players, long nowMillis,
                           long nextAttackAtMillis) {
        Objects.requireNonNull(mob, "mob");
        Objects.requireNonNull(players, "players");
        if (nowMillis < 0 || nextAttackAtMillis < 0) {
            throw new IllegalArgumentException("AI timestamps must be non-negative");
        }
        if (mob.health() <= 0) return Decision.idle(mob.runtimeId());

        PlayerTarget nearest = players.stream()
                .filter(Objects::nonNull)
                .filter(PlayerTarget::alive)
                .min(Comparator.comparingDouble(player -> distanceSquared(mob, player)))
                .orElse(null);

        if (isHostile(mob.identifier())) {
            if (nearest == null || distanceSquared(mob, nearest) > ACQUIRE_RANGE * ACQUIRE_RANGE) {
                return Decision.idle(mob.runtimeId());
            }
            return pursue(mob, nearest, nowMillis, nextAttackAtMillis);
        }

        if (nearest != null && distanceSquared(mob, nearest) <= FLEE_RANGE * FLEE_RANGE) {
            return flee(mob, nearest);
        }
        return wander(mob, nowMillis);
    }

    private Decision pursue(WorldLivingEntity mob, PlayerTarget target, long nowMillis,
                            long nextAttackAtMillis) {
        double dx = target.x() - mob.x();
        double dy = target.y() - mob.y();
        double dz = target.z() - mob.z();
        double horizontal = Math.hypot(dx, dz);
        if (dx * dx + dy * dy + dz * dz <= ATTACK_RANGE * ATTACK_RANGE) {
            if (nowMillis >= nextAttackAtMillis) {
                return new Decision(mob.runtimeId(), target.networkId(), 0, 0, 0,
                        attackDamageFor(mob.identifier()), true);
            }
            return Decision.idle(mob.runtimeId());
        }
        if (horizontal < 0.0001) return Decision.idle(mob.runtimeId());
        double step = Math.min(WALK_SPEED_PER_TICK, Math.max(0, horizontal - 1.0));
        return new Decision(mob.runtimeId(), target.networkId(),
                dx / horizontal * step, 0, dz / horizontal * step, 0, false);
    }

    private Decision flee(WorldLivingEntity mob, PlayerTarget threat) {
        double dx = mob.x() - threat.x();
        double dz = mob.z() - threat.z();
        double length = Math.hypot(dx, dz);
        if (length < 0.0001) {
            double angle = stableAngle(mob.runtimeId());
            dx = Math.cos(angle);
            dz = Math.sin(angle);
            length = 1.0;
        }
        return new Decision(mob.runtimeId(), threat.networkId(),
                dx / length * WALK_SPEED_PER_TICK, 0,
                dz / length * WALK_SPEED_PER_TICK, 0, false);
    }

    private Decision wander(WorldLivingEntity mob, long nowMillis) {
        long interval = nowMillis / WANDER_INTERVAL_MILLIS;
        long seed = mob.runtimeId() * 31L + interval * 0x9E3779B97F4A7C15L;
        double angle = stableAngle(seed);
        // A short pause between wander steps keeps passive mobs from looking mechanical.
        if (Math.floorMod(seed, 5) == 0) return Decision.idle(mob.runtimeId());
        return new Decision(mob.runtimeId(), null,
                Math.cos(angle) * WALK_SPEED_PER_TICK * 0.55, 0,
                Math.sin(angle) * WALK_SPEED_PER_TICK * 0.55, 0, false);
    }

    private static double stableAngle(long seed) {
        long mixed = seed ^ (seed >>> 33);
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        return (mixed & 0x1fffffffffffffL) / (double) 0x20000000000000L * Math.PI * 2.0;
    }

    public static float attackDamageFor(String identifier) {
        return switch (identifier) {
            case "minecraft:silverfish" -> 1.0f;
            case "minecraft:spider", "minecraft:cave_spider", "minecraft:endermite" -> 2.0f;
            case "minecraft:zombified_piglin", "minecraft:piglin" -> 5.0f;
            case "minecraft:zoglin", "minecraft:ravager", "minecraft:warden" -> 6.0f;
            case "minecraft:iron_golem", "minecraft:elder_guardian" -> 6.0f;
            default -> BASE_ATTACK_DAMAGE;
        };
    }

    /** Hostile families currently use melee pursuit; ranged behavior is handled separately. */
    public static boolean isHostile(String identifier) {
        return switch (identifier) {
            case "minecraft:zombie", "minecraft:husk", "minecraft:drowned",
                    "minecraft:zombified_piglin", "minecraft:zoglin",
                    "minecraft:spider", "minecraft:cave_spider", "minecraft:silverfish",
                    "minecraft:endermite", "minecraft:skeleton", "minecraft:stray",
                    "minecraft:wither_skeleton", "minecraft:creeper", "minecraft:witch",
                    "minecraft:slime", "minecraft:magma_cube", "minecraft:phantom",
                    "minecraft:blaze", "minecraft:ghast", "minecraft:guardian",
                    "minecraft:elder_guardian", "minecraft:shulker", "minecraft:vindicator",
                    "minecraft:evoker", "minecraft:pillager", "minecraft:ravager",
                    "minecraft:hoglin", "minecraft:piglin", "minecraft:piglin_brute",
                    "minecraft:warden", "minecraft:breeze",
                    "minecraft:bogged", "minecraft:breeze" -> true;
            default -> false;
        };
    }

    /** Kept as a compatibility alias for existing callers and tests. */
    public static boolean isSupportedHostile(String identifier) {
        return isHostile(identifier);
    }

    private static double distanceSquared(WorldLivingEntity mob, PlayerTarget player) {
        double dx = player.x() - mob.x();
        double dy = player.y() - mob.y();
        double dz = player.z() - mob.z();
        return dx * dx + dy * dy + dz * dz;
    }

    public record PlayerTarget(String networkId, double x, double y, double z, boolean alive) {
        public PlayerTarget {
            Objects.requireNonNull(networkId, "networkId");
            if (networkId.isBlank() || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("Invalid AI player target");
            }
        }
    }

    public record Decision(long mobRuntimeId, String targetNetworkId,
                           double moveX, double moveY, double moveZ,
                           float attackDamage, boolean attack) {
        public Decision {
            if (mobRuntimeId <= 0 || !Double.isFinite(moveX) || !Double.isFinite(moveY)
                    || !Double.isFinite(moveZ) || !Float.isFinite(attackDamage) || attackDamage < 0) {
                throw new IllegalArgumentException("Invalid living actor AI decision");
            }
            if (attack && (targetNetworkId == null || attackDamage <= 0
                    || moveX != 0 || moveY != 0 || moveZ != 0)) {
                throw new IllegalArgumentException("Invalid AI attack decision");
            }
        }

        static Decision idle(long runtimeId) {
            return new Decision(runtimeId, null, 0, 0, 0, 0, false);
        }

        public boolean moving() {
            return moveX != 0 || moveY != 0 || moveZ != 0;
        }
    }
}
