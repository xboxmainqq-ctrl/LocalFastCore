package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BedrockVanillaItemCatalogTest {
    @Test
    void registersOnlyVerifiedProtocol2225StackableItems() {
        BedrockItemRegistry registry = BedrockVanillaItemCatalog.createRegistry();
        assertTrue(registry.size() >= 60);

        assertEquals(306, registry.findByIdentifier("minecraft:diamond")
                .networkDefinition().runtimeItemId());
        assertEquals(425, registry.findByIdentifier("minecraft:ender_pearl")
                .networkDefinition().runtimeItemId());
        assertEquals(16, registry.findByIdentifier("minecraft:ender_pearl").maxStackSize());
        assertEquals(64, registry.findByIdentifier("minecraft:iron_ingot").maxStackSize());
        assertEquals(884, registry.findByIdentifier("minecraft:glow_berries")
                .networkDefinition().runtimeItemId());
        assertEquals(346, registry.findByIdentifier("minecraft:iron_helmet")
                .networkDefinition().runtimeItemId());
        assertEquals(347, registry.findByIdentifier("minecraft:iron_chestplate")
                .networkDefinition().runtimeItemId());
        assertEquals(1, registry.findByIdentifier("minecraft:iron_chestplate").maxStackSize());
        assertEquals(358, registry.findByIdentifier("minecraft:shield")
                .networkDefinition().runtimeItemId());

        assertNull(registry.findByIdentifier("minecraft:air"));
        assertNull(registry.findByIdentifier("minecraft:apple")); // component-based; not yet catalogued
        assertNull(registry.findByIdentifier("minecraft:stone")); // block-state item; not yet catalogued

        for (BedrockItemRegistry.Entry entry : registry.entries()) {
            assertFalse(entry.componentBased());
            assertEquals(4, entry.componentData().length);
        }
    }

    @Test
    void createsAProtocolRegistryPacketFromTheProductionCatalog() {
        BedrockPacket packet = BedrockItemRegistryPacketCodec.createPacket(
                BedrockVanillaItemCatalog.createRegistry());
        assertEquals(BedrockItemRegistryPacketCodec.ITEM_REGISTRY_PACKET_ID, packet.packetId());
        assertTrue(packet.payload().length > 500);
        assertTrue(packet.payload().length < 16_384);
    }
}
