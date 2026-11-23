package ru.nexora.inventory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Server-authoritative furnace processing engine.
 * Slot layout is input (0), fuel (1), output (2).
 */
public final class FurnaceService {
    public static final int INPUT_SLOT = 0;
    public static final int FUEL_SLOT = 1;
    public static final int OUTPUT_SLOT = 2;
    public static final int COOK_TIME_TICKS = 200;
    public static final int MAX_BURN_TICKS = 16_000;
    public static final int MAX_PENDING_EXPERIENCE_MILLI = 10_000_000;

    public enum Kind {
        FURNACE(200, RecipeGroup.ALL),
        BLAST_FURNACE(100, RecipeGroup.METALS),
        SMOKER(100, RecipeGroup.FOOD);

        private final int cookTimeTicks;
        private final RecipeGroup recipeGroup;

        Kind(int cookTimeTicks, RecipeGroup recipeGroup) {
            this.cookTimeTicks = cookTimeTicks;
            this.recipeGroup = recipeGroup;
        }

        public int cookTimeTicks() { return cookTimeTicks; }
        boolean accepts(RecipeGroup recipe) {
            return recipeGroup == RecipeGroup.ALL || recipeGroup == recipe;
        }
    }

    private enum RecipeGroup { ALL, METALS, FOOD }

    private static final Map<String, SmeltingRecipe> RECIPES = recipes();
    private static final Map<String, Integer> FUELS = Map.of(
            "minecraft:coal_block", 16000,
            "minecraft:blaze_rod", 2400,
            "minecraft:coal", 1600,
            "minecraft:oak_log", 300,
            "minecraft:oak_planks", 300,
            "minecraft:stick", 100
    );
    private final Map<Position, FurnaceState> states = new HashMap<>();

    public synchronized TickResult tick(
            int x, int y, int z, ContainerInventory inventory, long elapsedTicks
    ) {
        return tick(x, y, z, inventory, Kind.FURNACE, elapsedTicks);
    }

