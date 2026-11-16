package ru.nexora.network.bedrock;

import ru.nexora.inventory.ItemStack;
import ru.nexora.inventory.PlayerInventory;

import java.io.ByteArrayOutputStream;
import java.util.Objects;

/**
 * Encodes the protocol 2225 InventoryContent packet while ItemStackNetManager is disabled.
 *
 * The caller must supply a complete protocol definition for every non-empty NEXORA item.
 * This codec deliberately does not guess Bedrock runtime item IDs, block runtime IDs, metadata or user data.
 * The disabled-manager ItemStackNetId is 0 for empty slots and 1 for non-empty slots.
 */
public final class BedrockInventoryContentCodec {
    public static final int INVENTORY_CONTENT_PACKET_ID = 0x31;
    public static final int PLAYER_INVENTORY_CONTAINER_ID = 0;
    public static final int PLAYER_INVENTORY_CONTAINER_NAME = 29; // InventoryContainer

    private BedrockInventoryContentCodec() {
    }

    public static BedrockPacket createPlayerInventory(
            PlayerInventory inventory,
            ItemDefinitionResolver itemDefinitionResolver
    ) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(itemDefinitionResolver, "itemDefinitionResolver");

        var slots = inventory.snapshot();
        Writer out = new Writer();
        out.varUInt32(PLAYER_INVENTORY_CONTAINER_ID);
        out.varUInt32(slots.size());

        for (var optionalStack : slots) {
            ItemStack stack = optionalStack.orElse(null);
            out.varInt32(stack == null ? 0 : 1);
            if (stack == null) {
                writeStack(out, 0, 0, 0, 0, new byte[0]);
            } else {
                ItemDefinition definition = itemDefinitionResolver.resolve(stack.itemId());
                if (definition == null) {
                    throw new IllegalArgumentException(
                            "No Bedrock item definition registered for " + stack.itemId());
                }
                writeStack(out, definition.runtimeItemId(), stack.count(),
                        definition.auxValue(), definition.blockRuntimeId(), definition.userData());
            }
        }

        // FullContainerName: InventoryContainer. Dynamic ID is optional and omitted.
        out.byteValue(PLAYER_INVENTORY_CONTAINER_NAME);
        // Optional Storage Item is absent.
        return new BedrockPacket(INVENTORY_CONTENT_PACKET_ID, 0, 0, out.toByteArray());
    }

    /**
     * Encodes one player inventory slot using the same disabled ItemStackNetManager
     * compatibility format as InventoryContent.
     */
    public static BedrockPacket createPlayerInventorySlot(
            PlayerInventory inventory, int slot, ItemDefinitionResolver itemDefinitionResolver
    ) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(itemDefinitionResolver, "itemDefinitionResolver");
        var optionalStack = inventory.getSlot(slot); // validates slot bounds
        Writer out = new Writer();
        out.byteValue(PLAYER_INVENTORY_CONTAINER_ID);
        out.varUInt32(slot);
        // FullContainerName: InventoryContainer. Dynamic ID and Storage Item are absent.
        out.byteValue(PLAYER_INVENTORY_CONTAINER_NAME);
        ItemStack stack = optionalStack.orElse(null);
        out.varInt32(stack == null ? 0 : 1);
        if (stack == null) {
            writeStack(out, 0, 0, 0, 0, new byte[0]);
        } else {
            ItemDefinition definition = itemDefinitionResolver.resolve(stack.itemId());
            if (definition == null) {
                throw new IllegalArgumentException(
                        "No Bedrock item definition registered for " + stack.itemId());
            }
            writeStack(out, definition.runtimeItemId(), stack.count(),
                    definition.auxValue(), definition.blockRuntimeId(), definition.userData());
        }
        return new BedrockPacket(0x32, 0, 0, out.toByteArray());
    }

    private static void writeStack(
            Writer out, int itemId, int count, long auxValue, long blockRuntimeId, byte[] userData
    ) {
        out.int16(itemId);
        out.uint16(count);
        out.varUInt32(auxValue);
        // NetworkItemStackDescriptor always carries the hasNetId presence boolean.
        // ItemStackNetManager is disabled, so this must be false.
        out.byteValue(0);
        out.varUInt32(blockRuntimeId);
        out.string(userData);
    }

    @FunctionalInterface
    public interface ItemDefinitionResolver {
        ItemDefinition resolve(String namespacedItemId);
    }

    /** Protocol-specific fields that cannot safely be inferred from the logical ItemStack. */
    public record ItemDefinition(
            int runtimeItemId, long auxValue, long blockRuntimeId, byte[] userData
    ) {
        public ItemDefinition {
            if (runtimeItemId == 0 || runtimeItemId < Short.MIN_VALUE || runtimeItemId > Short.MAX_VALUE) {
                throw new IllegalArgumentException("runtimeItemId must be a non-zero signed int16");
            }
            if (auxValue < 0 || auxValue > 0xffff_ffffL) {
                throw new IllegalArgumentException("auxValue must be uint32");
            }
            if (blockRuntimeId < 0 || blockRuntimeId > 0xffff_ffffL) {
                throw new IllegalArgumentException("blockRuntimeId must be uint32");
            }
            userData = Objects.requireNonNull(userData, "userData").clone();
            if (userData.length > 1_048_576) {
                throw new IllegalArgumentException("userData exceeds 1 MiB");
            }
        }

        @Override
        public byte[] userData() {
            return userData.clone();
        }
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

        void string(byte[] value) {
            Objects.requireNonNull(value, "value");
            varUInt32(value.length);
            output.writeBytes(value);
        }

        byte[] toByteArray() {
            return output.toByteArray();
        }
    }
}
