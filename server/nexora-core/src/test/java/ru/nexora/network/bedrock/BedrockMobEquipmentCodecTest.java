package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import ru.nexora.inventory.ItemStack;
import ru.nexora.inventory.PlayerInventory;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BedrockMobEquipmentCodecTest {
    private static final BedrockItemRegistry REGISTRY = BedrockVanillaItemCatalog.createRegistry();

    @Test
    void encodesAndDecodesSelectedPlayerHotbarItem() {
        PlayerInventory inventory = new PlayerInventory();
        inventory.setSlot(2, new ItemStack("minecraft:diamond", 3, 64));
        inventory.selectHotbarSlot(2);

        BedrockPacket packet = BedrockMobEquipmentCodec.createPlayerEquipment(123, inventory, REGISTRY);
        assertEquals(0x1f, packet.packetId());
        assertArrayEquals(new byte[] {
                123,             // entity runtime ID
                50, 1,           // diamond runtime ID 306
                3, 0,            // stack count
                0,               // aux
                0,               // has StackNetId
                0,               // block runtime ID
                0,               // user data length
                2, 2, 0          // inventory slot, selected hotbar slot, player container
        }, packet.payload());

        var decoded = BedrockMobEquipmentCodec.decode(packet, REGISTRY);
        assertEquals(123, decoded.runtimeEntityId());
        assertEquals(2, decoded.inventorySlot());
        assertEquals(2, decoded.hotbarSlot());
        assertEquals(0, decoded.containerId());
        assertEquals(new ItemStack("minecraft:diamond", 3, 64), decoded.item().orElseThrow());
    }

    @Test
    void roundTripsEnchantedHeldItemThroughMobEquipmentPacket() {
        BedrockItemRegistry registry = new BedrockItemRegistry(List.of(
                new BedrockItemRegistry.Entry("minecraft:diamond_sword",
                        new BedrockInventoryContentCodec.ItemDefinition(276, 0, 0, new byte[0]), 1)));
        PlayerInventory inventory = new PlayerInventory();
        ItemStack enchanted = new ItemStack("minecraft:diamond_sword", 1, 1,
                java.util.Map.of("minecraft:sharpness", 4), 5);
        inventory.setSlot(0, enchanted);

        BedrockPacket packet = BedrockMobEquipmentCodec.createPlayerEquipment(55, inventory, registry);
        var decoded = BedrockMobEquipmentCodec.decode(packet, registry);

        assertEquals(enchanted, decoded.item().orElseThrow());
    }

    @Test
    void encodesAnEmptySelectedHotbarSlot() {
        PlayerInventory inventory = new PlayerInventory();
        inventory.selectHotbarSlot(4);
        BedrockPacket packet = BedrockMobEquipmentCodec.createPlayerEquipment(9, inventory, REGISTRY);
        var decoded = BedrockMobEquipmentCodec.decode(packet, REGISTRY);
        assertTrue(decoded.item().isEmpty());
        assertEquals(4, decoded.inventorySlot());
        assertEquals(4, decoded.hotbarSlot());
    }

    @Test
    void rejectsUnknownItemsForgedDefinitionsAndMalformedPackets() {
        byte[] unknown = payload(123, 999, 1, 0, 0, 0, 0, 0);
        assertThrows(IllegalArgumentException.class, () ->
                BedrockMobEquipmentCodec.decode(new BedrockPacket(0x1f, 0, 0, unknown), REGISTRY));

        byte[] malformedBool = payload(123, 306, 1, 0, 2, 0, 0, 0);
        assertThrows(IllegalArgumentException.class, () ->
                BedrockMobEquipmentCodec.decode(new BedrockPacket(0x1f, 0, 0, malformedBool), REGISTRY));

        byte[] trailing = java.util.Arrays.copyOf(payload(123, 306, 1, 0, 0, 0, 0, 0),
                payload(123, 306, 1, 0, 0, 0, 0, 0).length + 1);
        assertThrows(IllegalArgumentException.class, () ->
                BedrockMobEquipmentCodec.decode(new BedrockPacket(0x1f, 0, 0, trailing), REGISTRY));

        assertThrows(IllegalArgumentException.class, () ->
                BedrockMobEquipmentCodec.decode(new BedrockPacket(0x1e, 0, 0, new byte[0]), REGISTRY));
    }

    private static byte[] payload(long entityId, int runtimeId, int count,
                                  int aux, int hasNetId, int blockRuntimeId,
                                  int userDataLength, int slot) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((int) entityId);
        out.write(runtimeId & 0xff);
        out.write((runtimeId >>> 8) & 0xff);
        out.write(count & 0xff);
        out.write((count >>> 8) & 0xff);
        out.write(aux);
        out.write(hasNetId);
        out.write(blockRuntimeId);
        out.write(userDataLength);
        out.write(slot);
        out.write(slot);
        out.write(0);
        return out.toByteArray();
    }
}
