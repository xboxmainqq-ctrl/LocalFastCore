package ru.nexora.inventory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class InventoryTransactionServiceTest {
    private final InventoryTransactionService service = new InventoryTransactionService();

    @Test
    void appliesAValidAtomicTransferWithoutChangingTotalItemCount() {
        PlayerInventory inventory = new PlayerInventory();
        ItemStack before = new ItemStack("nexora:mana_crystal", 10, 64);
        ItemStack afterSource = new ItemStack("nexora:mana_crystal", 6, 64);
        ItemStack afterTarget = new ItemStack("nexora:mana_crystal", 4, 64);
        inventory.setSlot(0, before);

        var result = service.applyPlayerInventoryTransaction(inventory, List.of(
                InventoryTransactionService.Action.playerSlot(0,
                        Optional.of(before), Optional.of(afterSource)),
                InventoryTransactionService.Action.playerSlot(1,
                        Optional.empty(), Optional.of(afterTarget))));

        assertEquals(InventoryTransactionService.Result.APPLIED, result);
        assertEquals(6, inventory.getSlot(0).orElseThrow().count());
        assertEquals(4, inventory.getSlot(1).orElseThrow().count());
        assertEquals(10, inventory.count("nexora:mana_crystal"));
    }

    @Test
    void appliesAValidSwapAtomically() {
        PlayerInventory inventory = new PlayerInventory();
        ItemStack dust = new ItemStack("nexora:arcane_dust", 3, 64);
        ItemStack crystal = new ItemStack("nexora:mana_crystal", 2, 64);
        inventory.setSlot(0, dust);
        inventory.setSlot(1, crystal);

        var result = service.applyPlayerInventoryTransaction(inventory, List.of(
                InventoryTransactionService.Action.playerSlot(0, Optional.of(dust), Optional.of(crystal)),
                InventoryTransactionService.Action.playerSlot(1, Optional.of(crystal), Optional.of(dust))));

        assertEquals(InventoryTransactionService.Result.APPLIED, result);
        assertEquals(crystal, inventory.getSlot(0).orElseThrow());
        assertEquals(dust, inventory.getSlot(1).orElseThrow());
    }

    @Test
    void rejectsStaleFromItemWithoutChangingAnySlot() {
        PlayerInventory inventory = new PlayerInventory();
        ItemStack actual = new ItemStack("nexora:mana_crystal", 10, 64);
        ItemStack stale = new ItemStack("nexora:mana_crystal", 9, 64);
        inventory.setSlot(0, actual);

        var result = service.applyPlayerInventoryTransaction(inventory, List.of(
                InventoryTransactionService.Action.playerSlot(0,
                        Optional.of(stale), Optional.of(stale)),
                InventoryTransactionService.Action.playerSlot(1,
                        Optional.empty(), Optional.of(new ItemStack("nexora:mana_crystal", 1, 64)))));

        assertEquals(InventoryTransactionService.Result.REJECTED_STALE_OR_UNSAFE, result);
        assertEquals(actual, inventory.getSlot(0).orElseThrow());
        assertTrue(inventory.getSlot(1).isEmpty());
    }

    @Test
    void rejectsItemDuplicationAndDeletion() {
        PlayerInventory inventory = new PlayerInventory();
        ItemStack original = new ItemStack("nexora:arcane_shard", 5, 64);
        inventory.setSlot(0, original);

        var duplicate = service.applyPlayerInventoryTransaction(inventory, List.of(
                InventoryTransactionService.Action.playerSlot(0,
                        Optional.of(original), Optional.of(original)),
                InventoryTransactionService.Action.playerSlot(1, Optional.empty(),
                        Optional.of(new ItemStack("nexora:arcane_shard", 1, 64)))));
        assertEquals(InventoryTransactionService.Result.REJECTED_STALE_OR_UNSAFE, duplicate);
        assertEquals(5, inventory.count("nexora:arcane_shard"));
        assertTrue(inventory.getSlot(1).isEmpty());

        var deletion = service.applyPlayerInventoryTransaction(inventory, List.of(
                InventoryTransactionService.Action.playerSlot(0, Optional.of(original), Optional.empty())));
        assertEquals(InventoryTransactionService.Result.REJECTED_STALE_OR_UNSAFE, deletion);
        assertEquals(original, inventory.getSlot(0).orElseThrow());
    }

    @Test
    void rejectsDuplicateSlotReferencesAndUnsupportedContainers() {
        PlayerInventory inventory = new PlayerInventory();
        ItemStack stack = new ItemStack("nexora:arcane_shard", 2, 64);
        inventory.setSlot(0, stack);

        var duplicateSlot = service.applyPlayerInventoryTransaction(inventory, List.of(
                InventoryTransactionService.Action.playerSlot(0, Optional.of(stack), Optional.of(stack)),
                InventoryTransactionService.Action.playerSlot(0, Optional.of(stack), Optional.of(stack))));
        assertEquals(InventoryTransactionService.Result.REJECTED_STALE_OR_UNSAFE, duplicateSlot);

        var unsupported = service.applyPlayerInventoryTransaction(inventory, List.of(
                new InventoryTransactionService.Action(0, 1, 0,
                        Optional.of(stack), Optional.of(stack))));
        assertEquals(InventoryTransactionService.Result.UNSUPPORTED_CONTAINER, unsupported);
        assertEquals(stack, inventory.getSlot(0).orElseThrow());
    }

    @Test
    void transfersItemsBetweenMainInventoryOffhandAndCursorAtomically() {
        PlayerInventory inventory = new PlayerInventory();
        PlayerEquipment equipment = new PlayerEquipment();
        ItemStack four = new ItemStack("nexora:mana_crystal", 4, 64);
        ItemStack two = new ItemStack("nexora:mana_crystal", 2, 64);
        ItemStack remaining = new ItemStack("nexora:mana_crystal", 2, 64);
        inventory.setSlot(0, four);

        var toOffhand = service.applyPlayerInventoryAndUiTransaction(inventory, equipment, List.of(
                new InventoryTransactionService.Action(0, 0, 0, Optional.of(four), Optional.of(remaining)),
                new InventoryTransactionService.Action(0, 119, 0, Optional.empty(), Optional.of(two))));
        assertEquals(InventoryTransactionService.Result.APPLIED, toOffhand);
        assertEquals(remaining, inventory.getSlot(0).orElseThrow());
        assertEquals(two, equipment.offhand().orElseThrow());

        var offhandToCursor = service.applyPlayerInventoryAndUiTransaction(inventory, equipment, List.of(
                new InventoryTransactionService.Action(0, 119, 0, Optional.of(two), Optional.empty()),
                new InventoryTransactionService.Action(0, 124, 0, Optional.empty(), Optional.of(two))));
        assertEquals(InventoryTransactionService.Result.APPLIED, offhandToCursor);
        assertTrue(equipment.offhand().isEmpty());
        assertEquals(two, equipment.cursor().orElseThrow());
        assertEquals(4, inventory.count("nexora:mana_crystal")
                + equipment.offhand().map(ItemStack::count).orElse(0)
                + equipment.cursor().map(ItemStack::count).orElse(0));
    }

    @Test
    void equipsAndUnequipsVanillaArmorOnlyInItsRegisteredSlot() {
        PlayerInventory inventory = new PlayerInventory();
        PlayerEquipment equipment = new PlayerEquipment();
        ArmorSlotRegistry armorRules = VanillaArmorSlotCatalog.createRegistry();
        ItemStack helmet = new ItemStack("minecraft:iron_helmet", 1, 1);
        inventory.setSlot(3, helmet);

        var equip = service.applyPlayerInventoryAndUiTransaction(inventory, equipment, List.of(
                new InventoryTransactionService.Action(0, 0, 3, Optional.of(helmet), Optional.empty()),
                new InventoryTransactionService.Action(0, 120, 0, Optional.empty(), Optional.of(helmet))),
                armorRules);
        assertEquals(InventoryTransactionService.Result.APPLIED, equip);
        assertTrue(inventory.getSlot(3).isEmpty());
        assertEquals(helmet, equipment.armor(0).orElseThrow());

        var wrongSlot = service.applyPlayerInventoryAndUiTransaction(inventory, equipment, List.of(
                new InventoryTransactionService.Action(0, 120, 0, Optional.of(helmet), Optional.empty()),
                new InventoryTransactionService.Action(0, 120, 3, Optional.empty(), Optional.of(helmet))),
                armorRules);
        assertEquals(InventoryTransactionService.Result.INCOMPATIBLE_EQUIPMENT_ITEM, wrongSlot);
        assertEquals(helmet, equipment.armor(0).orElseThrow());
        assertTrue(equipment.armor(3).isEmpty());

        var unequip = service.applyPlayerInventoryAndUiTransaction(inventory, equipment, List.of(
                new InventoryTransactionService.Action(0, 120, 0, Optional.of(helmet), Optional.empty()),
                new InventoryTransactionService.Action(0, 0, 3, Optional.empty(), Optional.of(helmet))),
                armorRules);
        assertEquals(InventoryTransactionService.Result.APPLIED, unequip);
        assertTrue(equipment.armor(0).isEmpty());
        assertEquals(helmet, inventory.getSlot(3).orElseThrow());
    }

    @Test
    void rejectsArmorTransactionsWithoutRegisteredSlotCompatibility() {
        PlayerInventory inventory = new PlayerInventory();
        PlayerEquipment equipment = new PlayerEquipment();
        ItemStack helmet = new ItemStack("nexora:arcane_helmet", 1, 1);
        var result = service.applyPlayerInventoryAndUiTransaction(inventory, equipment, List.of(
                new InventoryTransactionService.Action(0, 120, 0, Optional.empty(), Optional.of(helmet))));
        assertEquals(InventoryTransactionService.Result.INCOMPATIBLE_EQUIPMENT_ITEM, result);
        assertTrue(equipment.armor(0).isEmpty());
    }

    @Test
    void rejectsInvalidSlotsAndActionCounts() {
        PlayerInventory inventory = new PlayerInventory();
        var invalidSlot = service.applyPlayerInventoryTransaction(inventory, List.of(
                InventoryTransactionService.Action.playerSlot(36, Optional.empty(), Optional.empty())));
        assertEquals(InventoryTransactionService.Result.INVALID_SLOT, invalidSlot);

        assertEquals(InventoryTransactionService.Result.INVALID_ACTION_COUNT,
                service.applyPlayerInventoryTransaction(inventory, List.of()));
        assertEquals(InventoryTransactionService.Result.INVALID_ACTION_COUNT,
                service.applyPlayerInventoryTransaction(inventory,
                        java.util.Collections.nCopies(65,
                                InventoryTransactionService.Action.playerSlot(
                                        0, Optional.empty(), Optional.empty()))));
    }

    @Test
    void rejectsChangingEnchantmentMetadataDuringAPlayerInventoryMove() {
        PlayerInventory inventory = new PlayerInventory();
        ItemStack enchanted = new ItemStack("minecraft:diamond_sword", 1, 1,
                java.util.Map.of("minecraft:sharpness", 5), 0);
        ItemStack forged = new ItemStack("minecraft:diamond_sword", 1, 1);
        inventory.setSlot(0, enchanted);

        var result = service.applyPlayerInventoryTransaction(inventory, List.of(
                InventoryTransactionService.Action.playerSlot(
                        0, Optional.of(enchanted), Optional.of(forged))));

        assertEquals(InventoryTransactionService.Result.REJECTED_STALE_OR_UNSAFE, result);
        assertEquals(enchanted, inventory.getSlot(0).orElseThrow());
    }

    @Test
    void rejectsChangingRepairMetadataWhenMovingAnItemToCursor() {
        PlayerInventory inventory = new PlayerInventory();
        PlayerEquipment equipment = new PlayerEquipment();
        ItemStack repaired = new ItemStack("minecraft:diamond_pickaxe", 1, 1,
                java.util.Map.of("minecraft:efficiency", 4), 7);
        ItemStack forged = new ItemStack("minecraft:diamond_pickaxe", 1, 1,
                java.util.Map.of("minecraft:efficiency", 4), 0);
        inventory.setSlot(0, repaired);

        var result = service.applyPlayerInventoryAndUiTransaction(inventory, equipment, List.of(
                new InventoryTransactionService.Action(0, 0, 0,
                        Optional.of(repaired), Optional.empty()),
                new InventoryTransactionService.Action(0, 124, 0,
                        Optional.empty(), Optional.of(forged))));

        assertEquals(InventoryTransactionService.Result.REJECTED_STALE_OR_UNSAFE, result);
        assertEquals(repaired, inventory.getSlot(0).orElseThrow());
        assertTrue(equipment.cursor().isEmpty());
    }

}
