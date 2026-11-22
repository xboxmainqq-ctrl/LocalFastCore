package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import ru.nexora.inventory.ContainerInventory;
import ru.nexora.inventory.ItemStack;
import ru.nexora.inventory.PlayerInventory;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class BedrockInventoryContentCodecTest {
    @Test
    void encodesThirtySixEmptyPlayerSlotsWithRequiredNetIdPrefix() {
        BedrockPacket packet = BedrockInventoryContentCodec.createPlayerInventory(
                new PlayerInventory(), ignored -> fail("Empty inventory must not resolve item IDs"));
        byte[] payload = packet.payload();
        assertEquals(0x31, packet.packetId());
        assertEquals(340, payload.length); // header 2 + 36*(NetId 1 + descriptor 8) + FullContainerName 6 + StorageItem 8
        assertEquals(0, Byte.toUnsignedInt(payload[0]));
        assertEquals(36, Byte.toUnsignedInt(payload[1]));
        for (int slot = 0; slot < 36; slot++) {
            int offset = 2 + slot * 9;
            assertArrayEquals(new byte[8], Arrays.copyOfRange(payload, offset, offset + 8));
        }
        assertEquals(12, Byte.toUnsignedInt(payload[326]));
        assertEquals(1, Byte.toUnsignedInt(payload[327]));
        assertArrayEquals(new byte[4], Arrays.copyOfRange(payload, 328, 332));
        assertArrayEquals(new byte[8], Arrays.copyOfRange(payload, 332, 340));
    }

    @Test
    void encodesNonEmptyItemAsDescriptorImmediatelyAfterSlotCount() {
        PlayerInventory inventory = new PlayerInventory();
        inventory.setSlot(0, new ItemStack("nexora:arcane_shard", 3, 64));
        BedrockPacket packet = BedrockInventoryContentCodec.createPlayerInventory(
                inventory, ignored -> new BedrockInventoryContentCodec.ItemDefinition(257, 0, 0, new byte[0]));
        byte[] payload = packet.payload();
        assertEquals(1, Byte.toUnsignedInt(payload[2])); // ItemStackNetId for non-empty item
        assertArrayEquals(new byte[] {1, 1, 3, 0, 0, 0, 0, 0},
                Arrays.copyOfRange(payload, 3, 11));
        assertEquals(0, Byte.toUnsignedInt(payload[11])); // ItemStackNetId for empty slot
        assertArrayEquals(new byte[8], Arrays.copyOfRange(payload, 12, 20));
    }

    @Test
    void encodesItemMetadataInsideDescriptorUserData() {
        PlayerInventory inventory = new PlayerInventory();
        inventory.setSlot(0, new ItemStack("minecraft:diamond_sword", 1, 1,
                java.util.Map.of("minecraft:sharpness", 5), 3));
        BedrockPacket packet = BedrockInventoryContentCodec.createPlayerInventory(
                inventory, ignored -> new BedrockInventoryContentCodec.ItemDefinition(276, 0, 0, new byte[0]));
        byte[] payload = packet.payload();
        int userDataLengthOffset = 2 + 1 + 2 + 2 + 1 + 1 + 1;
        int userDataLength = Byte.toUnsignedInt(payload[userDataLengthOffset]);
        byte[] userData = Arrays.copyOfRange(payload, userDataLengthOffset + 1,
                userDataLengthOffset + 1 + userDataLength);
        var metadata = BedrockItemStackUserDataCodec.decode(userData, new byte[0]);
        assertEquals(java.util.Map.of("minecraft:sharpness", 5), metadata.enchantments());
        assertEquals(3, metadata.repairCost());
    }

    @Test
    void encodesTwentySevenEmptyContainerSlotsAsDescriptors() {
        BedrockPacket packet = BedrockInventoryContentCodec.createContainerInventory(
                7, new ContainerInventory(), ignored -> fail("Empty container must not resolve item IDs"));
        byte[] payload = packet.payload();
        assertEquals(255, payload.length);
        assertEquals(7, Byte.toUnsignedInt(payload[0]));
        assertEquals(27, Byte.toUnsignedInt(payload[1]));
        for (int slot = 0; slot < 27; slot++) {
            int offset = 2 + slot * 9;
            assertArrayEquals(new byte[8], Arrays.copyOfRange(payload, offset, offset + 8));
        }
        assertEquals(7, Byte.toUnsignedInt(payload[245]));
        assertEquals(0, Byte.toUnsignedInt(payload[246]));
        assertArrayEquals(new byte[8], Arrays.copyOfRange(payload, 247, 255));
    }

    @Test
    void encodesSinglePlayerSlotWithRequiredNetIdPrefix() {
        PlayerInventory inventory = new PlayerInventory();
        inventory.setSlot(5, new ItemStack("nexora:arcane_shard", 3, 64));
        BedrockPacket packet = BedrockInventoryContentCodec.createPlayerInventorySlot(
                inventory, 5, ignored -> new BedrockInventoryContentCodec.ItemDefinition(257, 0, 0, new byte[0]));
        assertEquals(0x32, packet.packetId());
        assertArrayEquals(new byte[] {0, 5, 0, 0, 1, 1, 1, 3, 0, 0, 0, 0, 0}, packet.payload());
    }

    @Test
    void rejectsUnknownItemsAndInvalidContainerWindows() {
        PlayerInventory player = new PlayerInventory();
        player.setSlot(0, new ItemStack("minecraft:iron_ingot", 1, 64));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockInventoryContentCodec.createPlayerInventory(player, ignored -> null));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockInventoryContentCodec.createContainerInventory(101,
                        new ContainerInventory(), ignored -> null));
    }
}
