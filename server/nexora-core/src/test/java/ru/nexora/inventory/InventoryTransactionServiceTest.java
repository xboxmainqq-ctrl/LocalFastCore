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
}
