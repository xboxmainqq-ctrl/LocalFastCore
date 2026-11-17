package ru.nexora.combat;

import ru.nexora.world.WorldLivingEntity;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Deterministic, server-authoritative melee AI decisions for supported hostile mobs. */
public final class LivingEntityAiService {
    public static final double ACQUIRE_RANGE = 16.0;
    public static final double ATTACK_RANGE = 1.8;
    public static final double WALK_SPEED_PER_TICK = 0.23;
    public static final long ATTACK_COOLDOWN_MILLIS = 1_000;
    public static final float BASE_ATTACK_DAMAGE = 3.0f;

    public Decision decide(WorldLivingEntity mob, List<PlayerTarget> players, long nowMillis,
                           long nextAttackAtMillis) {
        Objects.requireNonNull(mob, "mob");
        Objects.requireNonNull(players, "players");
        if (nowMillis < 0 || nextAttackAtMillis < 0) {
            throw new IllegalArgumentException("AI timestamps must be non-negative");
        }
        if (!isSupportedHostile(mob.identifier()) || mob.health() <= 0) return Decision.idle(mob.runtimeId());

        PlayerTarget target = players.stream()
                .filter(Objects::nonNull)
                .filter(PlayerTarget::alive)
                .filter(player -> distanceSquared(mob, player) <= ACQUIRE_RANGE * ACQUIRE_RANGE)
                .min(Comparator.comparingDouble(player -> distanceSquared(mob, player)))
                .orElse(null);
        if (target == null) return Decision.idle(mob.runtimeId());

        double dx = target.x() - mob.x();
        double dy = target.y() - mob.y();
        double dz = target.z() - mob.z();
        double horizontal = Math.hypot(dx, dz);
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        if (distanceSquared <= ATTACK_RANGE * ATTACK_RANGE) {
            if (nowMillis >= nextAttackAtMillis) {
                return new Decision(mob.runtimeId(), target.networkId(), 0, 0, 0,
                        attackDamageFor(mob.identifier()), true);
            }
            return Decision.idle(mob.runtimeId());
        }
        if (horizontal < 0.0001) return Decision.idle(mob.runtimeId());
        double step = Math.min(WALK_SPEED_PER_TICK, Math.max(0, horizontal - 1.0));
        double moveX = dx / horizontal * step;
        double moveZ = dz / horizontal * step;
        return new Decision(mob.runtimeId(), target.networkId(), moveX, 0, moveZ, 0, false);
    }

    public static float attackDamageFor(String identifier) {
        return switch (identifier) {
            case "minecraft:silverfish" -> 1.0f;
            case "minecraft:spider", "minecraft:cave_spider", "minecraft:endermite" -> 2.0f;
            case "minecraft:zombified_piglin" -> 5.0f;
            case "minecraft:zoglin" -> 6.0f;
            default -> BASE_ATTACK_DAMAGE;
        };
    }

    public static boolean isSupportedHostile(String identifier) {
        return switch (identifier) {
            case "minecraft:zombie", "minecraft:husk", "minecraft:drowned",
                    "minecraft:zombified_piglin", "minecraft:zoglin",
                    "minecraft:spider", "minecraft:cave_spider",
                    "minecraft:silverfish", "minecraft:endermite" -> true;
            default -> false;
        };
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
