package ru.nexora.inventory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Server-authoritative furnace processing engine.
 *
 * Slot layout is input (0), fuel (1), output (2). The caller owns persistence
 * and invokes tick with elapsed server ticks for each loaded furnace.
 */
public final class FurnaceService {
    public static final int INPUT_SLOT = 0;
    public static final int FUEL_SLOT = 1;
    public static final int OUTPUT_SLOT = 2;
    public static final int COOK_TIME_TICKS = 200;

    private static final Map<String, SmeltingRecipe> RECIPES = recipes();
    private static final Map<String, Integer> FUELS = Map.of(
            "minecraft:blaze_rod", 2400,
            "minecraft:coal", 1600,
            "minecraft:stick", 100
    );
    private final Map<Position, FurnaceState> states = new HashMap<>();

    public synchronized TickResult tick(
            int x, int y, int z, ContainerInventory inventory, long elapsedTicks
    ) {
        Objects.requireNonNull(inventory, "inventory");
        if (inventory.size() != 3) throw new IllegalArgumentException("Furnace inventory must have exactly 3 slots");
        if (elapsedTicks < 0 || elapsedTicks > 20_000) {
            throw new IllegalArgumentException("Elapsed furnace ticks must be 0..20000");
        }
        Position position = new Position(x, y, z);
        FurnaceState state = states.computeIfAbsent(position, ignored -> new FurnaceState());
        if (elapsedTicks == 0) return result(state, false);

        boolean changed = false;
        long remainingTicks = elapsedTicks;
        while (remainingTicks > 0) {
            ItemStack input = inventory.getSlot(INPUT_SLOT).orElse(null);
            SmeltingRecipe recipe = input == null ? null : RECIPES.get(input.itemId());
            boolean canSmelt = recipe != null && canAcceptOutput(inventory, recipe.result());

            if (state.burnTicksRemaining == 0 && canSmelt) {
                ItemStack fuel = inventory.getSlot(FUEL_SLOT).orElse(null);
                if (fuel == null) break;
                Integer burnTime = FUELS.get(fuel.itemId());
                if (burnTime == null) break;
                inventory.setSlot(FUEL_SLOT, fuel.count() == 1 ? null : fuel.withCount(fuel.count() - 1));
                state.burnTicksRemaining = burnTime;
                state.burnTicksTotal = burnTime;
                changed = true;
            }
            if (state.burnTicksRemaining == 0) break;

            int untilCooked = canSmelt ? COOK_TIME_TICKS - state.cookProgress : state.burnTicksRemaining;
            long step = Math.min(remainingTicks, Math.min(state.burnTicksRemaining, untilCooked));
            state.burnTicksRemaining -= (int) step;
            remainingTicks -= step;

            if (canSmelt) {
                state.cookProgress += (int) step;
                if (state.cookProgress >= COOK_TIME_TICKS) {
                    consumeOneInput(inventory);
                    addOutput(inventory, recipe.result());
                    state.cookProgress = 0;
                    changed = true;
                }
            } else {
                state.cookProgress = 0;
            }
        }
        return result(state, changed);
    }

    public synchronized void restoreState(int x, int y, int z, int burnTicksRemaining, int cookProgress) {
        restoreState(x, y, z, burnTicksRemaining, cookProgress, burnTicksRemaining);
    }

    public synchronized void restoreState(
            int x, int y, int z, int burnTicksRemaining, int cookProgress, int burnTicksTotal
    ) {
        if (burnTicksRemaining < 0 || burnTicksRemaining > 2400
                || cookProgress < 0 || cookProgress >= COOK_TIME_TICKS
                || burnTicksTotal < burnTicksRemaining || burnTicksTotal > 2400) {
            throw new IllegalArgumentException("Invalid persisted furnace state");
        }
        states.put(new Position(x, y, z),
                new FurnaceState(burnTicksRemaining, cookProgress, burnTicksTotal));
    }

    public synchronized FurnaceStateSnapshot snapshotState(int x, int y, int z) {
        FurnaceState state = states.get(new Position(x, y, z));
        return state == null ? new FurnaceStateSnapshot(0, 0, 0)
                : new FurnaceStateSnapshot(state.burnTicksRemaining, state.cookProgress, state.burnTicksTotal);
    }

    public synchronized void remove(int x, int y, int z) {
        states.remove(new Position(x, y, z));
    }

    private static TickResult result(FurnaceState state, boolean changed) {
        return new TickResult(state.burnTicksRemaining, state.cookProgress,
                (int) Math.round(state.cookProgress * 100.0 / COOK_TIME_TICKS), changed,
                state.burnTicksTotal);
    }

    private static boolean canAcceptOutput(ContainerInventory inventory, ItemStack result) {
        ItemStack output = inventory.getSlot(OUTPUT_SLOT).orElse(null);
        return output == null || (output.canStackWith(result)
                && output.count() + result.count() <= output.maxStackSize());
    }

    private static void consumeOneInput(ContainerInventory inventory) {
        ItemStack input = inventory.getSlot(INPUT_SLOT).orElseThrow();
        inventory.setSlot(INPUT_SLOT, input.count() == 1 ? null : input.withCount(input.count() - 1));
    }

    private static void addOutput(ContainerInventory inventory, ItemStack result) {
        ItemStack output = inventory.getSlot(OUTPUT_SLOT).orElse(null);
        inventory.setSlot(OUTPUT_SLOT, output == null ? result : output.withCount(output.count() + result.count()));
    }

    private static Map<String, SmeltingRecipe> recipes() {
        Map<String, SmeltingRecipe> recipes = new HashMap<>();
        addRecipe(recipes, "minecraft:raw_iron", "minecraft:iron_ingot");
        addRecipe(recipes, "minecraft:raw_gold", "minecraft:gold_ingot");
        addRecipe(recipes, "minecraft:raw_copper", "minecraft:copper_ingot");
        addRecipe(recipes, "minecraft:potato", "minecraft:baked_potato");
        addRecipe(recipes, "minecraft:beef", "minecraft:cooked_beef");
        addRecipe(recipes, "minecraft:chicken", "minecraft:cooked_chicken");
        addRecipe(recipes, "minecraft:salmon", "minecraft:cooked_salmon");
        addRecipe(recipes, "minecraft:cod", "minecraft:cooked_cod");
        return Map.copyOf(recipes);
    }

    private static void addRecipe(Map<String, SmeltingRecipe> recipes, String input, String output) {
        recipes.put(input, new SmeltingRecipe(input, new ItemStack(output, 1, 64)));
    }

    private record Position(int x, int y, int z) { }
    private record SmeltingRecipe(String input, ItemStack result) { }

    private static final class FurnaceState {
        private int burnTicksRemaining;
        private int cookProgress;
        private int burnTicksTotal;

        private FurnaceState() { }
        private FurnaceState(int burnTicksRemaining, int cookProgress, int burnTicksTotal) {
            this.burnTicksRemaining = burnTicksRemaining;
            this.cookProgress = cookProgress;
            this.burnTicksTotal = burnTicksTotal;
        }
    }

    public record FurnaceStateSnapshot(int burnTicksRemaining, int cookProgress, int burnTicksTotal) { }
    public record TickResult(int burnTicksRemaining, int cookProgress, int cookPercent, boolean inventoryChanged,
                             int burnTicksTotal) { }
}
