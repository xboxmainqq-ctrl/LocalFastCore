package ru.nexora.inventory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ContainerInventoryTransactionServiceTest {
    private final ContainerInventoryTransactionService service = new ContainerInventoryTransactionService();

    @Test
    void atomicallyMovesItemsFromPlayerInventoryIntoOpenContainer() {
        PlayerInventory player = new PlayerInventory();
        ContainerInventory chest = new ContainerInventory();
        player.setSlot(0, new ItemStack("minecraft:stone", 12, 64));

        var result = service.apply(player, chest, 7, List.of(
                action(0, 0, stack("minecraft:stone", 12), stack("minecraft:stone", 5)),
                action(7, 0, null, stack("minecraft:stone", 7))));

        assertEquals(ContainerInventoryTransactionService.Result.APPLIED, result);
        assertEquals(5, player.getSlot(0).orElseThrow().count());
        assertEquals(7, chest.getSlot(0).orElseThrow().count());
    }

    @Test
    void atomicallyMovesItemsFromOpenContainerIntoPlayerInventory() {
        PlayerInventory player = new PlayerInventory();
        ContainerInventory chest = new ContainerInventory();
        chest.setSlot(3, new ItemStack("minecraft:iron_ingot", 8, 64));

        var result = service.apply(player, chest, 9, List.of(
                action(9, 3, stack("minecraft:iron_ingot", 8), null),
                action(0, 4, null, stack("minecraft:iron_ingot", 8))));

        assertEquals(ContainerInventoryTransactionService.Result.APPLIED, result);
        assertTrue(chest.getSlot(3).isEmpty());
        assertEquals(8, player.getSlot(4).orElseThrow().count());
    }

    @Test
    void rejectsWrongWindowStaleSnapshotsAndItemCreationWithoutMutation() {
        PlayerInventory player = new PlayerInventory();
        ContainerInventory chest = new ContainerInventory();
        player.setSlot(0, new ItemStack("minecraft:stone", 10, 64));

        assertEquals(ContainerInventoryTransactionService.Result.UNSUPPORTED_CONTAINER,
                service.apply(player, chest, 7, List.of(
                        action(8, 0, null, stack("minecraft:stone", 1)))));
        assertEquals(ContainerInventoryTransactionService.Result.REJECTED_STALE_OR_UNSAFE,
                service.apply(player, chest, 7, List.of(
                        action(0, 0, stack("minecraft:dirt", 10), stack("minecraft:dirt", 10)))));
        assertEquals(ContainerInventoryTransactionService.Result.REJECTED_STALE_OR_UNSAFE,
                service.apply(player, chest, 7, List.of(
                        action(7, 0, null, stack("minecraft:stone", 1)))));

        assertEquals(10, player.getSlot(0).orElseThrow().count());
        assertTrue(chest.getSlot(0).isEmpty());
    }

    @Test
    void rejectsDuplicateSlotsInvalidWindowAndInvalidSlot() {
        PlayerInventory player = new PlayerInventory();
        ContainerInventory chest = new ContainerInventory();

        assertEquals(ContainerInventoryTransactionService.Result.INVALID_WINDOW,
                service.apply(player, chest, 0, List.of(action(0, 0, null, null))));
        assertEquals(ContainerInventoryTransactionService.Result.INVALID_SLOT,
                service.apply(player, chest, 7, List.of(action(7, 27, null, null))));
        assertEquals(ContainerInventoryTransactionService.Result.INVALID_ACTION,
                service.apply(player, chest, 7, List.of(
                        action(0, 0, null, null),
                        action(0, 0, null, null))));
    }

    private static InventoryTransactionService.Action action(
            int containerId, int slot, ItemStack from, ItemStack to
    ) {
        return new InventoryTransactionService.Action(
                InventoryTransactionService.CONTAINER_INVENTORY_SOURCE,
                containerId, slot, Optional.ofNullable(from), Optional.ofNullable(to));
    }

    private static ItemStack stack(String id, int count) {
        return new ItemStack(id, count, 64);
    }
}
