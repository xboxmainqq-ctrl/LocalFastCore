package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import ru.nexora.inventory.ItemStack;
import ru.nexora.inventory.PlayerEquipment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BedrockPlayerEquipmentCodecTest {
    private static final BedrockItemRegistry REGISTRY = BedrockVanillaItemCatalog.createRegistry();

    @Test
    void encodesArmorContainerWithFourSlotsAndProtocolMetadata() {
        PlayerEquipment equipment = new PlayerEquipment();
        equipment.setArmor(0, new ItemStack("minecraft:diamond", 1, 64));
        List<BedrockPacket> packets = BedrockPlayerEquipmentCodec.createInitialEquipment(equipment, REGISTRY);
        BedrockPacket armor = packets.get(0);
        assertEquals(0x31, armor.packetId());
        byte[] payload = armor.payload();
        assertEquals(48, payload.length); // 2-byte header + four 8-byte descriptors + name + storage descriptor
        assertEquals(120, payload[0] & 0xff);
        assertEquals(4, payload[1] & 0xff);
        assertEquals(50, payload[2] & 0xff); // runtime ID 306, little-endian
        assertEquals(1, payload[3] & 0xff);
        assertEquals(1, payload[4] & 0xff); // count; no ItemStackNetId prefix in InventoryContent
        assertEquals(6, payload[payload.length - 14] & 0xff); // ARMOR full container name
        assertEquals(1, payload[payload.length - 13] & 0xff); // dynamic ID present
        assertEquals(120, payload[payload.length - 12] & 0xff);
    }

    @Test
    void encodesOffhandAndCursorWithTheirProtocolContainerIds() {
        PlayerEquipment equipment = new PlayerEquipment();
        equipment.setOffhand(new ItemStack("minecraft:ender_pearl", 2, 16));
        equipment.setCursor(new ItemStack("minecraft:bread", 1, 64));
        List<BedrockPacket> packets = BedrockPlayerEquipmentCodec.createInitialEquipment(equipment, REGISTRY);

        byte[] offhand = packets.get(1).payload();
        assertEquals(24, offhand.length); // header + one descriptor + full name + storage descriptor
        assertEquals(119, offhand[0] & 0xff);
        assertEquals(1, offhand[1] & 0xff);
        assertEquals(2, offhand[4] & 0xff); // count follows runtime ID directly; no NetId prefix
        assertEquals(0, offhand[5] & 0xff);
        assertEquals(34, offhand[offhand.length - 14] & 0xff); // OFFHAND
        assertEquals(1, offhand[offhand.length - 13] & 0xff); // dynamic ID optional is present
        assertEquals(0, offhand[offhand.length - 12] & 0xff); // dynamic ID = 0

        BedrockPacket cursor = packets.get(2);
        assertEquals(0x32, cursor.packetId());
        byte[] cursorPayload = cursor.payload();
        assertEquals(124, cursorPayload[0] & 0xff); // UI container
        assertEquals(0, cursorPayload[1] & 0xff);   // cursor slot 0
        assertEquals(0, cursorPayload[2] & 0xff);   // optional FullContainerName absent
        assertEquals(0, cursorPayload[3] & 0xff);   // optional StorageItem absent
        assertEquals(5, cursorPayload[4] & 0xff);   // bread runtime ID 261 (little-endian low byte)
    }

    @Test
    void rejectsUnknownEquipmentItemInsteadOfGuessingRuntimeId() {
        PlayerEquipment equipment = new PlayerEquipment();
        equipment.setArmor(1, new ItemStack("nexora:unregistered_helmet", 1, 1));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockPlayerEquipmentCodec.createInitialEquipment(equipment, REGISTRY));
    }
}
