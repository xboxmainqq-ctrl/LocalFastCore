package ru.nexora.runtime;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.v2225.Bedrock_v2225;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType;
import org.cloudburstmc.protocol.bedrock.data.inventory.FullContainerName;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemRegistryPacket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BedrockItemRegistryTest {

    @Test
    void loadsTheCompleteProtocol2225VanillaItemTable() {
        BedrockItemRegistry registry = BedrockItemRegistry.loadBundled();

        assertEquals(2080, registry.size());
        assertEquals(1, registry.definition("minecraft:stone").runtimeId());
        assertEquals(4, registry.definition("minecraft:cobblestone").runtimeId());
        assertEquals(3, registry.definition("minecraft:dirt").runtimeId());
        assertEquals(17, registry.definition("minecraft:oak_log").runtimeId());
        assertTrue(registry.definition("minecraft:coal").componentBased());
        assertFalse(registry.definition("minecraft:diamond").componentBased());

        ItemData coal = registry.itemData("minecraft:coal", 3);
        assertEquals(3, coal.getCount());
        assertEquals("minecraft:coal", coal.getDefinition().identifier());
    }

    @Test
    void serializesTheFullRegistryAndAPlayerInventoryWithProtocol2225Codec() throws Exception {
        BedrockItemRegistry registry = BedrockItemRegistry.loadBundled();
        ItemRegistryPacket itemRegistry = registry.createPacket();
        assertEquals(2080, itemRegistry.getItems().size());

        ByteBuf registryBytes = Unpooled.buffer();
        try {
            Bedrock_v2225.CODEC.tryEncode(
                    Bedrock_v2225.CODEC.createHelper(), registryBytes, itemRegistry);
            assertTrue(registryBytes.isReadable());
            assertEquals(162, Bedrock_v2225.CODEC
                    .getPacketDefinition(ItemRegistryPacket.class).id());
        } finally {
            registryBytes.release();
        }

        List<ItemData> slots = new ArrayList<>(PlayerInventory.DEFAULT_SLOTS);
        for (int i = 0; i < PlayerInventory.DEFAULT_SLOTS; i++) {
            slots.add(ItemData.AIR);
        }
        slots.set(0, registry.itemData("minecraft:oak_log", 4));
        slots.set(1, registry.itemData("minecraft:coal", 2));

        InventoryContentPacket content = new InventoryContentPacket();
        content.setContainerId(ContainerId.INVENTORY);
        content.setContents(slots);
        content.setContainerNameData(new FullContainerName(
                ContainerSlotType.HOTBAR_AND_INVENTORY, null));

        ByteBuf inventoryBytes = Unpooled.buffer();
        try {
            Bedrock_v2225.CODEC.tryEncode(
                    Bedrock_v2225.CODEC.createHelper(), inventoryBytes, content);
            assertTrue(inventoryBytes.isReadable());
            assertEquals(49, Bedrock_v2225.CODEC
                    .getPacketDefinition(InventoryContentPacket.class).id());
        } finally {
            inventoryBytes.release();
        }
    }
}
