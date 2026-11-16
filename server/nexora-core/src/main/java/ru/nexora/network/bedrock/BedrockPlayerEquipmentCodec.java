package ru.nexora.network.bedrock;

import ru.nexora.inventory.ItemStack;
import ru.nexora.inventory.PlayerEquipment;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Protocol-2225 synchronization for the local player's armor, offhand and cursor slots. */
public final class BedrockPlayerEquipmentCodec {
    public static final int ARMOR_CONTAINER_ID = 120;
    public static final int OFFHAND_CONTAINER_ID = 119;
    public static final int UI_CONTAINER_ID = 124;
    public static final int ARMOR_CONTAINER_TYPE = 6;
    public static final int OFFHAND_CONTAINER_TYPE = 34;
    public static final int CURSOR_CONTAINER_TYPE = 59;

    private BedrockPlayerEquipmentCodec() {
    }

    public static List<BedrockPacket> createInitialEquipment(PlayerEquipment equipment,
                                                              BedrockItemRegistry registry) {
        Objects.requireNonNull(equipment, "equipment");
        Objects.requireNonNull(registry, "registry");
        PlayerEquipment.Snapshot snapshot = equipment.snapshot();
        List<BedrockPacket> packets = new ArrayList<>(3);
        packets.add(createArmorContent(snapshot.armor(), registry));
        packets.add(createOffhandContent(snapshot.offhand(), registry));
        packets.add(createCursorSlot(snapshot.cursor(), registry));
        return List.copyOf(packets);
    }

    public static BedrockPacket createArmorContent(List<Optional<ItemStack>> armor,
                                                    BedrockItemRegistry registry) {
        if (armor == null || armor.size() != PlayerEquipment.ARMOR_SLOTS) {
            throw new IllegalArgumentException("Armor content must contain exactly four slots");
        }
        Writer out = new Writer();
        out.varUInt32(ARMOR_CONTAINER_ID);
        out.varUInt32(PlayerEquipment.ARMOR_SLOTS);
        for (Optional<ItemStack> stack : armor) {
            writeContainerStack(out, stack, registry);
        }
        writeFullContainerName(out, ARMOR_CONTAINER_TYPE, ARMOR_CONTAINER_ID);
        writeEmptyStorageItem(out);
        return new BedrockPacket(BedrockInventoryContentCodec.INVENTORY_CONTENT_PACKET_ID, 0, 0, out.bytes());
    }

    public static BedrockPacket createOffhandContent(Optional<ItemStack> offhand,
                                                      BedrockItemRegistry registry) {
        Writer out = new Writer();
        out.varUInt32(OFFHAND_CONTAINER_ID);
        out.varUInt32(1);
        writeContainerStack(out, Objects.requireNonNull(offhand, "offhand"), registry);
        writeFullContainerName(out, OFFHAND_CONTAINER_TYPE, 0);
        writeEmptyStorageItem(out);
        return new BedrockPacket(BedrockInventoryContentCodec.INVENTORY_CONTENT_PACKET_ID, 0, 0, out.bytes());
    }

    public static BedrockPacket createCursorSlot(Optional<ItemStack> cursor, BedrockItemRegistry registry) {
        Writer out = new Writer();
        // Protocol >= 1.26.20.26: InventorySlot carries byte container ID,
        // slot VarUInt32, optional FullContainerName=false, optional StorageItem=false.
        out.byteValue(UI_CONTAINER_ID);
        out.varUInt32(0);
        out.byteValue(0); // FullContainerName absent
        out.byteValue(0); // StorageItem absent
        writeDescriptor(out, Objects.requireNonNull(cursor, "cursor"), registry);
        return new BedrockPacket(0x32, 0, 0, out.bytes());
    }

    private static void writeContainerStack(Writer out, Optional<ItemStack> optional,
                                            BedrockItemRegistry registry) {
        ItemStack stack = optional.orElse(null);
        out.varInt32(stack == null ? 0 : 1);
        writeDescriptor(out, optional, registry);
    }

    private static void writeDescriptor(Writer out, Optional<ItemStack> optional,
                                        BedrockItemRegistry registry) {
        ItemStack stack = optional.orElse(null);
        if (stack == null) {
            out.int16(0);
            out.uint16(0);
            out.varUInt32(0);
            out.byteValue(0);
            out.varUInt32(0);
            out.byteArray(new byte[0]);
            return;
        }
        BedrockItemRegistry.Entry entry = registry.findByIdentifier(stack.itemId());
        if (entry == null) throw new IllegalArgumentException("No Bedrock item definition for " + stack.itemId());
        if (stack.count() > entry.maxStackSize()) throw new IllegalArgumentException("Equipment stack exceeds item maximum");
        BedrockInventoryContentCodec.ItemDefinition definition = entry.networkDefinition();
        out.int16(definition.runtimeItemId());
        out.uint16(stack.count());
        out.varUInt32(definition.auxValue());
        out.byteValue(0); // ItemStackNetManager disabled
        out.varUInt32(definition.blockRuntimeId());
        out.byteArray(definition.userData());
    }

    private static void writeFullContainerName(Writer out, int type, int dynamicId) {
        out.byteValue(type);
        out.byteValue(1); // optional dynamic ID present
        out.int32(dynamicId);
    }

    private static void writeEmptyStorageItem(Writer out) {
        // InventoryContent carries StorageItem as a mandatory descriptor in protocol 2225.
        out.int16(0);
        out.uint16(0);
        out.varUInt32(0);
        out.byteValue(0);
        out.varUInt32(0);
        out.byteArray(new byte[0]);
    }

    private static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(96);
        void byteValue(int value) { out.write(value & 0xff); }
        void int16(int value) { byteValue(value); byteValue(value >>> 8); }
        void uint16(int value) {
            if (value < 0 || value > 0xffff) throw new IllegalArgumentException("uint16 out of range");
            byteValue(value); byteValue(value >>> 8);
        }
        void int32(int value) {
            byteValue(value); byteValue(value >>> 8); byteValue(value >>> 16); byteValue(value >>> 24);
        }
        void varInt32(int value) { varUInt32(Integer.toUnsignedLong((value << 1) ^ (value >> 31))); }
        void varUInt32(long value) {
            if (value < 0 || value > 0xffff_ffffL) throw new IllegalArgumentException("varuint32 out of range");
            do {
                int next = (int) (value & 0x7f);
                value >>>= 7;
                if (value != 0) next |= 0x80;
                byteValue(next);
            } while (value != 0);
        }
        void byteArray(byte[] value) { varUInt32(value.length); out.writeBytes(value); }
        byte[] bytes() { return out.toByteArray(); }
    }
}
