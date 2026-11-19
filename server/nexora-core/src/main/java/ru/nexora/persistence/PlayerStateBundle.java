package ru.nexora.persistence;

import ru.nexora.inventory.ContainerInventory;
import ru.nexora.inventory.ItemStack;
import ru.nexora.inventory.PlayerEquipment;
import ru.nexora.inventory.PlayerInventory;
import ru.nexora.world.PlayerData;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, detached capture of all currently persisted player-owned state.
 * It contains no live inventory or session objects.
 */
public record PlayerStateBundle(
        PlayerData profile,
        List<Optional<ItemStack>> inventorySlots,
        int selectedHotbarSlot,
        List<Optional<ItemStack>> craftingUiSlots,
        PlayerEquipment.Snapshot equipment
) {
    public static final int CRAFTING_UI_SLOT_COUNT = 51;

    public PlayerStateBundle {
        Objects.requireNonNull(profile, "profile");
        inventorySlots = List.copyOf(Objects.requireNonNull(inventorySlots, "inventorySlots"));
        craftingUiSlots = List.copyOf(Objects.requireNonNull(craftingUiSlots, "craftingUiSlots"));
        Objects.requireNonNull(equipment, "equipment");
        if (inventorySlots.size() < PlayerInventory.HOTBAR_SIZE || inventorySlots.size() > 256) {
            throw new IllegalArgumentException("Invalid player inventory slot count");
        }
        if (selectedHotbarSlot < 0 || selectedHotbarSlot >= PlayerInventory.HOTBAR_SIZE
                || selectedHotbarSlot >= inventorySlots.size()) {
            throw new IllegalArgumentException("Invalid selected hotbar slot");
        }
        if (craftingUiSlots.size() != CRAFTING_UI_SLOT_COUNT) {
            throw new IllegalArgumentException("Invalid player crafting UI slot count");
        }
        inventorySlots.forEach(slot -> Objects.requireNonNull(slot, "inventory slot"));
        craftingUiSlots.forEach(slot -> Objects.requireNonNull(slot, "crafting UI slot"));
    }

    /** Captures the three mutable gameplay containers while holding their monitors. */
    public static PlayerStateBundle capture(PlayerData profile, PlayerInventory inventory,
                                            ContainerInventory craftingUi, PlayerEquipment equipment) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(craftingUi, "craftingUi");
        Objects.requireNonNull(equipment, "equipment");
        synchronized (inventory) {
            synchronized (craftingUi) {
                synchronized (equipment) {
                    return new PlayerStateBundle(profile, inventory.snapshot(),
                            inventory.selectedHotbarSlot(), craftingUi.snapshot(), equipment.snapshot());
                }
            }
        }
    }

    public Restored restore() {
        PlayerInventory inventory = new PlayerInventory(inventorySlots.size());
        inventory.restore(inventorySlots, selectedHotbarSlot);
        ContainerInventory craftingUi = new ContainerInventory(CRAFTING_UI_SLOT_COUNT);
        craftingUi.restore(craftingUiSlots);
        PlayerEquipment restoredEquipment = new PlayerEquipment();
        restoredEquipment.restore(equipment);
        return new Restored(profile, inventory, craftingUi, restoredEquipment);
    }

    public record Restored(PlayerData profile, PlayerInventory inventory,
                           ContainerInventory craftingUi, PlayerEquipment equipment) {
        public Restored {
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(inventory, "inventory");
            Objects.requireNonNull(craftingUi, "craftingUi");
            Objects.requireNonNull(equipment, "equipment");
        }
    }
}
