package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import ru.nexora.inventory.ItemStack;
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
        assertEquals(291, payload.length);
        assertEquals(0, Byte.toUnsignedInt(payload[0])); // Inventory container ID
        assertEquals(36, Byte.toUnsignedInt(payload[1]));

        for (int slot = 0; slot < 36; slot++) {
            int offset = 2 + slot * 8;
            assertArrayEquals(new byte[8], Arrays.copyOfRange(payload, offset, offset + 8),
                    "slot " + slot + " must be encoded as empty with net ID zero");
        }
        assertEquals(29, Byte.toUnsignedInt(payload[payload.length - 1]));
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
                2,       // VarInt32(1): non-empty ItemStackNetId
                1, 1,    // legacy item ID 257
                3, 0,    // stack size 3
                0,       // aux value
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
                2,             // ItemStackNetId = 1
                2, 1,          // legacy item ID 258
                2, 0,          // stack size
                2,             // aux value
                (byte) 0xd2, 9,// block runtime ID 1234
                2, 1, 2        // user data string
        }, Arrays.copyOfRange(packet.payload(), 2, 13));
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
