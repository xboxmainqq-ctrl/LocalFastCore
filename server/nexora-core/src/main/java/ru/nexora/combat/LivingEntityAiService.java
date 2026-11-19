package ru.nexora.combat;

import ru.nexora.world.WorldLivingEntity;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic server-authoritative behavior for living mobs.
 * Hostile mobs pursue players; non-hostile mobs flee nearby players and otherwise wander.
 */
public final class LivingEntityAiService {
    public static final double ACQUIRE_RANGE = 16.0;
    public static final double ATTACK_RANGE = 1.8;
    public static final double WALK_SPEED_PER_TICK = 0.23;
    public static final double FLEE_RANGE = 6.0;
    public static final double RANGED_RETREAT_DISTANCE = 4.0;
    public static final long ATTACK_COOLDOWN_MILLIS = 1_000;
    public static final long WANDER_INTERVAL_MILLIS = 3_000;
    public static final float BASE_ATTACK_DAMAGE = 3.0f;

    private final LongAdder decisionCount = new LongAdder();
    private final LongAdder decisionNanos = new LongAdder();
    private final LongAdder decisionFailures = new LongAdder();
    private final AtomicLong lastDecisionNanos = new AtomicLong();
    private final AtomicLong maxDecisionNanos = new AtomicLong();

    public Decision decide(WorldLivingEntity mob, List<PlayerTarget> players, long nowMillis,
                           long nextAttackAtMillis) {
        return decide(mob, players, nowMillis, nextAttackAtMillis, null);
    }

    public Decision decide(WorldLivingEntity mob, List<PlayerTarget> players, long nowMillis,
                           long nextAttackAtMillis, String provokedByNetworkId) {
        return decide(mob, players, nowMillis, nextAttackAtMillis, provokedByNetworkId,
                nearestAlivePlayer(mob, players));
    }

    /**
     * Reuses the caller's nearest-player scan so the scheduler and behavior decision
     * do not each walk the same player list for every mob tick.
     */
    public Decision decide(WorldLivingEntity mob, List<PlayerTarget> players, long nowMillis,
                           long nextAttackAtMillis, String provokedByNetworkId,
                           PlayerTarget nearest) {
        long started = System.nanoTime();
        try {
            return decideInternal(mob, players, nowMillis, nextAttackAtMillis,
                    provokedByNetworkId, nearest);
        } catch (RuntimeException | Error failure) {
            decisionFailures.increment();
            throw failure;
        } finally {
            long elapsed = Math.max(0L, System.nanoTime() - started);
            decisionCount.increment();
            decisionNanos.add(elapsed);
            maxDecisionNanos.accumulateAndGet(elapsed, Math::max);
            lastDecisionNanos.set(elapsed);
        }
    }

    private Decision decideInternal(WorldLivingEntity mob, List<PlayerTarget> players, long nowMillis,
                                    long nextAttackAtMillis, String provokedByNetworkId,
                                    PlayerTarget nearest) {
        Objects.requireNonNull(mob, "mob");
        Objects.requireNonNull(players, "players");
        if (nowMillis < 0 || nextAttackAtMillis < 0) {
            throw new IllegalArgumentException("AI timestamps must be non-negative");
        }
        if (nearest != null && !nearest.alive()) {
            throw new IllegalArgumentException("nearest player must be alive");
        }
        if (mob.health() <= 0) return Decision.idle(mob.runtimeId());

        if (isHostile(mob.identifier())) {
            double projectileRange = projectileRangeFor(mob.identifier());
            // Ranged hostiles acquire a target before it enters firing distance, then close the gap.
            double acquireRange = projectileRange > 0
                    ? Math.max(ACQUIRE_RANGE, projectileRange + 8.0) : ACQUIRE_RANGE;
            if (nearest == null || distanceSquared(mob, nearest) > acquireRange * acquireRange) {
                return Decision.idle(mob.runtimeId());
            }
            return pursue(mob, nearest, nowMillis, nextAttackAtMillis);
        }

        PlayerTarget provoker = findPlayer(players, provokedByNetworkId);
        if (isPassivePrey(mob.identifier())) {
            if (provoker != null
                    && distanceSquared(mob, provoker) <= ACQUIRE_RANGE * ACQUIRE_RANGE) {
                return flee(mob, provoker);
            }
            if (isTimidMob(mob.identifier()) && nearest != null
                    && distanceSquared(mob, nearest) <= FLEE_RANGE * FLEE_RANGE) {
                return flee(mob, nearest);
            }
            return wander(mob, nowMillis);
        }

        if (provoker != null
                && distanceSquared(mob, provoker) <= ACQUIRE_RANGE * ACQUIRE_RANGE) {
            return pursue(mob, provoker, nowMillis, nextAttackAtMillis);
        }
        return wander(mob, nowMillis);
    }

