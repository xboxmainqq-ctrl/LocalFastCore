package ru.nexora.world;

/** Immutable survival state for a player: health, hunger, saturation and exhaustion. */
public record PlayerVitals(float health, int hunger, float saturation, float exhaustion) {
    public static final float MAX_HEALTH = 20.0f;
    public static final int MAX_HUNGER = 20;
    public static final float MAX_SATURATION = 20.0f;
    public static final float MAX_EXHAUSTION = 4.0f;
    public static final PlayerVitals DEFAULT = new PlayerVitals(20.0f, 20, 5.0f, 0.0f);

    public PlayerVitals {
        if (!Float.isFinite(health) || health < 0.0f || health > MAX_HEALTH
                || hunger < 0 || hunger > MAX_HUNGER
                || !Float.isFinite(saturation) || saturation < 0.0f
                || saturation > MAX_SATURATION || saturation > hunger
                || !Float.isFinite(exhaustion) || exhaustion < 0.0f
                || exhaustion >= MAX_EXHAUSTION) {
            throw new IllegalArgumentException("Player vitals are outside supported survival bounds");
        }
    }

    public boolean dead() {
        return health <= 0.0f;
    }

    public PlayerVitals damage(float amount) {
        requirePositiveFinite(amount, "damage");
        return new PlayerVitals(Math.max(0.0f, health - amount), hunger, saturation, exhaustion);
    }

    public PlayerVitals heal(float amount) {
        requirePositiveFinite(amount, "healing");
        return new PlayerVitals(Math.min(MAX_HEALTH, health + amount), hunger, saturation, exhaustion);
    }

    /** Applies Minecraft-style exhaustion conversion: saturation first, then hunger. */
    public PlayerVitals addExhaustion(float amount) {
        if (!Float.isFinite(amount) || amount < 0.0f || amount > 1_000_000.0f) {
            throw new IllegalArgumentException("Invalid exhaustion amount");
        }
        float nextExhaustion = exhaustion + amount;
        int nextHunger = hunger;
        float nextSaturation = saturation;
        while (nextExhaustion >= MAX_EXHAUSTION) {
            nextExhaustion -= MAX_EXHAUSTION;
            if (nextSaturation > 0.0f) {
                nextSaturation = Math.max(0.0f, nextSaturation - 1.0f);
            } else if (nextHunger > 0) {
                nextHunger--;
            }
        }
        return new PlayerVitals(health, nextHunger, Math.min(nextSaturation, nextHunger), nextExhaustion);
    }

    public PlayerVitals feed(int foodPoints, float saturationModifier) {
        if (foodPoints < 1 || foodPoints > MAX_HUNGER
                || !Float.isFinite(saturationModifier)
                || saturationModifier < 0.0f || saturationModifier > 2.0f) {
            throw new IllegalArgumentException("Invalid food values");
        }
        int nextHunger = Math.min(MAX_HUNGER, hunger + foodPoints);
        float nextSaturation = Math.min(nextHunger,
                Math.min(MAX_SATURATION, saturation + foodPoints * saturationModifier * 2.0f));
        return new PlayerVitals(health, nextHunger, nextSaturation, exhaustion);
    }

    private static void requirePositiveFinite(float amount, String field) {
        if (!Float.isFinite(amount) || amount <= 0.0f) {
            throw new IllegalArgumentException(field + " amount must be finite and positive");
        }
    }
}
