package ru.nexora.inventory;

import ru.nexora.world.PlayerExperience;

import java.util.Objects;

/**
 * Applies an anvil combination to two authoritative player-inventory slots.
 * The operation changes neither inventory nor XP when the rule check fails.
 */
public final class AnvilTransactionService {
    private final AnvilService rules;

    public AnvilTransactionService() {
        this(new AnvilService());
    }

    public AnvilTransactionService(AnvilService rules) {
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    public Result combine(PlayerInventory inventory, int baseSlot, int sacrificeSlot,
                          long totalExperienceMilli) {
        Objects.requireNonNull(inventory, "inventory");
        PlayerExperience.fromTotalMilliPoints(totalExperienceMilli);
        synchronized (inventory) {
            if (baseSlot == sacrificeSlot) {
                return new Result(AnvilService.Status.INVALID_SLOTS, null, 0, totalExperienceMilli);
            }
            ItemStack base = inventory.getSlot(baseSlot).orElse(null);
            ItemStack sacrifice = inventory.getSlot(sacrificeSlot).orElse(null);
            if (base == null || sacrifice == null) {
                return new Result(AnvilService.Status.EMPTY_SLOT, null, 0, totalExperienceMilli);
            }

            int availableLevels = PlayerExperience.fromTotalMilliPoints(totalExperienceMilli).level();
            AnvilService.Outcome outcome = rules.combine(base, sacrifice, availableLevels);
            if (!outcome.successful()) {
                return new Result(outcome.status(), null, 0, totalExperienceMilli);
            }

            ItemStack result = outcome.resultStack();
            long remainingExperience = PlayerExperience.removeLevelsFromTotalMilliPoints(
                    totalExperienceMilli, outcome.levelCost());
            inventory.setSlot(baseSlot, result);
            inventory.setSlot(sacrificeSlot, null);
            return new Result(AnvilService.Status.SUCCESS, result,
                    outcome.levelCost(), remainingExperience);
        }
    }

    public record Result(AnvilService.Status status, ItemStack result,
                         int levelCost, long remainingExperienceMilli) {
        public Result {
            Objects.requireNonNull(status, "status");
            if ((status == AnvilService.Status.SUCCESS) != (result != null)
                    || levelCost < 0 || remainingExperienceMilli < 0) {
                throw new IllegalArgumentException("Invalid anvil transaction result");
            }
        }

        public boolean successful() {
            return status == AnvilService.Status.SUCCESS;
        }
    }
}
