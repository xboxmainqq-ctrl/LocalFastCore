package ru.nexora.runtime;

import java.util.Objects;
import java.util.UUID;

/**
 * Persistent-ready survival vitals model exposed for future RPG integrations.
 * Health is stored in half-heart points: 20 points are the default 10 hearts.
 */
public final class PlayerVitals {
    public static final int DEFAULT_MAX_HEALTH = 20;
    public static final int DEFAULT_HUNGER = 20;
    public static final int MAX_HEALTH_LIMIT = 200;
    private static final double HUNGER_REDUCTION_PER_BONUS_HEART = 0.05d;
    private static final double MAX_HUNGER_REDUCTION = 0.75d;

    private final UUID playerId;
    private int maxHealth;
    private int health;
    private int hunger;

    public PlayerVitals(UUID playerId) {
        this(playerId, DEFAULT_MAX_HEALTH, DEFAULT_MAX_HEALTH, DEFAULT_HUNGER);
    }

    public PlayerVitals(UUID playerId, int maxHealth, int health, int hunger) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        if (maxHealth < DEFAULT_MAX_HEALTH || maxHealth > MAX_HEALTH_LIMIT
                || health < 0 || health > maxHealth || hunger < 0 || hunger > DEFAULT_HUNGER) {
            throw new IllegalArgumentException("Invalid player vitals");
        }
        this.maxHealth = maxHealth;
        this.health = health;
        this.hunger = hunger;
    }

    public UUID playerId() { return playerId; }
    public synchronized int maxHealth() { return maxHealth; }
    public synchronized int health() { return health; }
    public synchronized int hunger() { return hunger; }
    public synchronized int maxHearts() { return maxHealth / 2; }
    public synchronized int hearts() { return (health + 1) / 2; }

    /** Adds permanent maximum health for RPG rewards; current health gains the same amount. */
    public synchronized boolean increaseMaxHealth(int points) {
        if (points <= 0 || maxHealth > MAX_HEALTH_LIMIT - points) return false;
        maxHealth += points;
        health = Math.min(maxHealth, health + points);
        return true;
    }

    /** Removes bonus maximum health without ever reducing the player below the vanilla 10 hearts. */
    public synchronized boolean reduceMaxHealth(int points) {
        if (points <= 0 || maxHealth - points < DEFAULT_MAX_HEALTH) return false;
        maxHealth -= points;
        health = Math.min(health, maxHealth);
        return true;
    }

    public synchronized int damage(int points) {
        if (points <= 0) return 0;
        int applied = Math.min(health, points);
        health -= applied;
        return applied;
    }

    public synchronized int heal(int points) {
        if (points <= 0) return 0;
        int applied = Math.min(maxHealth - health, points);
        health += applied;
        return applied;
    }

    public synchronized void setHunger(int value) {
        hunger = Math.max(0, Math.min(DEFAULT_HUNGER, value));
    }

    /** RPG-aware exhaustion multiplier. One bonus heart reduces hunger drain by 5%, capped at 75%. */
    public synchronized double hungerDrainMultiplier() {
        int bonusHearts = Math.max(0, (maxHealth - DEFAULT_MAX_HEALTH) / 2);
        double reduction = Math.min(MAX_HUNGER_REDUCTION,
                bonusHearts * HUNGER_REDUCTION_PER_BONUS_HEART);
        return 1.0d - reduction;
    }

    /** Applies a vanilla-style exhaustion amount after the RPG heart bonus modifier. */
    public synchronized double applyExhaustion(double exhaustion) {
        if (!Double.isFinite(exhaustion) || exhaustion < 0.0d) {
            throw new IllegalArgumentException("Exhaustion must be finite and non-negative");
        }
        return exhaustion * hungerDrainMultiplier();
    }
}
