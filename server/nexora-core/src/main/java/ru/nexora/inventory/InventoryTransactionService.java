package ru.nexora.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Server-side validator for the supported subset of Bedrock normal inventory
 * transactions while ItemStackNetManager is disabled.
 *
 * The network decoder must construct these actions from packet 0x1e only after
 * validating its packet framing and item descriptors. This service never trusts
 * a client-proposed result without comparing it to the current server inventory.
 */
public final class InventoryTransactionService {
    public static final int INVENTORY_TRANSACTION_PACKET_ID = 0x1e;
    public static final int NORMAL_TRANSACTION_TYPE = 0;
    public static final int CONTAINER_INVENTORY_SOURCE = 0;
    public static final int PLAYER_INVENTORY_CONTAINER_ID = 0;
    public static final int MAX_ACTIONS = 64;

    public Result applyPlayerInventoryTransaction(PlayerInventory inventory, List<Action> actions) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(actions, "actions");
        if (actions.isEmpty() || actions.size() > MAX_ACTIONS) return Result.INVALID_ACTION_COUNT;

        List<PlayerInventory.SlotChange> changes = new ArrayList<>(actions.size());
        for (Action action : actions) {
            if (action == null) return Result.INVALID_ACTION;
            if (action.sourceType() != CONTAINER_INVENTORY_SOURCE
                    || action.containerId() != PLAYER_INVENTORY_CONTAINER_ID) {
                return Result.UNSUPPORTED_CONTAINER;
            }
            if (action.slot() < 0 || action.slot() >= inventory.size()) {
                return Result.INVALID_SLOT;
            }
            changes.add(new PlayerInventory.SlotChange(action.slot(), action.from(), action.to()));
        }

        return inventory.applySlotChanges(changes) ? Result.APPLIED : Result.REJECTED_STALE_OR_UNSAFE;
    }

    public enum Result {
        APPLIED,
        INVALID_ACTION_COUNT,
        INVALID_ACTION,
        INVALID_SLOT,
        UNSUPPORTED_CONTAINER,
        REJECTED_STALE_OR_UNSAFE
    }

    /**
     * A decoded InventoryAction. Item descriptors must be resolved to logical
     * NEXORA stacks by a strict protocol item registry before reaching this layer.
     */
    public record Action(
            int sourceType,
            int containerId,
            int slot,
            Optional<ItemStack> from,
            Optional<ItemStack> to
    ) {
        public Action {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }

        public static Action playerSlot(
                int slot, Optional<ItemStack> from, Optional<ItemStack> to
        ) {
            return new Action(CONTAINER_INVENTORY_SOURCE, PLAYER_INVENTORY_CONTAINER_ID,
                    slot, from, to);
        }
    }
}
