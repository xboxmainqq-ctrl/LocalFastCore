package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import ru.nexora.inventory.ItemStack;
import ru.nexora.inventory.ContainerInventory;
import ru.nexora.inventory.PlayerInventory;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class BedrockInventoryContentCodecTest {
    @Test
    void encodesAllThirtySixEmptyPlayerInventorySlots() {
        BedrockPacket packet = BedrockInventoryContentCodec.createPlayerInventory(
                new PlayerInventory(), ignored -> fail("Empty inventory must not resolve item IDs"));

        assertEquals(0x31, packet.packetId());
        byte[] payload = packet.payload();
        assertEquals(304, payload.length);
        assertEquals(0, Byte.toUnsignedInt(payload[0])); // Inventory container ID
        assertEquals(36, Byte.toUnsignedInt(payload[1]));

        for (int slot = 0; slot < 36; slot++) {
            int offset = 2 + slot * 8;
            assertArrayEquals(new byte[8], Arrays.copyOfRange(payload, offset, offset + 8),
                    "slot " + slot + " must be encoded as empty with net ID zero");
        }
        assertEquals(12, Byte.toUnsignedInt(payload[payload.length - 14])); // HOTBAR_AND_INVENTORY
        assertEquals(1, Byte.toUnsignedInt(payload[payload.length - 13])); // dynamic ID present
        assertEquals(0, Byte.toUnsignedInt(payload[payload.length - 12])); // dynamic ID = 0
        assertArrayEquals(new byte[8], Arrays.copyOfRange(payload, payload.length - 8, payload.length)); // empty storage item
    }

    @Test
    void encodesNonEmptySlotUsingResolvedProtocolItemDefinition() {
        PlayerInventory inventory = new PlayerInventory();
        inventory.setSlot(0, new ItemStack("nexora:arcane_shard", 3, 64));

        BedrockPacket packet = BedrockInventoryContentCodec.createPlayerInventory(
                inventory, itemId -> {
                    assertEquals("nexora:arcane_shard", itemId);
                    return new BedrockInventoryContentCodec.ItemDefinition(257, 0, 0, new byte[0]);
                });

        byte[] payload = packet.payload();
        assertArrayEquals(new byte[] {
                1, 1,    // legacy item ID 257
                3, 0,    // stack size 3
                0,       // aux value
                0,       // has StackNetId = false
                0,       // block runtime ID
                0        // empty user data buffer
        }, Arrays.copyOfRange(payload, 2, 10));
        assertArrayEquals(new byte[8], Arrays.copyOfRange(payload, 10, 18));
    }

    @Test
    void encodesBlockRuntimeIdAuxValueAndUserDataFromDefinition() {
        PlayerInventory inventory = new PlayerInventory();
        inventory.setSlot(0, new ItemStack("nexora:runed_block", 2, 64));
        byte[] userData = {1, 2};

        BedrockPacket packet = BedrockInventoryContentCodec.createPlayerInventory(
                inventory, ignored -> new BedrockInventoryContentCodec.ItemDefinition(
                        258, 2, 1234, userData));

        userData[0] = 99; // ItemDefinition must own a defensive copy.
        assertArrayEquals(new byte[] {
                2, 1,          // runtime item ID 258
                2, 0,          // stack size
                2,             // aux value
                0,             // has StackNetId = false
                (byte) 0xd2, 9,// block runtime ID 1234
                2, 1, 2        // user data string
        }, Arrays.copyOfRange(packet.payload(), 2, 13));
    }

    @Test
    void includesPerStackEnchantmentsAndRepairCostInInventoryDescriptor() {
        PlayerInventory inventory = new PlayerInventory();
        inventory.setSlot(0, new ItemStack("minecraft:diamond_sword", 1, 1,
                java.util.Map.of("minecraft:sharpness", 5), 3));

        BedrockPacket packet = BedrockInventoryContentCodec.createPlayerInventory(
                inventory, ignored -> new BedrockInventoryContentCodec.ItemDefinition(276, 0, 0, new byte[0]));

        byte[] payload = packet.payload();
        int offset = 2 + 2 + 2 + 1 + 1 + 1;
        int userDataLength = Byte.toUnsignedInt(payload[offset++]);
        byte[] userData = Arrays.copyOfRange(payload, offset, offset + userDataLength);
        var metadata = BedrockItemStackUserDataCodec.decode(userData, new byte[0]);

        assertEquals(java.util.Map.of("minecraft:sharpness", 5), metadata.enchantments());
        assertEquals(3, metadata.repairCost());
    }

    @Test
    void encodesPlayerUiCraftingGridWithCraftingInputContainerName() {
        ContainerInventory ui = new ContainerInventory(BedrockInventoryContentCodec.PLAYER_UI_SIZE);
        ui.setSlot(28, new ItemStack("minecraft:oak_planks", 2, 64));
        BedrockPacket packet = BedrockInventoryContentCodec.createPlayerUiInventory(
                ui, itemId -> {
                    assertEquals("minecraft:oak_planks", itemId);
                    return new BedrockInventoryContentCodec.ItemDefinition(5, 0, 0x37abcd4fL, new byte[0]);
                });

        assertEquals(0x31, packet.packetId());
        byte[] payload = packet.payload();
        assertEquals(124, Byte.toUnsignedInt(payload[0])); // UI container
        assertEquals(51, Byte.toUnsignedInt(payload[1]));
        assertArrayEquals(new byte[] {
                5, 0, 2, 0, 0, 0,
                (byte) 0xcf, (byte) 0x9a, (byte) 0xaf, (byte) 0xbd, 3, 0
        }, Arrays.copyOfRange(payload, 2 + 28 * 8, 2 + 28 * 8 + 12));
        assertEquals(13, Byte.toUnsignedInt(payload[payload.length - 10])); // CRAFTING_INPUT
        assertEquals(0, Byte.toUnsignedInt(payload[payload.length - 9])); // no dynamic ID
        assertArrayEquals(new byte[8], Arrays.copyOfRange(payload, payload.length - 8, payload.length));
    }

    @Test
    void encodesTwentySevenEmptyContainerSlotsWithLevelEntityName() {
        BedrockPacket packet = BedrockInventoryContentCodec.createContainerInventory(
                7, new ContainerInventory(), ignored -> fail("Empty chest must not resolve item IDs"));

        assertEquals(0x31, packet.packetId());
        byte[] payload = packet.payload();
        assertEquals(228, payload.length);
        assertEquals(7, Byte.toUnsignedInt(payload[0])); // window/container ID
        assertEquals(27, Byte.toUnsignedInt(payload[1]));
        for (int slot = 0; slot < 27; slot++) {
            int offset = 2 + slot * 9;
            assertArrayEquals(new byte[8], Arrays.copyOfRange(payload, offset, offset + 8),
                    "chest slot " + slot + " must be empty");
        }
        assertEquals(7, Byte.toUnsignedInt(payload[payload.length - 10])); // LEVEL_ENTITY
        assertEquals(0, Byte.toUnsignedInt(payload[payload.length - 9])); // no dynamic ID
        assertArrayEquals(new byte[8], Arrays.copyOfRange(payload, payload.length - 8, payload.length));
    }

    @Test
    void encodesContainerItemsUsingResolvedBedrockDefinitions() {
        ContainerInventory chest = new ContainerInventory();
        chest.setSlot(2, new ItemStack("minecraft:iron_ingot", 4, 64));

        BedrockPacket packet = BedrockInventoryContentCodec.createContainerInventory(
                11, chest, itemId -> {
                    assertEquals("minecraft:iron_ingot", itemId);
                    return new BedrockInventoryContentCodec.ItemDefinition(265, 0, 0, new byte[0]);
                });

        byte[] payload = packet.payload();
        assertEquals(11, Byte.toUnsignedInt(payload[0]));
        int offset = 2 + 2 * 9;
        assertArrayEquals(new byte[] {9, 1, 4, 0, 0, 0, 0, 0},
                Arrays.copyOfRange(payload, offset, offset + 8));
    }

    @Test
    void rejectsInvalidContainerWindowIdsAndUnregisteredItems() {
        assertThrows(IllegalArgumentException.class, () ->
                BedrockInventoryContentCodec.createContainerInventory(
                        101, new ContainerInventory(), ignored -> null));
        ContainerInventory chest = new ContainerInventory();
        chest.setSlot(0, new ItemStack("minecraft:iron_ingot", 1, 64));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockInventoryContentCodec.createContainerInventory(1, chest, ignored -> null));
    }

    @Test
    void encodesSingleEmptyPlayerInventorySlot() {
        BedrockPacket packet = BedrockInventoryContentCodec.createPlayerInventorySlot(
                new PlayerInventory(), 0, ignored -> fail("Empty slot must not resolve item IDs"));

        assertEquals(0x32, packet.packetId());
        assertArrayEquals(new byte[] {
                0, 0, 0, 0, // container, slot, no optional FullContainerName, no StorageItem
                0, 0, 0, 0, 0, 0, 0, 0 // empty item descriptor
        }, packet.payload());
    }

    @Test
    void encodesSingleNonEmptyPlayerInventorySlot() {
        PlayerInventory inventory = new PlayerInventory();
        inventory.setSlot(5, new ItemStack("nexora:arcane_shard", 3, 64));

        BedrockPacket packet = BedrockInventoryContentCodec.createPlayerInventorySlot(
                inventory, 5, ignored -> new BedrockInventoryContentCodec.ItemDefinition(
                        257, 0, 0, new byte[0]));

        assertEquals(0x32, packet.packetId());
        assertArrayEquals(new byte[] {
                0, 5, 0, 0, // container, slot, optional FullContainerName absent, StorageItem absent
                1, 1,       // runtime item ID 257
                3, 0,       // stack size
                0,          // aux
                0,          // has StackNetId = false
                0, 0        // block runtime ID, empty user data
        }, packet.payload());
    }

    @Test
    void slotEncoderRejectsOutOfRangeSlotsAndUnknownItems() {
        PlayerInventory inventory = new PlayerInventory();
        assertThrows(IndexOutOfBoundsException.class,
                () -> BedrockInventoryContentCodec.createPlayerInventorySlot(inventory, 36, ignored -> null));
        inventory.setSlot(0, new ItemStack("nexora:arcane_shard", 1, 64));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockInventoryContentCodec.createPlayerInventorySlot(inventory, 0, ignored -> null));
    }

    @Test
    void rejectsMissingOrInvalidBedrockItemDefinitions() {
        PlayerInventory inventory = new PlayerInventory();
        inventory.setSlot(0, new ItemStack("nexora:arcane_shard", 1, 64));

        assertThrows(IllegalArgumentException.class,
                () -> BedrockInventoryContentCodec.createPlayerInventory(inventory, ignored -> null));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockInventoryContentCodec.createPlayerInventory(inventory,
                        ignored -> new BedrockInventoryContentCodec.ItemDefinition(0, 0, 0, new byte[0])));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockInventoryContentCodec.createPlayerInventory(inventory,
                        ignored -> new BedrockInventoryContentCodec.ItemDefinition(40_000, 0, 0, new byte[0])));
    }
}
