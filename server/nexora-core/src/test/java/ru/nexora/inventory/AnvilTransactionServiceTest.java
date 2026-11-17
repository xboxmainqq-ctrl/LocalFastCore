package ru.nexora.inventory;

import org.junit.jupiter.api.Test;
import ru.nexora.world.PlayerExperience;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnvilTransactionServiceTest {
    private final AnvilTransactionService service = new AnvilTransactionService();

    @Test
    void combinesInventoryStacksAndDeductsLevelsWhilePreservingXpProgress() {
        PlayerInventory inventory = new PlayerInventory();
        ItemStack sword = new ItemStack("minecraft:diamond_sword", 1, 1,
                Map.of("minecraft:sharpness", 2), 0);
        ItemStack book = new ItemStack("minecraft:enchanted_book", 1, 1,
                Map.of("minecraft:sharpness", 2), 0);
        inventory.setSlot(0, sword);
        inventory.setSlot(1, book);
        long xp = (long) PlayerExperience.pointsAtLevelStart(10) * 1000L + 1234L;

        AnvilTransactionService.Result result = service.combine(inventory, 0, 1, xp);

        assertTrue(result.successful());
        assertEquals(3, result.result().enchantments().get("minecraft:sharpness"));
        assertEquals(1, result.result().repairCost());
        assertEquals(result.result(), inventory.getSlot(0).orElseThrow());
        assertTrue(inventory.getSlot(1).isEmpty());
        assertEquals(3, result.levelCost());
        assertEquals(7, PlayerExperience.fromTotalMilliPoints(result.remainingExperienceMilli()).level());
        assertEquals(PlayerExperience.fromTotalMilliPoints(xp).progress(),
                PlayerExperience.fromTotalMilliPoints(result.remainingExperienceMilli()).progress(), 0.0001f);
    }

    @Test
    void leavesInventoryAndExperienceUnchangedWhenLevelsAreInsufficient() {
        PlayerInventory inventory = new PlayerInventory();
        ItemStack sword = new ItemStack("minecraft:diamond_sword", 1, 1);
        ItemStack book = new ItemStack("minecraft:enchanted_book", 1, 1,
                Map.of("minecraft:sharpness", 5), 0);
        inventory.setSlot(0, sword);
        inventory.setSlot(1, book);

        AnvilTransactionService.Result result = service.combine(inventory, 0, 1, 500L);

        assertEquals(AnvilService.Status.INSUFFICIENT_EXPERIENCE, result.status());
        assertEquals(500L, result.remainingExperienceMilli());
        assertEquals(sword, inventory.getSlot(0).orElseThrow());
        assertEquals(book, inventory.getSlot(1).orElseThrow());
    }

    @Test
    void leavesInventoryUnchangedForConflictingEnchantments() {
        PlayerInventory inventory = new PlayerInventory();
        ItemStack pickaxe = new ItemStack("minecraft:diamond_pickaxe", 1, 1,
                Map.of("minecraft:fortune", 2), 0);
        ItemStack book = new ItemStack("minecraft:enchanted_book", 1, 1,
                Map.of("minecraft:silk_touch", 1), 0);
        inventory.setSlot(0, pickaxe);
        inventory.setSlot(1, book);

        AnvilTransactionService.Result result = service.combine(inventory, 0, 1, 100_000L);

        assertEquals(AnvilService.Status.CONFLICTING_ENCHANTMENTS, result.status());
        assertEquals(pickaxe, inventory.getSlot(0).orElseThrow());
        assertEquals(book, inventory.getSlot(1).orElseThrow());
    }

    @Test
    void reportsEmptyAndDuplicateSlotsWithoutMutation() {
        PlayerInventory inventory = new PlayerInventory();
        ItemStack sword = new ItemStack("minecraft:diamond_sword", 1, 1);
        inventory.setSlot(0, sword);

        assertEquals(AnvilService.Status.EMPTY_SLOT, service.combine(inventory, 0, 1, 100_000L).status());
        assertEquals(AnvilService.Status.INVALID_SLOTS, service.combine(inventory, 0, 0, 100_000L).status());
        assertEquals(sword, inventory.getSlot(0).orElseThrow());
    }
}
