package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BedrockItemRegistryPacketCodecTest {
    @Test
    void encodesEmptyRegistryAsAnEmptyVarUIntArray() {
        BedrockPacket packet = BedrockItemRegistryPacketCodec.createPacket(BedrockItemRegistry.empty());
        assertEquals(0xA2, packet.packetId());
        assertArrayEquals(new byte[] {0}, packet.payload());
    }

    @Test
    void encodesProtocol2225ItemDataFieldsInWireOrder() {
        BedrockItemRegistry registry = new BedrockItemRegistry(List.of(
                new BedrockItemRegistry.Entry(
                        "nexora:arcane_shard",
                        new BedrockInventoryContentCodec.ItemDefinition(257, 0, 0, new byte[0]),
                        64,
                        false,
                        2,
                        new byte[] {10, 0, 0}
                )
        ));

        BedrockPacket packet = BedrockItemRegistryPacketCodec.createPacket(registry);
        assertEquals(0xA2, packet.packetId());
        assertArrayEquals(new byte[] {
                1, // one ItemData
                19, // UTF-8 identifier length
                'n','e','x','o','r','a',':','a','r','c','a','n','e','_','s','h','a','r','d',
                1, 1, // runtime ID 257, little-endian int16
                0,    // not component-based
                4,    // ZigZag varint32(2): ItemVersion None
                10, 0, 0 // empty named network-NBT compound
        }, packet.payload());
    }

    @Test
    void copiesComponentDataAndRejectsInvalidRegistryMetadata() {
        byte[] nbt = new byte[] {10, 0, 3, 1, 120, 1, 0, 0, 0, 0};
        BedrockItemRegistry.Entry entry = new BedrockItemRegistry.Entry(
                "nexora:crystal",
                new BedrockInventoryContentCodec.ItemDefinition(300, 0, 0, new byte[0]),
                16, true, 1, nbt);
        nbt[0] = 0;
        assertEquals(10, entry.componentData()[0]);

        assertThrows(IllegalArgumentException.class, () -> new BedrockItemRegistry.Entry(
                "nexora:bad_version",
                new BedrockInventoryContentCodec.ItemDefinition(301, 0, 0, new byte[0]),
                64, false, 3, new byte[] {10, 0, 0}));
        assertThrows(IllegalArgumentException.class, () -> new BedrockItemRegistry.Entry(
                "nexora:missing_nbt",
                new BedrockInventoryContentCodec.ItemDefinition(302, 0, 0, new byte[0]),
                64, false, 2, new byte[0]));
    }

    @Test
    void rejectsComponentBasedItemsWithoutTheirClientComponentData() {
        assertThrows(IllegalArgumentException.class, () -> new BedrockItemRegistry.Entry(
                "minecraft:diamond_spear",
                new BedrockInventoryContentCodec.ItemDefinition(856, 0, 0, new byte[0]),
                1, true, 1, new byte[] {10, 0, 0}));
    }

    @Test
    void rejectsDuplicateRuntimeIdsAndOversizedRegistries() {
        assertThrows(IllegalArgumentException.class, () -> new BedrockItemRegistry(List.of(
                entry("nexora:first", 400),
                entry("nexora:second", 400)
        )));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockItemRegistryPacketCodec.createPacket(new BedrockItemRegistry(
                        java.util.Collections.nCopies(BedrockItemRegistryPacketCodec.MAX_ENTRIES + 1,
                                entry("nexora:oversized", 401)))));
    }

    private static BedrockItemRegistry.Entry entry(String id, int runtimeId) {
        return new BedrockItemRegistry.Entry(id,
                new BedrockInventoryContentCodec.ItemDefinition(runtimeId, 0, 0, new byte[0]));
    }
}
