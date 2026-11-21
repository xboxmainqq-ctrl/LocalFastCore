package ru.nexora.network.bedrock;

import ru.nexora.inventory.ItemStack;
import ru.nexora.inventory.PlayerInventory;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Protocol-2225 MobEquipment codec (packet 0x1f).
 *
 * Only the player's main-inventory hotbar (container 0) is modelled here.
 * Other containers and stack NetIds are deliberately unsupported.
 */
public final class BedrockMobEquipmentCodec {
    public static final int MOB_EQUIPMENT_PACKET_ID = 0x1f;
    private static final int MAX_USER_DATA_BYTES = 1_048_576;

    private BedrockMobEquipmentCodec() {
    }

    public static DecodedEquipment decode(BedrockPacket packet, BedrockItemRegistry registry) {
        if (packet == null || packet.packetId() != MOB_EQUIPMENT_PACKET_ID) {
            throw new IllegalArgumentException("Expected MobEquipment packet 0x1f");
        }
        Objects.requireNonNull(registry, "registry");
        Reader in = new Reader(packet.payload());
        long runtimeEntityId = in.varUInt64();
        Optional<ItemStack> item = readItem(in, registry);
        int inventorySlot = in.byteValue();
        int hotbarSlot = in.byteValue();
        int containerId = in.byteValue();
        in.requireExhausted();
        return new DecodedEquipment(runtimeEntityId, item, inventorySlot, hotbarSlot, containerId);
    }

    public static BedrockPacket createPlayerEquipment(
            long runtimeEntityId, PlayerInventory inventory, BedrockItemRegistry registry
    ) {
        if (runtimeEntityId < 0) throw new IllegalArgumentException("runtimeEntityId must be non-negative");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(registry, "registry");
        int selectedSlot = inventory.selectedHotbarSlot();
        Optional<ItemStack> held = inventory.heldItem();

        Writer out = new Writer();
        out.varUInt64(runtimeEntityId);
        writeItem(out, held, registry);
        out.byteValue(selectedSlot);
        out.byteValue(selectedSlot);
        out.byteValue(BedrockInventoryContentCodec.PLAYER_INVENTORY_CONTAINER_ID);
        return new BedrockPacket(MOB_EQUIPMENT_PACKET_ID, 0, 0, out.toByteArray());
    }

    private static Optional<ItemStack> readItem(Reader in, BedrockItemRegistry registry) {
        int runtimeItemId = in.int16();
        int count = in.uint16();
        long auxValue = in.varUInt32();
        boolean hasStackNetId = in.bool();
        // Modern Bedrock clients may include a StackNetId on equipment updates.
        // Consume the signed VarInt even though NEXORA does not yet track stack IDs.
        if (hasStackNetId) {
            in.varInt32();
        }
        long blockRuntimeId = in.varUInt32();
        byte[] userData = in.byteArray(MAX_USER_DATA_BYTES);

        if (runtimeItemId == 0) {
            if (count != 0 || auxValue != 0 || blockRuntimeId != 0 || userData.length != 0) {
                throw new IllegalArgumentException("Malformed empty equipment descriptor");
            }
            return Optional.empty();
        }
        if (count < 1) throw new IllegalArgumentException("Non-empty equipment item has zero count");

        BedrockItemRegistry.Entry entry = registry.findByRuntimeItemId(runtimeItemId);
        if (entry == null) throw new IllegalArgumentException("Unknown equipment runtime item ID");
        BedrockInventoryContentCodec.ItemDefinition definition = entry.networkDefinition();
        if (auxValue != definition.auxValue() || blockRuntimeId != definition.blockRuntimeId()) {
            throw new IllegalArgumentException("Equipment item descriptor does not match registry");
        }
        BedrockItemStackUserDataCodec.DecodedMetadata metadata =
                BedrockItemStackUserDataCodec.decode(userData, definition.userData());
        if (count > entry.maxStackSize()) throw new IllegalArgumentException("Equipment stack exceeds maximum size");
        return Optional.of(new ItemStack(entry.namespacedId(), count, entry.maxStackSize(),
                metadata.enchantments(), metadata.repairCost()));
    }

