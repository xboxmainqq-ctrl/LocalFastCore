package ru.nexora.combat;

import ru.nexora.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Deterministic loot and XP table for supported hostile and passive mob families. */
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
                    "minecraft:zombified_piglin" ->
                    new Loot(List.of(new ItemStack("minecraft:rotten_flesh", 1, 64)), 5_000);
            case "minecraft:zoglin" ->
                    new Loot(List.of(new ItemStack("minecraft:rotten_flesh", 1, 64)), 5_000);
            case "minecraft:spider", "minecraft:cave_spider" ->
                    new Loot(List.of(new ItemStack("minecraft:string", 1, 64)), 5_000);
            case "minecraft:skeleton", "minecraft:stray", "minecraft:bogged" ->
                    new Loot(List.of(new ItemStack("minecraft:bone", 1, 64),
                            new ItemStack("minecraft:arrow", 1, 64)), 5_000);
            case "minecraft:wither_skeleton" ->
                    new Loot(List.of(new ItemStack("minecraft:bone", 1, 64),
                            new ItemStack("minecraft:coal", 1, 64)), 5_000);
            case "minecraft:creeper" ->
                    new Loot(List.of(new ItemStack("minecraft:gunpowder", 1, 64)), 5_000);
            case "minecraft:enderman" ->
                    new Loot(List.of(new ItemStack("minecraft:ender_pearl", 1, 16)), 5_000);
            case "minecraft:blaze" ->
                    new Loot(List.of(new ItemStack("minecraft:blaze_rod", 1, 64)), 5_000);
            case "minecraft:ghast" ->
                    new Loot(List.of(new ItemStack("minecraft:ghast_tear", 1, 64)), 5_000);
            case "minecraft:wither" ->
                    new Loot(List.of(new ItemStack("minecraft:nether_star", 1, 1)), 50_000);
            case "minecraft:breeze" ->
                    new Loot(List.of(new ItemStack("minecraft:breeze_rod", 1, 64)), 5_000);
            case "minecraft:witch" ->
                    new Loot(List.of(new ItemStack("minecraft:redstone", 1, 64)), 5_000);
            case "minecraft:slime" ->
                    new Loot(List.of(new ItemStack("minecraft:slime_ball", 1, 64)), 1_000);
            case "minecraft:magma_cube" ->
                    new Loot(List.of(new ItemStack("minecraft:magma_cream", 1, 64)), 1_000);
            case "minecraft:guardian" ->
                    new Loot(List.of(new ItemStack("minecraft:prismarine_shard", 1, 64),
                            new ItemStack("minecraft:cod", 1, 64)), 5_000);
            case "minecraft:elder_guardian" ->
                    new Loot(List.of(new ItemStack("minecraft:prismarine_shard", 1, 64),
                            new ItemStack("minecraft:wet_sponge", 1, 1)), 10_000);
            case "minecraft:shulker" ->
                    new Loot(List.of(new ItemStack("minecraft:shulker_shell", 1, 64)), 5_000);
            case "minecraft:phantom" ->
                    new Loot(List.of(new ItemStack("minecraft:phantom_membrane", 1, 64)), 5_000);
            case "minecraft:evoker" ->
                    new Loot(List.of(new ItemStack("minecraft:totem_of_undying", 1, 1)), 10_000);
            case "minecraft:ender_dragon" -> new Loot(List.of(), 100_000);
            case "minecraft:hoglin" ->
                    new Loot(List.of(new ItemStack("minecraft:porkchop", 1, 64)), 5_000);
            case "minecraft:strider" ->
                    new Loot(List.of(new ItemStack("minecraft:string", 1, 64)), 3_000);
            case "minecraft:camel", "minecraft:donkey", "minecraft:horse",
                    "minecraft:mule", "minecraft:llama", "minecraft:trader_llama" ->
                    new Loot(List.of(new ItemStack("minecraft:leather", 1, 64)), 3_000);
            case "minecraft:vindicator" ->
                    new Loot(List.of(new ItemStack("minecraft:emerald", 1, 64)), 5_000);
            case "minecraft:warden" ->
                    new Loot(List.of(new ItemStack("minecraft:sculk_catalyst", 1, 1)), 5_000);
            case "minecraft:panda" ->
                    new Loot(List.of(new ItemStack("minecraft:bamboo", 1, 64)), 3_000);
            case "minecraft:piglin", "minecraft:piglin_brute" ->
                    new Loot(List.of(new ItemStack("minecraft:gold_nugget", 1, 64)), 5_000);
            case "minecraft:iron_golem" ->
                    new Loot(List.of(new ItemStack("minecraft:iron_ingot", 1, 64),
                            new ItemStack("minecraft:poppy", 1, 64)), 0);
            case "minecraft:cow" ->
                    new Loot(List.of(new ItemStack("minecraft:beef", 1, 64),
                            new ItemStack("minecraft:leather", 1, 64)), 3_000);
            case "minecraft:sheep" ->
                    new Loot(List.of(new ItemStack("minecraft:mutton", 1, 64)), 3_000);
            case "minecraft:pig" ->
                    new Loot(List.of(new ItemStack("minecraft:porkchop", 1, 64)), 3_000);
            case "minecraft:chicken" ->
                    new Loot(List.of(new ItemStack("minecraft:feather", 1, 64),
                            new ItemStack("minecraft:chicken", 1, 64)), 3_000);
            case "minecraft:rabbit" ->
                    new Loot(List.of(new ItemStack("minecraft:rabbit", 1, 64),
                            new ItemStack("minecraft:rabbit_hide", 1, 64)), 3_000);
            case "minecraft:bee" -> new Loot(List.of(), 3_000);
            case "minecraft:snow_golem" ->
                    new Loot(List.of(new ItemStack("minecraft:snowball", 1, 16)), 0);
            case "minecraft:cod" ->
                    new Loot(List.of(new ItemStack("minecraft:cod", 1, 64)), 1_000);
            case "minecraft:salmon" ->
                    new Loot(List.of(new ItemStack("minecraft:salmon", 1, 64)), 1_000);
            case "minecraft:tropical_fish" ->
                    new Loot(List.of(new ItemStack("minecraft:tropical_fish", 1, 64)), 1_000);
            case "minecraft:pufferfish" ->
                    new Loot(List.of(new ItemStack("minecraft:pufferfish", 1, 64)), 1_000);
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
