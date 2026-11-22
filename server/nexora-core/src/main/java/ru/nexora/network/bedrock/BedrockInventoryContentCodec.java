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
 * Each slot is written directly as a NetworkItemStackDescriptor; the optional stack net ID is absent.
 */
public final class BedrockInventoryContentCodec {
    public static final int INVENTORY_CONTENT_PACKET_ID = 0x31;
    public static final int PLAYER_INVENTORY_CONTAINER_ID = 0;
    public static final int PLAYER_UI_CONTAINER_ID = 124;
    public static final int PLAYER_UI_SIZE = 51;
    public static final int CRAFTING_INPUT_SLOT_TYPE = 13;
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
            if (stack == null) {
                writeStack(out, 0, 0, 0, 0, new byte[0]);
            } else {
                ItemDefinition definition = itemDefinitionResolver.resolve(stack.itemId());
                if (definition == null) {
                    throw new IllegalArgumentException(
                            "No Bedrock item definition registered for " + stack.itemId());
                }
                writeStack(out, definition.runtimeItemId(), stack.count(),
                        definition.auxValue(), definition.blockRuntimeId(), BedrockItemStackUserDataCodec.encode(stack, definition.userData()));
            }
        }

        // Protocol 2225 FullContainerName uses an optional little-endian dynamic ID.
        out.byteValue(12); // HOTBAR_AND_INVENTORY
        out.byteValue(1);  // dynamic ID is present, including the player container ID 0
        out.int32(PLAYER_INVENTORY_CONTAINER_ID);
        writeEmptyStorageItem(out);
        return new BedrockPacket(INVENTORY_CONTENT_PACKET_ID, 0, 0, out.toByteArray());
    }

    /**
     * Encodes the 51-slot player UI inventory, including the 2x2 crafting grid at slots 28..31.
     * The FullContainerName is CRAFTING_INPUT (13), matching Bedrock's player UI inventory.
     */
    public static BedrockPacket createPlayerUiInventory(
            ru.nexora.inventory.ContainerInventory inventory,
            ItemDefinitionResolver itemDefinitionResolver
    ) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(itemDefinitionResolver, "itemDefinitionResolver");
        if (inventory.size() != PLAYER_UI_SIZE) {
            throw new IllegalArgumentException("Player UI inventory must contain 51 slots");
        }

        var slots = inventory.snapshot();
        Writer out = new Writer();
        out.varUInt32(PLAYER_UI_CONTAINER_ID);
        out.varUInt32(slots.size());
        for (var optionalStack : slots) {
            ItemStack stack = optionalStack.orElse(null);
            if (stack == null) {
                writeStack(out, 0, 0, 0, 0, new byte[0]);
            } else {
                ItemDefinition definition = itemDefinitionResolver.resolve(stack.itemId());
                if (definition == null) {
                    throw new IllegalArgumentException(
                            "No Bedrock item definition registered for " + stack.itemId());
                }
                writeStack(out, definition.runtimeItemId(), stack.count(),
                        definition.auxValue(), definition.blockRuntimeId(), BedrockItemStackUserDataCodec.encode(stack, definition.userData()));
            }
        }
        out.byteValue(CRAFTING_INPUT_SLOT_TYPE);
        out.byteValue(0); // dynamic ID is absent
        writeEmptyStorageItem(out);
        return new BedrockPacket(INVENTORY_CONTENT_PACKET_ID, 0, 0, out.toByteArray());
    }

    /**
     * Encodes a protocol-2225 block-container inventory window.
     * Bedrock's normal block inventories use LEVEL_ENTITY as their FullContainerName
     * slot type and do not carry a dynamic ID.
     */
    public static BedrockPacket createContainerInventory(
            int windowId,
            ru.nexora.inventory.ContainerInventory inventory,
            ItemDefinitionResolver itemDefinitionResolver
    ) {
        if (windowId < 1 || windowId > 100) {
            throw new IllegalArgumentException("Container window ID must be 1..100");
        }
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(itemDefinitionResolver, "itemDefinitionResolver");

        var slots = inventory.snapshot();
        Writer out = new Writer();
        out.varUInt32(windowId);
        out.varUInt32(slots.size());
        for (var optionalStack : slots) {
            ItemStack stack = optionalStack.orElse(null);
            if (stack == null) {
                writeStack(out, 0, 0, 0, 0, new byte[0]);
            } else {
                ItemDefinition definition = itemDefinitionResolver.resolve(stack.itemId());
                if (definition == null) {
                    throw new IllegalArgumentException(
                            "No Bedrock item definition registered for " + stack.itemId());
                }
                writeStack(out, definition.runtimeItemId(), stack.count(),
                        definition.auxValue(), definition.blockRuntimeId(), BedrockItemStackUserDataCodec.encode(stack, definition.userData()));
            }
        }

        out.byteValue(7); // LEVEL_ENTITY FullContainerName for ordinary block inventories
        out.byteValue(0); // dynamic ID is absent
        writeEmptyStorageItem(out);
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
        // Protocol >= 1.26.20.26 carries optional FullContainerName and StorageItem flags.
        out.byteValue(0);
        out.byteValue(0);
        ItemStack stack = optionalStack.orElse(null);
        if (stack == null) {
            writeStack(out, 0, 0, 0, 0, new byte[0]);
        } else {
            ItemDefinition definition = itemDefinitionResolver.resolve(stack.itemId());
            if (definition == null) {
                throw new IllegalArgumentException(
                        "No Bedrock item definition registered for " + stack.itemId());
            }
            writeStack(out, definition.runtimeItemId(), stack.count(),
                    definition.auxValue(), definition.blockRuntimeId(), BedrockItemStackUserDataCodec.encode(stack, definition.userData()));
        }
        return new BedrockPacket(0x32, 0, 0, out.toByteArray());
    }

    private static void writeEmptyStorageItem(Writer out) {
        writeStack(out, 0, 0, 0, 0, new byte[0]);
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

        void int32(int value) {
            byteValue(value);
            byteValue(value >>> 8);
            byteValue(value >>> 16);
            byteValue(value >>> 24);
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
