package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BedrockVanillaItemCatalogTest {
    @Test
    void registersOnlyItemsWithCompleteProtocol2225ClientDefinitions() throws IOException {
        BedrockItemRegistry registry = BedrockVanillaItemCatalog.createRegistry();
        assertTrue(registry.size() >= 60);

        assertEquals(61, registry.findByIdentifier("minecraft:furnace").networkDefinition().runtimeItemId());
        assertEquals(-196, registry.findByIdentifier("minecraft:blast_furnace").networkDefinition().runtimeItemId());
        assertEquals(-198, registry.findByIdentifier("minecraft:smoker").networkDefinition().runtimeItemId());
        assertEquals(Integer.toUnsignedLong(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.BLAST_FURNACE)),
                registry.findByIdentifier("minecraft:blast_furnace").networkDefinition().blockRuntimeId());
        assertEquals(Integer.toUnsignedLong(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.SMOKER)),
                registry.findByIdentifier("minecraft:smoker").networkDefinition().blockRuntimeId());
        assertEquals(Integer.toUnsignedLong(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.FURNACE)),
                registry.findByIdentifier("minecraft:furnace").networkDefinition().blockRuntimeId());
        assertEquals(306, registry.findByIdentifier("minecraft:diamond")
                .networkDefinition().runtimeItemId());
        assertEquals(425, registry.findByIdentifier("minecraft:ender_pearl")
                .networkDefinition().runtimeItemId());
        assertEquals(277, registry.findByIdentifier("minecraft:rotten_flesh")
                .networkDefinition().runtimeItemId());
        assertEquals(418, registry.findByIdentifier("minecraft:bone")
                .networkDefinition().runtimeItemId());
        assertEquals(16, registry.findByIdentifier("minecraft:ender_pearl").maxStackSize());
        assertEquals(64, registry.findByIdentifier("minecraft:iron_ingot").maxStackSize());
        assertEquals(289, registry.findByIdentifier("minecraft:cooked_rabbit").networkDefinition().runtimeItemId());
        assertEquals(560, registry.findByIdentifier("minecraft:mutton").networkDefinition().runtimeItemId());
        assertEquals(561, registry.findByIdentifier("minecraft:cooked_mutton").networkDefinition().runtimeItemId());
        assertEquals(887, registry.findByIdentifier("minecraft:glow_berries")
                .networkDefinition().runtimeItemId());
        assertEquals(0, registry.findByIdentifier("minecraft:glow_berries").itemVersion());
        assertFalse(registry.findByIdentifier("minecraft:glow_berries").componentBased());
        assertTrue(registry.findByIdentifier("minecraft:glow_berries").componentData().length > 3);
        assertTrue(readNetworkNbt(registry.findByIdentifier("minecraft:glow_berries"))
                .getCompound("components").containsKey("minecraft:food"));
        assertTrue(registry.findByIdentifier("minecraft:bread").componentData().length > 3);
        assertTrue(readNetworkNbt(registry.findByIdentifier("minecraft:bread"))
                .getCompound("components").containsKey("minecraft:food"));
        assertEquals(2, registry.findByIdentifier("minecraft:diamond").itemVersion());
        assertEquals(346, registry.findByIdentifier("minecraft:iron_helmet")
                .networkDefinition().runtimeItemId());
        assertEquals(2, registry.findByIdentifier("minecraft:iron_helmet").itemVersion());
        assertEquals(347, registry.findByIdentifier("minecraft:iron_chestplate")
                .networkDefinition().runtimeItemId());
        assertEquals(1, registry.findByIdentifier("minecraft:iron_chestplate").maxStackSize());
        assertEquals(358, registry.findByIdentifier("minecraft:shield")
                .networkDefinition().runtimeItemId());
        assertEquals(1, registry.findByIdentifier("minecraft:stone").networkDefinition().runtimeItemId());
        assertEquals(Integer.toUnsignedLong(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.STONE)),
                registry.findByIdentifier("minecraft:stone").networkDefinition().blockRuntimeId());
        assertEquals(2, registry.findByIdentifier("minecraft:grass_block").networkDefinition().runtimeItemId());
        assertEquals(3, registry.findByIdentifier("minecraft:dirt").networkDefinition().runtimeItemId());
        assertEquals(4, registry.findByIdentifier("minecraft:cobblestone").networkDefinition().runtimeItemId());
        assertEquals(13, registry.findByIdentifier("minecraft:gravel").networkDefinition().runtimeItemId());
        assertEquals(20, registry.findByIdentifier("minecraft:glass").networkDefinition().runtimeItemId());
        assertEquals(49, registry.findByIdentifier("minecraft:obsidian").networkDefinition().runtimeItemId());
        assertEquals(87, registry.findByIdentifier("minecraft:netherrack").networkDefinition().runtimeItemId());
        assertEquals(121, registry.findByIdentifier("minecraft:end_stone").networkDefinition().runtimeItemId());
        assertEquals(173, registry.findByIdentifier("minecraft:coal_block").networkDefinition().runtimeItemId());
        assertEquals(42, registry.findByIdentifier("minecraft:iron_block").networkDefinition().runtimeItemId());
        assertEquals(41, registry.findByIdentifier("minecraft:gold_block").networkDefinition().runtimeItemId());
        assertEquals(57, registry.findByIdentifier("minecraft:diamond_block").networkDefinition().runtimeItemId());
        assertEquals(133, registry.findByIdentifier("minecraft:emerald_block").networkDefinition().runtimeItemId());
        assertEquals(54, registry.findByIdentifier("minecraft:chest").networkDefinition().runtimeItemId());
        assertEquals(Integer.toUnsignedLong(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.CHEST)),
                registry.findByIdentifier("minecraft:chest").networkDefinition().blockRuntimeId());
        assertEquals(17, registry.findByIdentifier("minecraft:oak_log").networkDefinition().runtimeItemId());
        assertEquals(Integer.toUnsignedLong(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.OAK_LOG)),
                registry.findByIdentifier("minecraft:oak_log").networkDefinition().blockRuntimeId());
        assertEquals(5, registry.findByIdentifier("minecraft:oak_planks").networkDefinition().runtimeItemId());
        assertEquals(Integer.toUnsignedLong(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.OAK_PLANKS)),
                registry.findByIdentifier("minecraft:oak_planks").networkDefinition().blockRuntimeId());
        assertEquals(58, registry.findByIdentifier("minecraft:crafting_table").networkDefinition().runtimeItemId());
        assertEquals(Integer.toUnsignedLong(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.CRAFTING_TABLE)),
                registry.findByIdentifier("minecraft:crafting_table").networkDefinition().blockRuntimeId());
        assertEquals(Integer.toUnsignedLong(BedrockBlockRuntimeIds.forBlock(ru.nexora.world.BlockType.COBBLESTONE)),
                registry.findByIdentifier("minecraft:cobblestone").networkDefinition().blockRuntimeId());

        assertNull(registry.findByIdentifier("minecraft:air"));
        BedrockItemRegistry.Entry coal = registry.findByIdentifier("minecraft:coal");
        assertNotNull(coal);
        assertEquals(304, coal.networkDefinition().runtimeItemId());
        assertEquals(2, coal.itemVersion());
        assertTrue(coal.componentBased());
        assertEquals(16, coal.componentData().length);
        assertTrue(readNetworkNbt(coal).getCompound("components").isEmpty());
        assertNull(registry.findByIdentifier("minecraft:apple")); // component-based; not yet catalogued

        int[] spearIds = {858, 859, 860, 861, 862, 863, 864};
        String[] spearNames = {
                "copper_spear", "diamond_spear", "golden_spear", "iron_spear",
                "netherite_spear", "stone_spear", "wooden_spear"
        };
        for (int i = 0; i < spearNames.length; i++) {
            BedrockItemRegistry.Entry spear = registry.findByIdentifier("minecraft:" + spearNames[i]);
            assertNotNull(spear);
            assertEquals(spearIds[i], spear.networkDefinition().runtimeItemId());
            assertEquals(1, spear.itemVersion());
            assertTrue(spear.componentBased());
            assertEquals(1, spear.maxStackSize());
            assertTrue(spear.componentData().length > 1_000);
            NbtMap spearComponents = readNetworkNbt(spear).getCompound("components");
            assertTrue(spearComponents.containsKey("minecraft:kinetic_weapon"));
            assertTrue(spearComponents.containsKey("item_properties"));
        }

        for (BedrockItemRegistry.Entry entry : registry.entries()) {
            if (entry.componentData().length > 3) {
                assertTrue(readNetworkNbt(entry).containsKey("components"),
                        "Component NBT must be wrapped for " + entry.namespacedId());
            } else {
                assertFalse(entry.componentBased(),
                        "Component-based items must have their complete NBT: " + entry.namespacedId());
                assertArrayEquals(new byte[] {10, 0, 0}, entry.componentData());
            }
        }

    }

    private static NbtMap readNetworkNbt(BedrockItemRegistry.Entry entry) throws IOException {
        try (NBTInputStream reader = NbtUtils.createNetworkReader(
                new ByteArrayInputStream(entry.componentData()))) {
            Object tag = reader.readTag();
            assertInstanceOf(NbtMap.class, tag);
            return (NbtMap) tag;
        }
    }

    @Test
    void createsAProtocolRegistryPacketFromTheProductionCatalog() {
        BedrockPacket packet = BedrockItemRegistryPacketCodec.createPacket(
                BedrockVanillaItemCatalog.createRegistry());
        assertEquals(BedrockItemRegistryPacketCodec.ITEM_REGISTRY_PACKET_ID, packet.packetId());
        assertTrue(packet.payload().length > 500);
        assertTrue(packet.payload().length < 32_768);
    }
}
