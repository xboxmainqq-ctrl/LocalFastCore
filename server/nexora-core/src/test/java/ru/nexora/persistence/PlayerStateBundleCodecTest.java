package ru.nexora.persistence;

import org.junit.jupiter.api.Test;
import ru.nexora.inventory.ContainerInventory;
import ru.nexora.inventory.ItemStack;
import ru.nexora.inventory.PlayerEquipment;
import ru.nexora.inventory.PlayerInventory;
import ru.nexora.world.PlayerData;
import ru.nexora.world.PlayerVitals;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlayerStateBundleCodecTest {
    @Test
    void roundTripsProfileInventoryCraftingAndEquipment() throws IOException {
        PlayerData profile = new PlayerData("xuid-test_42", "NexoraUser", "world",
                12.5f, 70.0f, -4.25f, 90.0f, 10.0f, 123456L,
                new PlayerVitals(17.0f, 18, 4.0f, 0.5f), 1234, 567);

        PlayerInventory inventory = new PlayerInventory();
        inventory.setSlot(0, new ItemStack("minecraft:diamond_sword", 1, 1,
                Map.of("minecraft:sharpness", 5), 3));
        inventory.selectHotbarSlot(0);

        ContainerInventory crafting = new ContainerInventory(PlayerStateBundle.CRAFTING_UI_SLOT_COUNT);
        crafting.setSlot(28, new ItemStack("minecraft:diamond", 3, 64));

        PlayerEquipment equipment = new PlayerEquipment();
        equipment.setArmor(0, new ItemStack("minecraft:diamond_helmet", 1, 1));
        equipment.setOffhand(new ItemStack("minecraft:shield", 1, 1));

        PlayerStateBundle original = PlayerStateBundle.capture(profile, inventory, crafting, equipment);
        PlayerStateBundleCodec codec = new PlayerStateBundleCodec();
        PlayerStateBundle decoded = codec.decode(codec.encode(original));

        assertEquals(original, decoded);
        PlayerStateBundle.Restored restored = decoded.restore();
        assertEquals(profile, restored.profile());
        assertEquals(inventory.snapshot(), restored.inventory().snapshot());
        assertEquals(0, restored.inventory().selectedHotbarSlot());
        assertEquals(crafting.snapshot(), restored.craftingUi().snapshot());
        assertEquals(equipment.snapshot(), restored.equipment().snapshot());
    }

    @Test
    void rejectsTruncatedAndUnsupportedPayloads() throws IOException {
        PlayerData profile = new PlayerData("xuid-1", "User", "world",
                0, 64, 0, 0, 0, 1L, PlayerVitals.DEFAULT, 0, 0);
        PlayerStateBundle bundle = PlayerStateBundle.capture(profile, new PlayerInventory(),
                new ContainerInventory(PlayerStateBundle.CRAFTING_UI_SLOT_COUNT), new PlayerEquipment());
        PlayerStateBundleCodec codec = new PlayerStateBundleCodec();
        byte[] encoded = codec.encode(bundle);

        assertThrows(IOException.class, () -> codec.decode(new byte[] {1, 2, 3}));
        encoded[7] = 2;
        assertThrows(IOException.class, () -> codec.decode(encoded));
    }
}
