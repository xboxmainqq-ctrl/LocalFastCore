package ru.nexora.network.bedrock;

import ru.nexora.inventory.ItemStack;
import ru.nexora.inventory.PlayerInventory;

import java.io.ByteArrayOutputStream;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * Encodes the protocol 2225 InventoryContent packet while ItemStackNetManager is disabled.
 *
 * The caller must supply the protocol's signed 16-bit legacy item ID for every
 * non-empty NEXORA item. This codec deliberately does not guess IDs or register
 * custom items. The disabled-manager ItemStackNetId is 0 for empty slots and 1
 * for non-empty slots, as required by the Bedrock compatibility guide.
 */
public final class BedrockInventoryContentCodec {
    public static final int INVENTORY_CONTENT_PACKET_ID = 0x31;
    public static final int PLAYER_INVENTORY_CONTAINER_ID = 0;
    public static final int PLAYER_INVENTORY_CONTAINER_NAME = 29; // InventoryContainer

    private BedrockInventoryContentCodec() {
    }

    public static BedrockPacket createPlayerInventory(
            PlayerInventory inventory,
            ToIntFunction<String> legacyItemIdResolver
    ) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(legacyItemIdResolver, "legacyItemIdResolver");

        var slots = inventory.snapshot();
        Writer out = new Writer();
        out.varUInt32(PLAYER_INVENTORY_CONTAINER_ID);
        out.varUInt32(slots.size());

        for (var optionalStack : slots) {
            ItemStack stack = optionalStack.orElse(null);
            out.varInt32(stack == null ? 0 : 1);
            if (stack == null) {
                writeEmptyStack(out);
            } else {
                int itemId = legacyItemIdResolver.applyAsInt(stack.itemId());
                if (itemId == 0 || itemId < Short.MIN_VALUE || itemId > Short.MAX_VALUE) {
                    throw new IllegalArgumentException(
                            "No valid Bedrock legacy item ID for " + stack.itemId());
                }
                writeStack(out, itemId, stack.count());
            }
        }

        // FullContainerName: InventoryContainer. Dynamic ID is optional and omitted.
        out.byteValue(PLAYER_INVENTORY_CONTAINER_NAME);
        // Optional Storage Item is absent.
        return new BedrockPacket(INVENTORY_CONTENT_PACKET_ID, 0, 0, out.toByteArray());
    }

    private static void writeEmptyStack(Writer out) {
        writeStack(out, 0, 0);
    }

    private static void writeStack(Writer out, int itemId, int count) {
        out.int16(itemId);
        out.uint16(count);
        out.varUInt32(0); // Aux value / metadata
        // Optional ItemStackNetIdVariant is omitted while ItemStackNetManager is disabled.
        out.varUInt32(0); // Block Runtime ID (not a block-specific descriptor)
        out.string("");
    }

    private static final class Writer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream(320);

        void byteValue(int value) {
            output.write(value & 0xff);
        }

        void int16(int value) {
            byteValue(value);
            byteValue(value >>> 8);
        }

        void uint16(int value) {
            if (value < 0 || value > 0xffff) {
                throw new IllegalArgumentException("uint16 out of range");
            }
            byteValue(value);
            byteValue(value >>> 8);
        }

        void varInt32(int value) {
            varUInt32(Integer.toUnsignedLong((value << 1) ^ (value >> 31)));
        }

        void varUInt32(long value) {
            if (value < 0 || value > 0xffff_ffffL) {
                throw new IllegalArgumentException("varuint32 out of range");
            }
            do {
                int next = (int) (value & 0x7f);
                value >>>= 7;
                if (value != 0) next |= 0x80;
                byteValue(next);
            } while (value != 0);
        }

        void string(String value) {
            byte[] bytes = Objects.requireNonNull(value, "value")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            varUInt32(bytes.length);
            output.writeBytes(bytes);
        }

        byte[] toByteArray() {
            return output.toByteArray();
        }
    }
}
