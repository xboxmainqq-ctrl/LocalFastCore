package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NexoraInventorySyncCodecTest {
    @Test
    void roundTripsPlayerInventorySnapshot() {
        UUID player = UUID.randomUUID();
        PlayerInventory inventory = new PlayerInventory(player);
        for (int i = 0; i < 65; i++) assertTrue(inventory.addOne("minecraft:apple"));
        assertTrue(inventory.addOne("minecraft:diamond"));

        NexoraInventorySyncCodec.InventoryState decoded =
                NexoraInventorySyncCodec.decode(ByteBuffer.wrap(NexoraInventorySyncCodec.encode(inventory)));

        assertEquals(player, decoded.playerId());
        assertEquals(2, decoded.stacks().size());
        assertEquals(new NexoraInventorySyncCodec.InventoryEntry(0,
                new ItemStack("minecraft:apple", 65)), decoded.stacks().get(0));
        assertEquals(new NexoraInventorySyncCodec.InventoryEntry(1,
                new ItemStack("minecraft:diamond", 1)), decoded.stacks().get(1));
    }

    @Test
    void preservesEmptySlotIndexesInsteadOfCompactingStacks() {
        PlayerInventory inventory = new PlayerInventory(UUID.randomUUID());
        assertTrue(inventory.addOne("minecraft:apple"));
        assertTrue(inventory.addOne("minecraft:diamond"));
        assertNotNull(inventory.removeStack(0));

        NexoraInventorySyncCodec.InventoryState decoded =
                NexoraInventorySyncCodec.decode(ByteBuffer.wrap(NexoraInventorySyncCodec.encode(inventory)));

        assertEquals(1, decoded.stacks().size());
        assertEquals(1, decoded.stacks().get(0).slot());
        assertEquals(new ItemStack("minecraft:diamond", 1), decoded.stacks().get(0).stack());
    }

    @Test
    void movesAndMergesStacksWithoutExceedingTheStackLimit() {
        PlayerInventory inventory = new PlayerInventory(UUID.randomUUID());
        for (int i = 0; i < 100; i++) assertTrue(inventory.addOne("minecraft:apple"));
        assertNotNull(inventory.removeOne(0));
        assertTrue(inventory.moveOrSwap(1, 0));

        assertEquals(new ItemStack("minecraft:apple", 99), inventory.stackAt(0));
        assertNull(inventory.stackAt(1));
    }

    @Test
    void rejectsTruncatedOrTrailingData() {
        PlayerInventory inventory = new PlayerInventory(UUID.randomUUID());
        inventory.addOne("minecraft:stone");
        byte[] packet = NexoraInventorySyncCodec.encode(inventory);
        assertThrows(IllegalArgumentException.class,
                () -> NexoraInventorySyncCodec.decode(ByteBuffer.wrap(packet, 0, packet.length - 1)));
        byte[] trailing = java.util.Arrays.copyOf(packet, packet.length + 1);
        assertThrows(IllegalArgumentException.class,
                () -> NexoraInventorySyncCodec.decode(ByteBuffer.wrap(trailing)));
    }
}
