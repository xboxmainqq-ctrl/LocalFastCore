package ru.nexora.network.bedrock;

import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    void productionCatalogPacketCanBeDecodedEntryByEntry() throws IOException {
        BedrockItemRegistry registry = BedrockVanillaItemCatalog.createRegistry();
        BedrockPacket packet = BedrockItemRegistryPacketCodec.createPacket(registry);
        ByteArrayInputStream input = new ByteArrayInputStream(packet.payload());

        int count = readVarUInt32(input);
        assertEquals(2_078, count);

        try (NBTInputStream nbtReader = NbtUtils.createNetworkReader(input)) {
            for (int index = 0; index < count; index++) {
                BedrockItemRegistry.Entry expected = registry.entries().get(index);
                String identifier = readString(input);
                int runtimeId = readShortLE(input);
                boolean componentBased = readBoolean(input);
                int itemVersion = readVarInt32(input);
                Object decodedTag = nbtReader.readTag();
                assertInstanceOf(NbtMap.class, decodedTag,
                        "network NBT must decode for " + expected.namespacedId());
                NbtMap tag = (NbtMap) decodedTag;

                assertEquals(expected.namespacedId(), identifier);
                assertEquals(expected.networkDefinition().runtimeItemId(), runtimeId);
                assertEquals(expected.componentBased(), componentBased);
                assertEquals(expected.itemVersion(), itemVersion);
                if (expected.componentData().length > 3) {
                    assertTrue(tag.containsKey("components"),
                            "component wrapper must be present for " + identifier);
                } else {
                    assertTrue(tag.isEmpty(), "empty item component tag expected for " + identifier);
                }
            }
        }
        assertEquals(0, input.available(), "ItemRegistry payload must have no trailing bytes");
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
                new BedrockInventoryContentCodec.ItemDefinition(859, 0, 0, new byte[0]),
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

    private static int readVarUInt32(ByteArrayInputStream input) throws IOException {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int next = input.read();
            if (next < 0) throw new IOException("Unexpected end of ItemRegistry payload");
            if (shift == 28 && (next & 0xf0) != 0) throw new IOException("VarUInt32 overflow");
            result |= (next & 0x7f) << shift;
            if ((next & 0x80) == 0) return result;
        }
        throw new IOException("Malformed VarUInt32");
    }

    private static int readVarInt32(ByteArrayInputStream input) throws IOException {
        int encoded = readVarUInt32(input);
        return (encoded >>> 1) ^ -(encoded & 1);
    }

    private static String readString(ByteArrayInputStream input) throws IOException {
        int length = readVarUInt32(input);
        byte[] value = input.readNBytes(length);
        if (value.length != length) throw new IOException("Truncated item identifier");
        return new String(value, StandardCharsets.UTF_8);
    }

    private static int readShortLE(ByteArrayInputStream input) throws IOException {
        int low = input.read();
        int high = input.read();
        if ((low | high) < 0) throw new IOException("Truncated runtime item ID");
        return (short) (low | (high << 8));
    }

    private static boolean readBoolean(ByteArrayInputStream input) throws IOException {
        int value = input.read();
        if (value != 0 && value != 1) throw new IOException("Invalid boolean in ItemRegistry: " + value);
        return value == 1;
    }

    private static BedrockItemRegistry.Entry entry(String id, int runtimeId) {
        return new BedrockItemRegistry.Entry(id,
                new BedrockInventoryContentCodec.ItemDefinition(runtimeId, 0, 0, new byte[0]));
    }
}
