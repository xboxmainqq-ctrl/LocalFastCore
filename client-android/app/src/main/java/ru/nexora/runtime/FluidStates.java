package ru.nexora.runtime;

import java.util.Objects;
import java.util.Set;

/** Validation helpers for fluid states stored independently from solid blocks. */
final class FluidStates {
    private static final Set<String> FLUID_IDENTIFIERS = Set.of(
            "minecraft:water", "minecraft:flowing_water",
            "minecraft:lava", "minecraft:flowing_lava");

    private FluidStates() {
    }

    static boolean isFluid(BlockState state) {
        Objects.requireNonNull(state, "state");
        return FLUID_IDENTIFIERS.contains(state.identifier());
    }

    static void requireFluidOrAir(BlockState state) {
        Objects.requireNonNull(state, "fluidState");
        if (!state.isAir() && !isFluid(state)) {
            throw new IllegalArgumentException("Fluid storage accepts only water/lava fluid states: " + state);
        }
    }
}
