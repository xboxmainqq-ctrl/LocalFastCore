package ru.nexora.combat;

import ru.nexora.inventory.ItemStack;

import java.util.Objects;

/**
 * Server-side kinetic damage calculation for spear charge attacks.
 *
 * The caller is responsible for proving that the spear is actively charged and
 * that the target intersects the spear's forward hit volume. Velocities and view
 * direction must come from authoritative server state, never packet damage values.
 */
public final class SpearChargeCombatService {
    public static final double MIN_DAMAGE_CLOSING_SPEED = 4.6;
    public static final double MIN_KNOCKBACK_ATTACKER_SPEED = 5.1;

    private SpearChargeCombatService() {
    }

    public static ChargeEffect resolve(
            ItemStack spear,
            Vector attackerVelocity,
            Vector targetVelocity,
            Vector lookDirection
    ) {
        Objects.requireNonNull(attackerVelocity, "attackerVelocity");
        Objects.requireNonNull(targetVelocity, "targetVelocity");
        Objects.requireNonNull(lookDirection, "lookDirection");
        double multiplier = multiplier(spear);
        if (multiplier == 0.0) return ChargeEffect.NO_HIT;

        double lookLength = lookDirection.length();
        if (lookLength < 1.0e-9) throw new IllegalArgumentException("lookDirection must be non-zero");
        Vector look = lookDirection.scale(1.0 / lookLength);
        Vector relativeVelocity = attackerVelocity.subtract(targetVelocity);
        double closingSpeed = relativeVelocity.dot(look);
        double forwardAttackerSpeed = attackerVelocity.dot(look);

        if (closingSpeed < MIN_DAMAGE_CLOSING_SPEED) {
            return new ChargeEffect(0.0f, false, closingSpeed, forwardAttackerSpeed);
        }
        return new ChargeEffect((float) (closingSpeed * multiplier),
                forwardAttackerSpeed >= MIN_KNOCKBACK_ATTACKER_SPEED,
                closingSpeed, forwardAttackerSpeed);
    }

    public static double multiplier(ItemStack spear) {
        if (spear == null) return 0.0;
        return switch (spear.itemId()) {
            case "minecraft:wooden_spear", "minecraft:golden_spear" -> 0.70;
            case "minecraft:stone_spear", "minecraft:copper_spear" -> 0.82;
            case "minecraft:iron_spear" -> 0.95;
            case "minecraft:diamond_spear" -> 1.075;
            case "minecraft:netherite_spear" -> 1.20;
            default -> 0.0;
        };
    }

    public record Vector(double x, double y, double z) {
        public Vector {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("Vector components must be finite");
            }
        }

        public double dot(Vector other) {
            return x * other.x + y * other.y + z * other.z;
        }

        public double length() {
            return Math.sqrt(x * x + y * y + z * z);
        }

        public Vector scale(double factor) {
            return new Vector(x * factor, y * factor, z * factor);
        }

        public Vector subtract(Vector other) {
            return new Vector(x - other.x, y - other.y, z - other.z);
        }
    }

    public record ChargeEffect(
            float damage,
            boolean knockback,
            double closingSpeed,
            double attackerForwardSpeed
    ) {
        public static final ChargeEffect NO_HIT = new ChargeEffect(0.0f, false, 0.0, 0.0);

        public ChargeEffect {
            if (!Float.isFinite(damage) || damage < 0
                    || !Double.isFinite(closingSpeed) || !Double.isFinite(attackerForwardSpeed)) {
                throw new IllegalArgumentException("Invalid spear charge effect");
            }
        }

        public boolean hit() {
            return damage > 0.0f;
        }
    }
}