    /** Thread-safe aggregate timings for the actual decision algorithm, excluding wrapper scans. */
    public Stats stats() {
        long count = decisionCount.sum();
        long total = decisionNanos.sum();
        return new Stats(count, total, lastDecisionNanos.get(), maxDecisionNanos.get(),
                decisionFailures.sum(), count == 0 ? 0 : total / count);
    }

    public record Stats(long decisionCount, long totalDecisionNanos, long lastDecisionNanos,
                        long maxDecisionNanos, long failures, long averageDecisionNanos) {
        public Stats {
            if (decisionCount < 0 || totalDecisionNanos < 0 || lastDecisionNanos < 0
                    || failures < 0 || averageDecisionNanos < 0) {
                throw new IllegalArgumentException("Invalid living entity AI stats");
            }
        }
    }

    private static PlayerTarget findPlayer(List<PlayerTarget> players, String networkId) {
        if (networkId == null || networkId.isBlank()) return null;
        for (PlayerTarget player : players) {
            if (player != null && player.alive() && player.networkId().equals(networkId)) {
                return player;
            }
        }
        return null;
    }

    /** Species that naturally avoid nearby players even without being attacked. */
    public static boolean isTimidMob(String identifier) {
        return switch (identifier) {
            case "minecraft:bat", "minecraft:fox", "minecraft:ocelot", "minecraft:rabbit" -> true;
            default -> false;
        };
    }

    private Decision pursue(WorldLivingEntity mob, PlayerTarget target, long nowMillis,
                            long nextAttackAtMillis) {
        double dx = target.x() - mob.x();
        double dy = target.y() - mob.y();
        double dz = target.z() - mob.z();
        double horizontal = Math.hypot(dx, dz);
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        AttackStyle attackStyle = attackStyleFor(mob.identifier());
        double attackRange = attackStyle == AttackStyle.PROJECTILE
                ? projectileRangeFor(mob.identifier()) : attackRangeFor(mob.identifier());
        // Ranged species must create space even when their cooldown is ready.
        // Otherwise a close target can be misinterpreted as a projectile attack.
        if (attackStyle == AttackStyle.PROJECTILE
                && horizontal < RANGED_RETREAT_DISTANCE) {
            return flee(mob, target);
        }
        if (distanceSquared <= attackRange * attackRange) {
            if (nowMillis >= nextAttackAtMillis) {
                return new Decision(mob.runtimeId(), target.networkId(), 0, 0, 0,
                        attackDamageFor(mob.identifier()), true, attackStyle);
            }
            // Ranged mobs create space while reloading instead of standing still
            // with an opponent directly on top of them.
            if (attackStyle == AttackStyle.PROJECTILE
                    && horizontal < RANGED_RETREAT_DISTANCE) {
                return flee(mob, target);
            }
            return Decision.idle(mob.runtimeId());
        }
        double speed = movementSpeedFor(mob.identifier());
        if (isFlyingMob(mob.identifier())) {
            double distance = Math.sqrt(distanceSquared);
            if (distance < 0.0001) return Decision.idle(mob.runtimeId());
            double step = Math.min(speed, Math.max(0, distance - 1.0));
            return new Decision(mob.runtimeId(), target.networkId(),
                    dx / distance * step, dy / distance * step, dz / distance * step, 0, false);
        }
        if (horizontal < 0.0001) return Decision.idle(mob.runtimeId());
        double step = Math.min(speed, Math.max(0, horizontal - 1.0));
        return new Decision(mob.runtimeId(), target.networkId(),
                dx / horizontal * step, 0, dz / horizontal * step, 0, false);
    }

