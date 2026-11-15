package ru.nexora.runtime.rpg;

/** Immutable base attributes used by server-authoritative RPG class definitions. */
public record RpgStats(
        int vitality,
        int strength,
        int agility,
        int intelligence,
        int defense) {
    public RpgStats {
        check(vitality, "vitality");
        check(strength, "strength");
        check(agility, "agility");
        check(intelligence, "intelligence");
        check(defense, "defense");
    }

    private static void check(int value, String name) {
        if (value < 0 || value > 1_000) {
            throw new IllegalArgumentException(name + " must be between 0 and 1000");
        }
    }
}
