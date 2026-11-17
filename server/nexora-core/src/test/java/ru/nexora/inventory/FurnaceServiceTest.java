package ru.nexora.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FurnaceServiceTest {
    @Test
    void smeltsRawIronUsingFuelAndProducesAnIngot() {
        FurnaceService service = new FurnaceService();
        ContainerInventory furnace = new ContainerInventory(3);
        furnace.setSlot(FurnaceService.INPUT_SLOT, new ItemStack("minecraft:raw_iron", 1, 64));
        furnace.setSlot(FurnaceService.FUEL_SLOT, new ItemStack("minecraft:blaze_rod", 1, 64));

        FurnaceService.TickResult result = service.tick(4, 65, 8, furnace, 200);

        assertEquals(100, result.cookPercent());
        assertEquals(new ItemStack("minecraft:iron_ingot", 1, 64),
                furnace.getSlot(FurnaceService.OUTPUT_SLOT).orElseThrow());
        assertTrue(furnace.getSlot(FurnaceService.INPUT_SLOT).isEmpty());
        assertEquals(2200, result.burnTicksRemaining());
        assertEquals(0, result.cookProgress());
    }

    @Test
    void preservesPartialProgressWhenFuelRunsOutAndCanRestoreIt() {
        FurnaceService first = new FurnaceService();
        ContainerInventory furnace = new ContainerInventory(3);
        furnace.setSlot(FurnaceService.INPUT_SLOT, new ItemStack("minecraft:potato", 1, 64));
        furnace.setSlot(FurnaceService.FUEL_SLOT, new ItemStack("minecraft:stick", 1, 64));

        FurnaceService.TickResult partial = first.tick(0, 64, 0, furnace, 100);
        assertEquals(50, partial.cookPercent());
        assertEquals(0, partial.burnTicksRemaining());

        FurnaceService.FurnaceStateSnapshot saved = first.snapshotState(0, 64, 0);
        FurnaceService restarted = new FurnaceService();
        restarted.restoreState(0, 64, 0, saved.burnTicksRemaining(), saved.cookProgress());
        furnace.setSlot(FurnaceService.FUEL_SLOT, new ItemStack("minecraft:blaze_rod", 1, 64));

        restarted.tick(0, 64, 0, furnace, 100);
        assertEquals(new ItemStack("minecraft:baked_potato", 1, 64),
                furnace.getSlot(FurnaceService.OUTPUT_SLOT).orElseThrow());
        assertTrue(furnace.getSlot(FurnaceService.INPUT_SLOT).isEmpty());
    }

    @Test
    void doesNotConsumeInputWhenOutputCannotAcceptSmeltedItem() {
        FurnaceService service = new FurnaceService();
        ContainerInventory furnace = new ContainerInventory(3);
        furnace.setSlot(FurnaceService.INPUT_SLOT, new ItemStack("minecraft:raw_gold", 1, 64));
        furnace.setSlot(FurnaceService.FUEL_SLOT, new ItemStack("minecraft:blaze_rod", 1, 64));
        furnace.setSlot(FurnaceService.OUTPUT_SLOT, new ItemStack("minecraft:iron_ingot", 64, 64));

        FurnaceService.TickResult result = service.tick(1, 2, 3, furnace, 200);

        assertFalse(result.inventoryChanged());
        assertEquals(new ItemStack("minecraft:raw_gold", 1, 64),
                furnace.getSlot(FurnaceService.INPUT_SLOT).orElseThrow());
        assertEquals(new ItemStack("minecraft:blaze_rod", 1, 64),
                furnace.getSlot(FurnaceService.FUEL_SLOT).orElseThrow());
    }

    @Test
    void rejectsUnsupportedFuelAndInvalidElapsedTicks() {
        FurnaceService service = new FurnaceService();
        ContainerInventory furnace = new ContainerInventory(3);
        furnace.setSlot(FurnaceService.INPUT_SLOT, new ItemStack("minecraft:raw_iron", 1, 64));
        furnace.setSlot(FurnaceService.FUEL_SLOT, new ItemStack("minecraft:iron_ingot", 1, 64));

        assertEquals(0, service.tick(0, 0, 0, furnace, 200).cookProgress());
        assertThrows(IllegalArgumentException.class, () -> service.tick(0, 0, 0, furnace, -1));
    }
}