    /**
     * Produces a collision-checked movement intent toward a ranged target when a wall
     * blocks the shot. The world adapter still validates chunks, collisions and pathing.
     */
    public Decision approach(WorldLivingEntity mob, PlayerTarget target) {
        Objects.requireNonNull(mob, "mob");
        Objects.requireNonNull(target, "target");
        if (!target.alive()) throw new IllegalArgumentException("target must be alive");
        if (mob.health() <= 0) return Decision.idle(mob.runtimeId());

        double dx = target.x() - mob.x();
        double dy = target.y() - mob.y();
        double dz = target.z() - mob.z();
        // A blocked shot must not make a ranged mob walk into an opponent at point-blank range.
        if (attackStyleFor(mob.identifier()) == AttackStyle.PROJECTILE
                && Math.hypot(dx, dz) < RANGED_RETREAT_DISTANCE) {
            return flee(mob, target);
        }
        double speed = movementSpeedFor(mob.identifier());
        if (isFlyingMob(mob.identifier())) {
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance < 0.0001) return Decision.idle(mob.runtimeId());
            double step = Math.min(speed, Math.max(0, distance - 1.0));
            return new Decision(mob.runtimeId(), target.networkId(),
                    dx / distance * step, dy / distance * step, dz / distance * step,
                    0, false, attackStyleFor(mob.identifier()));
        }

