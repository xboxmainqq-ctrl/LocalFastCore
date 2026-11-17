package ru.nexora.combat;

import ru.nexora.inventory.ItemStack;

import java.util.List;

/** Small deterministic vanilla-style loot and XP table for the currently supported hostile mobs. */
public final class MobLootService {
    private MobLootService() {
    }

    public static Loot dropsFor(String identifier) {
        return switch (identifier) {
            case "minecraft:zombie", "minecraft:husk", "minecraft:drowned",
                    "minecraft:zombified_piglin", "minecraft:zoglin" ->
                    new Loot(List.of(new ItemStack("minecraft:rotten_flesh", 1, 0)), 5_000);
            case "minecraft:spider", "minecraft:cave_spider" ->
                    new Loot(List.of(new ItemStack("minecraft:string", 1, 0)), 5_000);
            case "minecraft:skeleton" ->
                    new Loot(List.of(new ItemStack("minecraft:bone", 1, 0),
                            new ItemStack("minecraft:arrow", 1, 0)), 5_000);
            case "minecraft:silverfish" -> new Loot(List.of(), 5_000);
            case "minecraft:endermite" -> new Loot(List.of(), 3_000);
            default -> Loot.EMPTY;
        };
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
