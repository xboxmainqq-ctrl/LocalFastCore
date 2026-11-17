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
        return decide(mob, players, nowMillis, nextAttackAtMillis, null);
    }

    public Decision decide(WorldLivingEntity mob, List<PlayerTarget> players, long nowMillis,
                           long nextAttackAtMillis, String provokedByNetworkId) {
        Objects.requireNonNull(mob, "mob");
        Objects.requireNonNull(players, "players");
        if (nowMillis < 0 || nextAttackAtMillis < 0) {
            throw new IllegalArgumentException("AI timestamps must be non-negative");
        }
        if (mob.health() <= 0) return Decision.idle(mob.runtimeId());

        PlayerTarget nearest = nearestAlivePlayer(mob, players);

        if (isHostile(mob.identifier())) {
            if (nearest == null || distanceSquared(mob, nearest) > ACQUIRE_RANGE * ACQUIRE_RANGE) {
                return Decision.idle(mob.runtimeId());
            }
            return pursue(mob, nearest, nowMillis, nextAttackAtMillis);
        }

        if (isPassivePrey(mob.identifier())) {
            if (nearest != null && distanceSquared(mob, nearest) <= FLEE_RANGE * FLEE_RANGE) {
                return flee(mob, nearest);
            }
            return wander(mob, nowMillis);
        }

        if (provokedByNetworkId != null && !provokedByNetworkId.isBlank()) {
            PlayerTarget provoker = null;
            for (PlayerTarget player : players) {
                if (player != null && player.alive()
                        && player.networkId().equals(provokedByNetworkId)) {
                    provoker = player;
                    break;
                }
            }
            if (provoker != null
                    && distanceSquared(mob, provoker) <= ACQUIRE_RANGE * ACQUIRE_RANGE) {
                return pursue(mob, provoker, nowMillis, nextAttackAtMillis);
            }
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

    /** Passive prey flee from nearby players instead of treating every mob as frightened. */
    public static boolean isPassivePrey(String identifier) {
        return switch (identifier) {
            case "minecraft:allay", "minecraft:armadillo", "minecraft:axolotl",
                    "minecraft:bat", "minecraft:camel", "minecraft:cat", "minecraft:chicken",
                    "minecraft:cod", "minecraft:cow", "minecraft:donkey", "minecraft:fox",
                    "minecraft:frog", "minecraft:glow_squid", "minecraft:goat",
                    "minecraft:horse", "minecraft:llama", "minecraft:mooshroom",
                    "minecraft:mule", "minecraft:ocelot", "minecraft:parrot",
                    "minecraft:pig", "minecraft:pufferfish", "minecraft:rabbit",
                    "minecraft:salmon", "minecraft:sheep", "minecraft:sniffer",
                    "minecraft:squid", "minecraft:strider",
                    "minecraft:tadpole", "minecraft:trader_llama", "minecraft:tropical_fish",
                    "minecraft:turtle" -> true;
            default -> false;
        };
    }

    /** Hostile families pursue players; neutral mobs only retaliate after being hit. */
    public static boolean isHostile(String identifier) {
        return switch (identifier) {
            case "minecraft:zombie", "minecraft:zombie_villager", "minecraft:husk", "minecraft:drowned",
                    "minecraft:zoglin",
                    "minecraft:spider", "minecraft:cave_spider", "minecraft:silverfish",
                    "minecraft:endermite", "minecraft:skeleton", "minecraft:stray",
                    "minecraft:wither_skeleton", "minecraft:creeper", "minecraft:witch",
                    "minecraft:slime", "minecraft:magma_cube", "minecraft:phantom",
                    "minecraft:blaze", "minecraft:ghast", "minecraft:guardian",
                    "minecraft:elder_guardian", "minecraft:shulker", "minecraft:vindicator",
                    "minecraft:evoker", "minecraft:pillager", "minecraft:ravager",
                    "minecraft:hoglin", "minecraft:piglin_brute", "minecraft:ender_dragon",
                    "minecraft:warden", "minecraft:breeze",
                    "minecraft:bogged" -> true;
            default -> false;
        };
    }

    /** Kept as a compatibility alias for existing callers and tests. */
    public static boolean isSupportedHostile(String identifier) {
        return isHostile(identifier);
    }

    /**
     * Finds the nearest living player without allocating a Stream pipeline.
     * This method is called once per mob update, so keep the scan allocation-free.
     */
    public static PlayerTarget nearestAlivePlayer(WorldLivingEntity mob, List<PlayerTarget> players) {
        Objects.requireNonNull(mob, "mob");
        Objects.requireNonNull(players, "players");
        PlayerTarget nearest = null;
        double nearestDistanceSquared = Double.POSITIVE_INFINITY;
        for (PlayerTarget player : players) {
            if (player == null || !player.alive()) continue;
            double distanceSquared = distanceSquared(mob, player);
            if (distanceSquared < nearestDistanceSquared) {
                nearestDistanceSquared = distanceSquared;
                nearest = player;
            }
        }
        return nearest;
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
