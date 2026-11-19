package ru.nexora.combat;

import ru.nexora.world.BlockType;
import ru.nexora.inventory.ItemStack;

/** Bounded ghast blast rules shared by the live combat path and deterministic tests. */
public final class GhastExplosion {
    public static final double BLOCK_RADIUS = 2.5;
    public static final double PLAYER_RADIUS = 4.0;
    public static final float MAX_PLAYER_DAMAGE = 8.0f;
    public static final int FIRE_DURATION_SECONDS = 5;

    private GhastExplosion() {
    }

    public static float damageAtDistance(double distance) {
        if (!Double.isFinite(distance) || distance < 0.0) {
            throw new IllegalArgumentException("Explosion distance must be finite and non-negative");
        }
        if (distance >= PLAYER_RADIUS) return 0.0f;
        return (float) (MAX_PLAYER_DAMAGE * (1.0 - distance / PLAYER_RADIUS));
    }

    /** Protects valuable/container and blast-immune blocks until drops and block rules exist. */
    public static boolean canDestroy(BlockType block) {
        if (block == null) throw new NullPointerException("block");
        return switch (block) {
            case STONE, DIRT, GRASS_BLOCK, COBBLESTONE, GRAVEL, GLASS,
                    NETHERRACK, END_STONE, COAL_BLOCK, IRON_BLOCK, GOLD_BLOCK,
                    DIAMOND_BLOCK, EMERALD_BLOCK, OAK_LOG, OAK_PLANKS -> true;
            default -> false;
        };
    }

    /**
     * Conservative vanilla-style drops for the currently supported block subset.
     * Glass intentionally has no drop without Silk Touch; unsupported blocks return null.
     */
    public static ItemStack dropFor(BlockType block) {
        if (block == null) throw new NullPointerException("block");
        String itemId = switch (block) {
            case STONE -> "minecraft:cobblestone";
            case DIRT, GRASS_BLOCK -> "minecraft:dirt";
            case COBBLESTONE -> "minecraft:cobblestone";
            case GRAVEL -> "minecraft:gravel";
            case NETHERRACK -> "minecraft:netherrack";
            case END_STONE -> "minecraft:end_stone";
            case COAL_BLOCK -> "minecraft:coal_block";
            case IRON_BLOCK -> "minecraft:iron_block";
            case GOLD_BLOCK -> "minecraft:gold_block";
            case DIAMOND_BLOCK -> "minecraft:diamond_block";
            case EMERALD_BLOCK -> "minecraft:emerald_block";
            case OAK_LOG -> "minecraft:oak_log";
            case OAK_PLANKS -> "minecraft:oak_planks";
            case GLASS -> null;
            default -> null;
        };
        return itemId == null ? null : new ItemStack(itemId, 1, 64);
    }

    /** Outward impulse for entities inside the player blast radius. */
    public static Knockback knockbackAt(double dx, double dy, double dz) {
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)) return null;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance >= PLAYER_RADIUS) return null;
        double horizontal = Math.hypot(dx, dz);
        double strength = 1.15 * (1.0 - distance / PLAYER_RADIUS);
        double vx = horizontal < 1.0e-6 ? 0.0 : dx / horizontal * strength;
        double vz = horizontal < 1.0e-6 ? 0.0 : dz / horizontal * strength;
        double vy = 0.28 + strength * 0.38;
        return new Knockback(vx, vy, vz);
    }

    public record Knockback(double velocityX, double velocityY, double velocityZ) {
    }

    public static boolean isInsideBlockBlast(double dx, double dy, double dz) {
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)) return false;
        return dx * dx + dy * dy + dz * dz <= BLOCK_RADIUS * BLOCK_RADIUS;
    }
}