        double horizontal = Math.hypot(dx, dz);
        if (horizontal < 0.0001) return Decision.idle(mob.runtimeId());
        double step = Math.min(speed, Math.max(0, horizontal - 1.0));
        return new Decision(mob.runtimeId(), target.networkId(),
                dx / horizontal * step, 0, dz / horizontal * step,
                0, false, attackStyleFor(mob.identifier()));
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
        double speed = movementSpeedFor(mob.identifier());
        if (isFlyingMob(mob.identifier())) {
            double vertical = mob.y() - threat.y();
            double distance = Math.sqrt(dx * dx + vertical * vertical + dz * dz);
            if (distance < 0.0001) return Decision.idle(mob.runtimeId());
            return new Decision(mob.runtimeId(), threat.networkId(),
                    dx / distance * speed, vertical / distance * speed,
                    dz / distance * speed, 0, false, attackStyleFor(mob.identifier()));
        }
        return new Decision(mob.runtimeId(), threat.networkId(),
                dx / length * speed, 0, dz / length * speed, 0, false,
                attackStyleFor(mob.identifier()));
    }

    private Decision wander(WorldLivingEntity mob, long nowMillis) {
        long interval = nowMillis / WANDER_INTERVAL_MILLIS;
        long seed = mob.runtimeId() * 31L + interval * 0x9E3779B97F4A7C15L;
        double angle = stableAngle(seed);
        double speed = movementSpeedFor(mob.identifier()) * 0.55;
        double vertical = isFlyingMob(mob.identifier())
                ? Math.sin(stableAngle(seed ^ 0x5DEECE66DL)) * speed * 0.35 : 0;
        return new Decision(mob.runtimeId(), null,
                Math.cos(angle) * speed, vertical,
                Math.sin(angle) * speed, 0, false);
    }

    private static double stableAngle(long seed) {
        long mixed = seed ^ (seed >>> 33);
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        return (mixed & 0x1fffffffffffffL) / (double) 0x20000000000000L * Math.PI * 2.0;
    }

    /** Selects the combat delivery mode; this is an intent for the world adapter to execute. */
    public static AttackStyle attackStyleFor(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        return switch (identifier) {
            case "minecraft:skeleton", "minecraft:stray", "minecraft:bogged",
                    "minecraft:pillager", "minecraft:blaze", "minecraft:ghast",
                    "minecraft:guardian", "minecraft:elder_guardian" -> AttackStyle.PROJECTILE;
            default -> AttackStyle.MELEE;
        };
    }

    /** Effective projectile engagement range in blocks, before line-of-sight checks by the world adapter. */
    public static double projectileRangeFor(String identifier) {
        return switch (identifier) {
            case "minecraft:ghast" -> 16.0;
            case "minecraft:guardian", "minecraft:elder_guardian", "minecraft:blaze" -> 14.0;
            case "minecraft:skeleton", "minecraft:stray", "minecraft:bogged",
                    "minecraft:pillager" -> 12.0;
            default -> 0.0;
        };
    }

    /** Species-specific melee reach, measured in blocks. */
    public static double attackRangeFor(String identifier) {
        return switch (identifier) {
            case "minecraft:enderman" -> 3.0;
            case "minecraft:iron_golem", "minecraft:ravager", "minecraft:warden",
                    "minecraft:zoglin", "minecraft:hoglin" -> 2.5;
            default -> ATTACK_RANGE;
        };
    }

    /** Vanilla species with free 3D movement rather than ground-bound navigation. */
    public static boolean isFlyingMob(String identifier) {
        return switch (identifier) {
            case "minecraft:allay", "minecraft:bat", "minecraft:bee", "minecraft:blaze",
                    "minecraft:ghast", "minecraft:phantom", "minecraft:vex",
                    "minecraft:parrot", "minecraft:ender_dragon", "minecraft:wither" -> true;
            default -> false;
        };
    }

    /** Species-specific horizontal movement speed in blocks per AI update. */
    public static double movementSpeedFor(String identifier) {
        return switch (identifier) {
            case "minecraft:phantom", "minecraft:vex", "minecraft:ender_dragon" -> 0.30;
            case "minecraft:wither" -> 0.20;
            case "minecraft:enderman", "minecraft:horse", "minecraft:donkey",
                    "minecraft:mule", "minecraft:camel" -> 0.30;
            case "minecraft:allay", "minecraft:bee", "minecraft:blaze",
                    "minecraft:parrot", "minecraft:bat" -> 0.20;
            case "minecraft:ghast" -> 0.12;
            case "minecraft:spider", "minecraft:cave_spider", "minecraft:fox",
                    "minecraft:ocelot", "minecraft:cat" -> 0.27;
            case "minecraft:iron_golem", "minecraft:ravager", "minecraft:turtle" -> 0.16;
            case "minecraft:slime", "minecraft:magma_cube" -> 0.20;
            default -> WALK_SPEED_PER_TICK;
        };
    }

    /**
     * Species-specific ranged cadence. These are server-side recovery windows between
     * projectile launches; they are intentionally independent from melee recovery.
     */
    public static long rangedAttackCooldownMillisFor(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        return switch (identifier) {
            case "minecraft:skeleton", "minecraft:stray", "minecraft:bogged",
                    "minecraft:pillager" -> 1_250L;
            case "minecraft:guardian", "minecraft:elder_guardian" -> 1_500L;
            case "minecraft:ghast" -> 3_000L;
            case "minecraft:blaze" -> 1_000L;
            default -> ATTACK_COOLDOWN_MILLIS;
        };
    }

    /**
     * Species-specific melee cadence. Values are milliseconds between successful attacks.
     * Keep the common baseline stable; slower heavy hitters receive longer recovery windows.
     */
    public static long attackCooldownMillisFor(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        return switch (identifier) {
            case "minecraft:bee", "minecraft:spider", "minecraft:cave_spider" -> 1_250L;
            case "minecraft:ravager", "minecraft:hoglin", "minecraft:zoglin" -> 1_500L;
            case "minecraft:warden", "minecraft:iron_golem", "minecraft:ender_dragon",
                    "minecraft:wither" -> 1_750L;
            default -> ATTACK_COOLDOWN_MILLIS;
        };
    }

    public static float attackDamageFor(String identifier) {
        return switch (identifier) {
            case "minecraft:silverfish" -> 1.0f;
            case "minecraft:spider", "minecraft:cave_spider", "minecraft:endermite",
                    "minecraft:bee" -> 2.0f;
            case "minecraft:zombified_piglin", "minecraft:piglin", "minecraft:blaze",
                    "minecraft:guardian", "minecraft:wolf" -> 5.0f;
            case "minecraft:zoglin", "minecraft:ravager", "minecraft:warden",
                    "minecraft:hoglin", "minecraft:polar_bear", "minecraft:piglin_brute" -> 6.0f;
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
                    "minecraft:warden", "minecraft:breeze", "minecraft:vex",
                    "minecraft:wither", "minecraft:bogged" -> true;
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

    public enum AttackStyle {
        MELEE,
        PROJECTILE
    }

    public record Decision(long mobRuntimeId, String targetNetworkId,
                           double moveX, double moveY, double moveZ,
                           float attackDamage, boolean attack, AttackStyle attackStyle) {
        public Decision(long mobRuntimeId, String targetNetworkId,
                        double moveX, double moveY, double moveZ,
                        float attackDamage, boolean attack) {
            this(mobRuntimeId, targetNetworkId, moveX, moveY, moveZ,
                    attackDamage, attack, AttackStyle.MELEE);
        }

        public Decision {
            Objects.requireNonNull(attackStyle, "attackStyle");
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