    private static void writeItem(Writer out, Optional<ItemStack> optional, BedrockItemRegistry registry) {
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
        if (stack.count() > entry.maxStackSize()) {
            throw new IllegalArgumentException("Inventory stack exceeds registered item maximum");
        }
        BedrockInventoryContentCodec.ItemDefinition definition = entry.networkDefinition();
        out.int16(definition.runtimeItemId());
        out.uint16(stack.count());
        out.varUInt32(definition.auxValue());
        out.byteValue(0); // hasStackNetId = false while ItemStackNetManager is disabled
        out.varUInt32(definition.blockRuntimeId());
        out.byteArray(BedrockItemStackUserDataCodec.encode(stack, definition.userData()));
    }

    public record DecodedEquipment(
            long runtimeEntityId,
            Optional<ItemStack> item,
            int inventorySlot,
            int hotbarSlot,
            int containerId
    ) {
        public DecodedEquipment {
            Objects.requireNonNull(item, "item");
        }
    }

    private static final class Reader {
        private final ByteBuffer buffer;

        Reader(byte[] payload) {
            if (payload == null || payload.length > 2 * 1024 * 1024) {
                throw new IllegalArgumentException("MobEquipment payload size is invalid");
            }
            buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        }

        int byteValue() {
            require(1);
            return Byte.toUnsignedInt(buffer.get());
        }

        int int16() {
            require(2);
            return buffer.getShort();
        }

        int uint16() {
            require(2);
            return Short.toUnsignedInt(buffer.getShort());
        }

        boolean bool() {
            int value = byteValue();
            if (value > 1) throw new IllegalArgumentException("Invalid MobEquipment boolean");
            return value != 0;
        }

        long varUInt32() {
            long value = 0;
            for (int i = 0; i < 5; i++) {
                int current = byteValue();
                if (i == 4 && (current & 0xf0) != 0) {
                    throw new IllegalArgumentException("MobEquipment VarUInt32 overflow");
                }
                value |= (long) (current & 0x7f) << (7 * i);
                if ((current & 0x80) == 0) return value;
            }
            throw new IllegalArgumentException("MobEquipment VarUInt32 too long");
        }

        long varUInt64() {
            long value = 0;
            for (int i = 0; i < 10; i++) {
                int current = byteValue();
                if (i == 9 && (current & 0xfe) != 0) {
                    throw new IllegalArgumentException("MobEquipment VarUInt64 overflow");
                }
                value |= (long) (current & 0x7f) << (7 * i);
                if ((current & 0x80) == 0) {
                    if (value < 0) throw new IllegalArgumentException("Runtime entity ID exceeds signed long range");
                    return value;
                }
            }
            throw new IllegalArgumentException("MobEquipment VarUInt64 too long");
        }

        int varInt32() {
            long value = varUInt32();
            if (value > 0xffff_ffffL) throw new IllegalArgumentException("MobEquipment VarInt32 overflow");
            int encoded = (int) value;
            return (encoded >>> 1) ^ -(encoded & 1);
        }

        byte[] byteArray(int maximumLength) {
            long length = varUInt32();
            if (length > maximumLength) throw new IllegalArgumentException("MobEquipment user data too large");
            require((int) length);
            byte[] result = new byte[(int) length];
            buffer.get(result);
            return result;
        }

        void requireExhausted() {
            if (buffer.hasRemaining()) throw new IllegalArgumentException("Trailing MobEquipment bytes");
        }

        private void require(int length) {
            if (length < 0 || buffer.remaining() < length) {
                throw new IllegalArgumentException("Truncated MobEquipment packet");
            }
        }
    }

    private static final class Writer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream(64);

        void byteValue(int value) {
            output.write(value & 0xff);
        }

        void int16(int value) {
            byteValue(value);
            byteValue(value >>> 8);
        }

        void uint16(int value) {
            if (value < 0 || value > 0xffff) throw new IllegalArgumentException("uint16 out of range");
            byteValue(value);
            byteValue(value >>> 8);
        }

        void varUInt32(long value) {
            if (value < 0 || value > 0xffff_ffffL) throw new IllegalArgumentException("varuint32 out of range");
            do {
                int next = (int) (value & 0x7f);
                value >>>= 7;
                if (value != 0) next |= 0x80;
                byteValue(next);
            } while (value != 0);
        }

        void varUInt64(long value) {
            if (value < 0) throw new IllegalArgumentException("varuint64 out of supported range");
            do {
                int next = (int) (value & 0x7f);
                value >>>= 7;
                if (value != 0) next |= 0x80;
                byteValue(next);
            } while (value != 0);
        }

        void byteArray(byte[] value) {
            varUInt32(value.length);
            output.writeBytes(value);
        }

        byte[] toByteArray() {
            return output.toByteArray();
        }
    }
}
