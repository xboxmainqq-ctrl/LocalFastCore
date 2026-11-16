package ru.nexora.inventory;

import java.util.Objects;
import java.util.Optional;

/** Atomic server-side equip/unequip operations for magical equipment slots. */
public final class PlayerEquipmentService {

    public Result equipFromInventory(PlayerInventory inventory, PlayerEquipment equipment,
                                     EquipmentSlot slot, int inventorySlot,
                                     EquipmentSlotRegistry registry) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(equipment, "equipment");
        Objects.requireNonNull(slot, "slot");
        Objects.requireNonNull(registry, "registry");

        synchronized (inventory) {
            synchronized (equipment) {
                Optional<ItemStack> source;
                try {
                    source = inventory.getSlot(inventorySlot);
                } catch (IndexOutOfBoundsException exception) {
                    return Result.INVALID_INVENTORY_SLOT;
                }
                if (source.isEmpty()) return Result.EMPTY_INVENTORY_SLOT;
                ItemStack stack = source.orElseThrow();
                if (stack.count() != 1) return Result.NON_SINGLE_ITEM;
                if (!registry.allows(stack, slot)) return Result.INCOMPATIBLE_ITEM;
                if (equipment.magicSlot(slot).isPresent()) return Result.EQUIPMENT_SLOT_OCCUPIED;

                inventory.setSlot(inventorySlot, null);
                equipment.setMagicSlot(slot, stack);
                return Result.EQUIPPED;
            }
        }
    }

    public Result unequipToInventory(PlayerInventory inventory, PlayerEquipment equipment,
                                     EquipmentSlot slot) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(equipment, "equipment");
        Objects.requireNonNull(slot, "slot");

        synchronized (inventory) {
            synchronized (equipment) {
                Optional<ItemStack> equipped = equipment.magicSlot(slot);
                if (equipped.isEmpty()) return Result.EMPTY_EQUIPMENT_SLOT;
                ItemStack stack = equipped.orElseThrow();
                // Magical equipment is unique. This also guarantees add() cannot partially mutate
                // the inventory before reporting that it was full.
                if (stack.count() != 1) return Result.NON_SINGLE_ITEM;
                if (inventory.add(stack) != 0) return Result.INVENTORY_FULL;
                equipment.setMagicSlot(slot, null);
                return Result.UNEQUIPPED;
            }
        }
    }

    public enum Result {
        EQUIPPED,
        UNEQUIPPED,
        INVALID_INVENTORY_SLOT,
        EMPTY_INVENTORY_SLOT,
        EMPTY_EQUIPMENT_SLOT,
        NON_SINGLE_ITEM,
        INCOMPATIBLE_ITEM,
        EQUIPMENT_SLOT_OCCUPIED,
        INVENTORY_FULL
    }
}