    public synchronized TickResult tick(
            int x, int y, int z, ContainerInventory inventory, Kind kind, long elapsedTicks
    ) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(kind, "kind");
        if (inventory.size() != 3) throw new IllegalArgumentException("Furnace inventory must have exactly 3 slots");
        if (elapsedTicks < 0 || elapsedTicks > 20_000) {
            throw new IllegalArgumentException("Elapsed furnace ticks must be 0..20000");
        }
        // A furnace tick is one inventory transaction. Bedrock container transactions
        // synchronize on this same monitor, so they cannot observe or modify a half-tick.
        synchronized (inventory) {
            Position position = new Position(x, y, z);
            FurnaceState state = states.computeIfAbsent(position, ignored -> new FurnaceState());
            if (state.cookProgress >= kind.cookTimeTicks) state.cookProgress = 0;
            if (elapsedTicks == 0) return result(state, false, kind);

            boolean changed = false;
            long remainingTicks = elapsedTicks;
            while (remainingTicks > 0) {
                ItemStack input = inventory.getSlot(INPUT_SLOT).orElse(null);
                SmeltingRecipe recipe = input == null ? null : RECIPES.get(input.itemId());
                if (recipe != null && !kind.accepts(recipe.group())) recipe = null;
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

                int untilCooked = canSmelt ? kind.cookTimeTicks - state.cookProgress : state.burnTicksRemaining;
                long step = Math.min(remainingTicks, Math.min(state.burnTicksRemaining, untilCooked));
                state.burnTicksRemaining -= (int) step;
                remainingTicks -= step;

                if (canSmelt) {
                    state.cookProgress += (int) step;
                    if (state.cookProgress >= kind.cookTimeTicks) {
                        consumeOneInput(inventory);
                        addOutput(inventory, recipe.result());
                        state.cookProgress = 0;
                        state.pendingExperienceMilli = Math.min(MAX_PENDING_EXPERIENCE_MILLI,
                                state.pendingExperienceMilli + recipe.experienceMilli());
                        changed = true;
                    }
                } else {
                    state.cookProgress = 0;
                }
            }
            return result(state, changed, kind);

        }    }

        public synchronized void restoreState(int x, int y, int z, int burnTicksRemaining, int cookProgress) {
        restoreState(x, y, z, burnTicksRemaining, cookProgress, burnTicksRemaining, 0);
    }

    public synchronized void restoreState(
            int x, int y, int z, int burnTicksRemaining, int cookProgress, int burnTicksTotal
    ) {
        restoreState(x, y, z, burnTicksRemaining, cookProgress, burnTicksTotal, 0);
    }

    public synchronized void restoreState(
            int x, int y, int z, int burnTicksRemaining, int cookProgress,
            int burnTicksTotal, int pendingExperienceMilli
    ) {
        if (burnTicksRemaining < 0 || burnTicksRemaining > MAX_BURN_TICKS
                || cookProgress < 0 || cookProgress >= COOK_TIME_TICKS
                || burnTicksTotal < burnTicksRemaining || burnTicksTotal > MAX_BURN_TICKS
                || pendingExperienceMilli < 0 || pendingExperienceMilli > MAX_PENDING_EXPERIENCE_MILLI) {
            throw new IllegalArgumentException("Invalid persisted furnace state");
        }
        states.put(new Position(x, y, z),
                new FurnaceState(burnTicksRemaining, cookProgress, burnTicksTotal, pendingExperienceMilli));
    }

    public synchronized FurnaceStateSnapshot snapshotState(int x, int y, int z) {
        FurnaceState state = states.get(new Position(x, y, z));
        return state == null ? new FurnaceStateSnapshot(0, 0, 0)
                : new FurnaceStateSnapshot(state.burnTicksRemaining, state.cookProgress, state.burnTicksTotal,
                        state.pendingExperienceMilli);
    }

    public synchronized int collectExperience(int x, int y, int z, int removedItems, int previousOutputCount) {
        if (removedItems <= 0 || previousOutputCount <= 0) return 0;
        FurnaceState state = states.get(new Position(x, y, z));
        if (state == null || state.pendingExperienceMilli == 0) return 0;
        int collected = removedItems >= previousOutputCount ? state.pendingExperienceMilli
                : (int) ((long) state.pendingExperienceMilli * removedItems / previousOutputCount);
        state.pendingExperienceMilli -= collected;
        return collected;
    }

    public synchronized void restorePendingExperience(int x, int y, int z, int amount) {
        if (amount < 0) throw new IllegalArgumentException("Experience rollback amount cannot be negative");
        FurnaceState state = states.computeIfAbsent(new Position(x, y, z), ignored -> new FurnaceState());
        state.pendingExperienceMilli = Math.min(MAX_PENDING_EXPERIENCE_MILLI,
                state.pendingExperienceMilli + amount);
    }

    public synchronized void remove(int x, int y, int z) {
        states.remove(new Position(x, y, z));
    }

    private static TickResult result(FurnaceState state, boolean changed, Kind kind) {
        return new TickResult(state.burnTicksRemaining, state.cookProgress,
                (int) Math.round(state.cookProgress * 100.0 / kind.cookTimeTicks), changed,
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
        addRecipe(recipes, "minecraft:raw_iron", "minecraft:iron_ingot", RecipeGroup.METALS, 700);
        addRecipe(recipes, "minecraft:raw_gold", "minecraft:gold_ingot", RecipeGroup.METALS, 1000);
        addRecipe(recipes, "minecraft:raw_copper", "minecraft:copper_ingot", RecipeGroup.METALS, 700);
        addRecipe(recipes, "minecraft:potato", "minecraft:baked_potato", RecipeGroup.FOOD, 350);
        addRecipe(recipes, "minecraft:beef", "minecraft:cooked_beef", RecipeGroup.FOOD, 350);
        addRecipe(recipes, "minecraft:chicken", "minecraft:cooked_chicken", RecipeGroup.FOOD, 350);
        addRecipe(recipes, "minecraft:salmon", "minecraft:cooked_salmon", RecipeGroup.FOOD, 350);
        addRecipe(recipes, "minecraft:cod", "minecraft:cooked_cod", RecipeGroup.FOOD, 350);
        addRecipe(recipes, "minecraft:rabbit", "minecraft:cooked_rabbit", RecipeGroup.FOOD, 350);
        addRecipe(recipes, "minecraft:mutton", "minecraft:cooked_mutton", RecipeGroup.FOOD, 350);
        return Map.copyOf(recipes);
    }

    private static void addRecipe(
            Map<String, SmeltingRecipe> recipes, String input, String output,
            RecipeGroup group, int experienceMilli
    ) {
        recipes.put(input, new SmeltingRecipe(input, new ItemStack(output, 1, 64), group, experienceMilli));
    }

        private record Position(int x, int y, int z) { }
    private record SmeltingRecipe(String input, ItemStack result, RecipeGroup group, int experienceMilli) { }

    private static final class FurnaceState {
        private int burnTicksRemaining;
        private int cookProgress;
        private int burnTicksTotal;
        private int pendingExperienceMilli;

        private FurnaceState() { }
        private FurnaceState(int burnTicksRemaining, int cookProgress, int burnTicksTotal,
                             int pendingExperienceMilli) {
            this.burnTicksRemaining = burnTicksRemaining;
            this.cookProgress = cookProgress;
            this.burnTicksTotal = burnTicksTotal;
            this.pendingExperienceMilli = pendingExperienceMilli;
        }
    }

    public record FurnaceStateSnapshot(int burnTicksRemaining, int cookProgress, int burnTicksTotal,
                                       int pendingExperienceMilli) {
        public FurnaceStateSnapshot(int burnTicksRemaining, int cookProgress, int burnTicksTotal) {
            this(burnTicksRemaining, cookProgress, burnTicksTotal, 0);
        }
    }
    public record TickResult(int burnTicksRemaining, int cookProgress, int cookPercent, boolean inventoryChanged,
                             int burnTicksTotal) { }
}
