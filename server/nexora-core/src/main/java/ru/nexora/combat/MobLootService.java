package ru.nexora.combat;

import ru.nexora.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Small deterministic vanilla-style loot and XP table for the currently supported hostile mobs. */
public final class MobLootService {
    private MobLootService() {
    }

    public static Loot dropsFor(String identifier) {
        return dropsFor(identifier, 0, 0);
    }

    /** Applies one bounded Looting bonus roll to the first eligible stack. */
    public static Loot dropsFor(String identifier, int lootingLevel, int bonusRoll) {
        if (lootingLevel < 0 || lootingLevel > 3 || bonusRoll < 0 || bonusRoll > lootingLevel) {
            throw new IllegalArgumentException("Looting level or bonus roll is outside supported bounds");
        }
        Loot base = switch (identifier) {
            case "minecraft:zombie", "minecraft:husk", "minecraft:drowned",
                    "minecraft:zombified_piglin", "minecraft:zoglin" ->
                    new Loot(List.of(new ItemStack("minecraft:rotten_flesh", 1, 64)), 5_000);
            case "minecraft:spider", "minecraft:cave_spider" ->
                    new Loot(List.of(), 5_000);
            case "minecraft:skeleton" ->
                    new Loot(List.of(new ItemStack("minecraft:bone", 1, 64)), 5_000);
            case "minecraft:silverfish" -> new Loot(List.of(), 5_000);
            case "minecraft:endermite" -> new Loot(List.of(), 3_000);
            default -> Loot.EMPTY;
        };
        if (bonusRoll == 0 || base.drops().isEmpty()) return base;
        List<ItemStack> adjusted = new ArrayList<>(base.drops());
        ItemStack first = adjusted.getFirst();
        adjusted.set(0, first.withCount(Math.min(first.maxStackSize(), first.count() + bonusRoll)));
        return new Loot(adjusted, base.experienceMilliPoints());
    }

    public record Loot(List<ItemStack> drops, int experienceMilliPoints) {
        public static final Loot EMPTY = new Loot(List.of(), 0);

        public Loot {
            drops = List.copyOf(drops);
            if (experienceMilliPoints < 0 || experienceMilliPoints > 100_000) {
                throw new IllegalArgumentException("Mob XP reward is outside supported bounds");
            }
        }
    }
}
