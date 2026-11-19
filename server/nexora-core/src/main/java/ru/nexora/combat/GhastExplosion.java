package ru.nexora.combat;

import ru.nexora.world.BlockType;

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

    public static boolean isInsideBlockBlast(double dx, double dy, double dz) {
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)) return false;
        return dx * dx + dy * dy + dz * dz <= BLOCK_RADIUS * BLOCK_RADIUS;
    }
}
