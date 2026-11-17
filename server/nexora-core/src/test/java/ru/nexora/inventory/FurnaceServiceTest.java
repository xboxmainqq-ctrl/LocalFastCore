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

        assertEquals(0, result.cookPercent()); // The 200-tick cooking cycle has completed.
        assertEquals(new ItemStack("minecraft:iron_ingot", 1, 64),
                furnace.getSlot(FurnaceService.OUTPUT_SLOT).orElseThrow());
        assertTrue(furnace.getSlot(FurnaceService.INPUT_SLOT).isEmpty());
        assertEquals(2200, result.burnTicksRemaining());
        assertEquals(0, result.cookProgress());
    }

    @Test
    void coalBurnsForEightSmeltingOperations() {
        FurnaceService service = new FurnaceService();
        ContainerInventory furnace = new ContainerInventory(3);
        furnace.setSlot(FurnaceService.INPUT_SLOT, new ItemStack("minecraft:raw_iron", 8, 64));
        furnace.setSlot(FurnaceService.FUEL_SLOT, new ItemStack("minecraft:coal", 1, 64));

        FurnaceService.TickResult result = service.tick(8, 64, 8, furnace, 1600);

        assertEquals(0, result.cookProgress());
        assertEquals(0, result.burnTicksRemaining());
        assertEquals(new ItemStack("minecraft:iron_ingot", 8, 64),
                furnace.getSlot(FurnaceService.OUTPUT_SLOT).orElseThrow());
        assertTrue(furnace.getSlot(FurnaceService.INPUT_SLOT).isEmpty());
        assertTrue(furnace.getSlot(FurnaceService.FUEL_SLOT).isEmpty());
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
    void fuelContinuesBurningAfterInputIsRemoved() {
        FurnaceService service = new FurnaceService();
        ContainerInventory furnace = new ContainerInventory(3);
        furnace.setSlot(FurnaceService.INPUT_SLOT, new ItemStack("minecraft:raw_iron", 1, 64));
        furnace.setSlot(FurnaceService.FUEL_SLOT, new ItemStack("minecraft:coal", 1, 64));

        FurnaceService.TickResult started = service.tick(2, 64, 2, furnace, 50);
        assertEquals(1550, started.burnTicksRemaining());
        furnace.setSlot(FurnaceService.INPUT_SLOT, null);

        FurnaceService.TickResult idle = service.tick(2, 64, 2, furnace, 50);
        assertEquals(1500, idle.burnTicksRemaining());
        assertEquals(0, idle.cookProgress());
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
    void supportsWoodAndCoalBlockAsFuel() {
        FurnaceService service = new FurnaceService();
        String[] fuels = {"minecraft:oak_log", "minecraft:oak_planks", "minecraft:coal_block"};
        int[] durations = {300, 300, 16000};

        for (int i = 0; i < fuels.length; i++) {
            ContainerInventory furnace = new ContainerInventory(3);
            furnace.setSlot(FurnaceService.INPUT_SLOT, new ItemStack("minecraft:raw_iron", 1, 64));
            furnace.setSlot(FurnaceService.FUEL_SLOT, new ItemStack(fuels[i], 1, 64));
            FurnaceService.TickResult result = service.tick(i, 64, 20, furnace, 1);
            assertEquals(durations[i] - 1, result.burnTicksRemaining(), fuels[i]);
            assertTrue(furnace.getSlot(FurnaceService.FUEL_SLOT).isEmpty(), fuels[i]);
        }
    }

    @Test
    void blastFurnaceSmeltsMetalsTwiceAsFastAndRejectsFood() {
        FurnaceService service = new FurnaceService();
        ContainerInventory furnace = new ContainerInventory(3);
        furnace.setSlot(FurnaceService.INPUT_SLOT, new ItemStack("minecraft:raw_iron", 1, 64));
        furnace.setSlot(FurnaceService.FUEL_SLOT, new ItemStack("minecraft:coal", 1, 64));

        FurnaceService.TickResult result = service.tick(
                10, 64, 10, furnace, FurnaceService.Kind.BLAST_FURNACE, 100);

        assertEquals(0, result.cookProgress());
        assertEquals(new ItemStack("minecraft:iron_ingot", 1, 64),
                furnace.getSlot(FurnaceService.OUTPUT_SLOT).orElseThrow());

        ContainerInventory food = new ContainerInventory(3);
        food.setSlot(FurnaceService.INPUT_SLOT, new ItemStack("minecraft:beef", 1, 64));
        food.setSlot(FurnaceService.FUEL_SLOT, new ItemStack("minecraft:coal", 1, 64));
        FurnaceService.TickResult rejected = service.tick(
                11, 64, 10, food, FurnaceService.Kind.BLAST_FURNACE, 100);
        assertEquals(0, rejected.burnTicksRemaining());
        assertTrue(food.getSlot(FurnaceService.FUEL_SLOT).isPresent());
        assertTrue(food.getSlot(FurnaceService.INPUT_SLOT).isPresent());
    }

    @Test
    void smokerCooksFoodTwiceAsFastAndRejectsMetals() {
        FurnaceService service = new FurnaceService();
        ContainerInventory smoker = new ContainerInventory(3);
        smoker.setSlot(FurnaceService.INPUT_SLOT, new ItemStack("minecraft:beef", 1, 64));
        smoker.setSlot(FurnaceService.FUEL_SLOT, new ItemStack("minecraft:coal", 1, 64));

        FurnaceService.TickResult result = service.tick(
                12, 64, 10, smoker, FurnaceService.Kind.SMOKER, 100);

        assertEquals(new ItemStack("minecraft:cooked_beef", 1, 64),
                smoker.getSlot(FurnaceService.OUTPUT_SLOT).orElseThrow());
        assertEquals(0, result.cookProgress());

        ContainerInventory metal = new ContainerInventory(3);
        metal.setSlot(FurnaceService.INPUT_SLOT, new ItemStack("minecraft:raw_gold", 1, 64));
        metal.setSlot(FurnaceService.FUEL_SLOT, new ItemStack("minecraft:coal", 1, 64));
        FurnaceService.TickResult rejected = service.tick(
                13, 64, 10, metal, FurnaceService.Kind.SMOKER, 100);
        assertEquals(0, rejected.burnTicksRemaining());
        assertTrue(metal.getSlot(FurnaceService.FUEL_SLOT).isPresent());
        assertTrue(metal.getSlot(FurnaceService.INPUT_SLOT).isPresent());
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
