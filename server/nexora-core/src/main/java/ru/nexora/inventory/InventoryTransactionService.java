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

    /**
     * Applies transfers between the main inventory, offhand and cursor as one
     * atomic, item-conserving transaction. Armor remains gated until the item
     * registry carries authoritative armor-slot compatibility metadata.
     */
    public Result applyPlayerInventoryAndUiTransaction(
            PlayerInventory inventory, PlayerEquipment equipment, List<Action> actions
    ) {
        return applyPlayerInventoryAndUiTransaction(inventory, equipment, actions, ArmorSlotRegistry.empty());
    }

    public Result applyPlayerInventoryAndUiTransaction(
            PlayerInventory inventory, PlayerEquipment equipment, List<Action> actions,
            ArmorSlotRegistry armorSlotRegistry
    ) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(equipment, "equipment");
        Objects.requireNonNull(actions, "actions");
        Objects.requireNonNull(armorSlotRegistry, "armorSlotRegistry");
        if (actions.isEmpty() || actions.size() > MAX_ACTIONS) return Result.INVALID_ACTION_COUNT;

        synchronized (inventory) {
            synchronized (equipment) {
                java.util.Set<String> touched = new java.util.HashSet<>();
                java.util.Map<String, Integer> itemDeltas = new java.util.HashMap<>();
                List<PendingChange> pending = new ArrayList<>(actions.size());

                for (Action action : actions) {
                    if (action == null) return Result.INVALID_ACTION;
                    if (action.sourceType() != CONTAINER_INVENTORY_SOURCE) return Result.UNSUPPORTED_CONTAINER;
                    int container = action.containerId();
                    int slot = action.slot();
                    if (container != 0 && container != 119 && container != 120 && container != 124) {
                        return Result.UNSUPPORTED_CONTAINER;
                    }
                    if ((container == 119 || container == 124) && slot != 0) return Result.INVALID_SLOT;
                    if (container == 120 && (slot < 0 || slot >= PlayerEquipment.ARMOR_SLOTS)) {
                        return Result.INVALID_SLOT;
                    }
                    if (container == 0 && (slot < 0 || slot >= inventory.size())) return Result.INVALID_SLOT;
                    if (!touched.add(container + ":" + slot)) return Result.INVALID_ACTION;

                    Optional<ItemStack> current = switch (container) {
                        case 0 -> inventory.getSlot(slot);
                        case 119 -> equipment.offhand();
                        case 120 -> equipment.armor(slot);
                        case 124 -> equipment.cursor();
                        default -> throw new IllegalStateException("Validated container became unsupported");
                    };
                    if (!current.equals(action.from())) return Result.REJECTED_STALE_OR_UNSAFE;

                    Optional<ItemStack> replacement = action.to();
                    if (container == 120) {
                        ArmorSlot armorSlot = ArmorSlot.values()[slot];
                        if (current.isPresent() && !armorSlotRegistry.allows(current.orElseThrow(), armorSlot)) {
                            return Result.REJECTED_STALE_OR_UNSAFE;
                        }
                        if (replacement.isPresent() && !armorSlotRegistry.allows(replacement.orElseThrow(), armorSlot)) {
                            return Result.INCOMPATIBLE_EQUIPMENT_ITEM;
                        }
                    }
                    current.ifPresent(stack -> itemDeltas.merge(stack.itemId(), -stack.count(), Integer::sum));
                    replacement.ifPresent(stack -> itemDeltas.merge(stack.itemId(), stack.count(), Integer::sum));
                    pending.add(new PendingChange(container, slot, replacement));
                }

                if (itemDeltas.values().stream().anyMatch(delta -> delta != 0)) {
                    return Result.REJECTED_STALE_OR_UNSAFE;
                }
                for (PendingChange change : pending) {
                    switch (change.containerId()) {
                        case 0 -> inventory.setSlot(change.slot(), change.replacement().orElse(null));
                        case 119 -> equipment.setOffhand(change.replacement().orElse(null));
                        case 120 -> equipment.setArmor(change.slot(), change.replacement().orElse(null));
                        case 124 -> equipment.setCursor(change.replacement().orElse(null));
                        default -> throw new IllegalStateException("Validated container became unsupported");
                    }
                }
                return Result.APPLIED;
            }
        }
    }

    private record PendingChange(int containerId, int slot, Optional<ItemStack> replacement) {
    }

    public enum Result {
        APPLIED,
        INVALID_ACTION_COUNT,
        INVALID_ACTION,
        INVALID_SLOT,
        UNSUPPORTED_CONTAINER,
        INCOMPATIBLE_EQUIPMENT_ITEM,
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
