package ru.nexora.network.bedrock;

import ru.nexora.inventory.ItemStack;
import ru.nexora.inventory.InventoryTransactionService;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Strict protocol-2225 decoder for the normal InventoryTransaction subset.
 *
 * It intentionally rejects legacy item-stack requests, non-normal transaction
 * variants, unknown item runtime IDs, stack NetIds, and item descriptors that
 * do not exactly match the server registry. Unsupported UI containers are
 * decoded structurally and then rejected by InventoryTransactionService.
 */
public final class BedrockInventoryTransactionCodec {
    public static final int INVENTORY_TRANSACTION_PACKET_ID = 0x1e;
    private static final int NORMAL_TRANSACTION_TYPE = 0;
    private static final int ITEM_USE_ON_ACTOR_TRANSACTION_TYPE = 3;
    private static final int SOURCE_CONTAINER = 0;
    private static final int MAX_ACTIONS = InventoryTransactionService.MAX_ACTIONS;
    private static final int MAX_USER_DATA_BYTES = 1_048_576;

    private BedrockInventoryTransactionCodec() {
    }

    public static DecodedTransaction decode(BedrockPacket packet, BedrockItemRegistry registry) {
        if (packet == null || packet.packetId() != INVENTORY_TRANSACTION_PACKET_ID) {
            throw new IllegalArgumentException("Expected InventoryTransaction packet 0x1e");
        }
        if (registry == null) throw new NullPointerException("registry");

        Reader in = new Reader(packet.payload());
        int legacyRequestId = in.varInt32();
        if (legacyRequestId != 0) {
            // A non-zero request may be followed by protocol-specific legacy slot data.
            // This server has ItemStackNetManager disabled and deliberately rejects it.
            throw new UnsupportedOperationException("Legacy inventory requests are not supported");
        }

        int transactionType = in.varInt32();
        if (transactionType != NORMAL_TRANSACTION_TYPE) {
            throw new UnsupportedOperationException("Only normal inventory transactions are supported");
        }

        int actionCount = in.varUInt32Int();
        if (actionCount < 1 || actionCount > MAX_ACTIONS) {
            throw new IllegalArgumentException("Invalid inventory transaction action count");
        }

        List<InventoryTransactionService.Action> actions = new ArrayList<>(actionCount);
        for (int i = 0; i < actionCount; i++) {
            int sourceType = in.varUInt32Int();

            boolean hasContainerId = in.bool();
            int containerId = hasContainerId ? in.int8() : -1;

            boolean hasFlags = in.bool();
            if (hasFlags) in.varUInt32(); // World-interaction flags are not supported by this service.

            if (sourceType == SOURCE_CONTAINER) {
                // Parse known player containers structurally. The authoritative transaction
                // service validates main inventory, armor, offhand and cursor changes separately.
                boolean playerUiContainer = containerId == 0 || containerId == 119
                        || containerId == 120 || containerId == 124;
                boolean blockContainerWindow = containerId >= 1
                        && containerId <= 100;
                if (!hasContainerId || (!playerUiContainer && !blockContainerWindow) || hasFlags) {
                    throw new IllegalArgumentException("Malformed inventory container source");
                }
            }

            int slot = in.varUInt32Int();
            Optional<ItemStack> from = readItemStack(in, registry);
            Optional<ItemStack> to = readItemStack(in, registry);
            actions.add(new InventoryTransactionService.Action(
                    sourceType, containerId, slot, from, to));
        }

        in.requireExhausted();
        return new DecodedTransaction(transactionType, List.copyOf(actions));
    }

    public static int transactionType(BedrockPacket packet) {
        if (packet == null || packet.packetId() != INVENTORY_TRANSACTION_PACKET_ID) {
            throw new IllegalArgumentException("Expected InventoryTransaction packet 0x1e");
        }
        Reader in = new Reader(packet.payload());
        int legacyRequestId = in.varInt32();
        if (legacyRequestId != 0) throw new UnsupportedOperationException("Legacy inventory requests are not supported");
        return in.varInt32();
    }

    /**
     * Decodes protocol-2225 ItemUseOnActor transaction data. Protocol 2225
     * includes the Hand byte between Slot and Item (added in 1.26.60).
     * Inventory action deltas are deliberately rejected until actor combat can
     * atomically validate and apply them with the authoritative inventory.
     */
    public static DecodedUseOnActor decodeUseOnActor(BedrockPacket packet, BedrockItemRegistry registry) {
        if (packet == null || packet.packetId() != INVENTORY_TRANSACTION_PACKET_ID) {
            throw new IllegalArgumentException("Expected InventoryTransaction packet 0x1e");
        }
        if (registry == null) throw new NullPointerException("registry");
        Reader in = new Reader(packet.payload());
        if (in.varInt32() != 0) {
            throw new UnsupportedOperationException("Legacy inventory requests are not supported");
        }
        if (in.varInt32() != ITEM_USE_ON_ACTOR_TRANSACTION_TYPE) {
            throw new IllegalArgumentException("Expected ItemUseOnActor transaction type");
        }
        int actionCount = in.varUInt32Int();
        if (actionCount != 0) {
            throw new UnsupportedOperationException("ItemUseOnActor inventory action arrays are not supported");
        }
        long runtimeEntityId = in.varUInt64();
        int actionType = in.varInt32();
        int slot = in.varInt32();
        int hand = in.int8();
        Optional<ItemStack> item = readItemStack(in, registry);
        float fromX = in.float32();
        float fromY = in.float32();
        float fromZ = in.float32();
        float hitX = in.float32();
        float hitY = in.float32();
        float hitZ = in.float32();
        if (runtimeEntityId <= 0 || actionType < 0 || actionType > 2
                || slot < -1 || slot >= 36 || hand < 0 || hand > 1
                || !finiteVector(fromX, fromY, fromZ, 30_000_000.0f)
                || !finiteVector(hitX, hitY, hitZ, 1_000_000.0f)) {
            throw new IllegalArgumentException("Invalid ItemUseOnActor transaction fields");
        }
        in.requireExhausted();
        return new DecodedUseOnActor(runtimeEntityId, actionType, slot, hand, item,
                fromX, fromY, fromZ, hitX, hitY, hitZ);
    }

    private static boolean finiteVector(float x, float y, float z, float limit) {
        return Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z)
                && Math.abs(x) <= limit && Math.abs(y) <= limit && Math.abs(z) <= limit;
    }

    /** Decodes the supported ItemRelease consume/cancel subset used to finish food use. */
    public static DecodedItemRelease decodeItemRelease(BedrockPacket packet, BedrockItemRegistry registry) {
        if (packet == null || packet.packetId() != INVENTORY_TRANSACTION_PACKET_ID) {
            throw new IllegalArgumentException("Expected InventoryTransaction packet 0x1e");
        }
        if (registry == null) throw new NullPointerException("registry");
        Reader in = new Reader(packet.payload());
        if (in.varInt32() != 0) throw new UnsupportedOperationException("Legacy inventory requests are not supported");
        if (in.varInt32() != 4) throw new IllegalArgumentException("Expected ItemRelease transaction type");
        int actionCount = in.varUInt32Int();
        if (actionCount != 0) {
            throw new UnsupportedOperationException("ItemRelease inventory action arrays are not supported");
        }
        int actionType = in.varInt32();
        int hotbarSlot = in.varInt32();
        Optional<ItemStack> heldItem = readItemStack(in, registry);
        float headX = in.float32();
        float headY = in.float32();
        float headZ = in.float32();
        if (actionType < 0 || actionType > 1 || hotbarSlot < 0 || hotbarSlot >= 9
                || !Float.isFinite(headX) || !Float.isFinite(headY) || !Float.isFinite(headZ)
                || Math.abs(headX) > 30_000_000 || Math.abs(headZ) > 30_000_000
                || headY < -2048 || headY > 2048) {
            throw new IllegalArgumentException("Invalid ItemRelease transaction fields");
        }
        in.requireExhausted();
        return new DecodedItemRelease(actionType, hotbarSlot, heldItem, headX, headY, headZ);
    }

    private static Optional<ItemStack> readItemStack(Reader in, BedrockItemRegistry registry) {
        int runtimeItemId = in.int16();
        int count = in.int16();
        long auxValue = in.varUInt32();
        boolean hasStackNetId = in.bool();
        if (hasStackNetId) {
            in.varInt32();
            throw new UnsupportedOperationException("Stack NetIds are not supported while ItemStackNetManager is disabled");
        }
        long blockRuntimeId = in.varUInt32();
        byte[] userData = in.byteArray(MAX_USER_DATA_BYTES);

        if (runtimeItemId == 0) {
            if (count != 0 || auxValue != 0 || blockRuntimeId != 0 || userData.length != 0) {
                throw new IllegalArgumentException("Malformed empty item descriptor");
            }
            return Optional.empty();
        }
        if (count < 1 || count > 99) {
            throw new IllegalArgumentException("Invalid item stack count");
        }

        BedrockItemRegistry.Entry entry = registry.findByRuntimeItemId(runtimeItemId);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown Bedrock runtime item ID: " + runtimeItemId);
        }
        BedrockInventoryContentCodec.ItemDefinition definition = entry.networkDefinition();
        if (definition.auxValue() != auxValue || definition.blockRuntimeId() != blockRuntimeId) {
            throw new IllegalArgumentException("Client item descriptor does not match the registered item definition");
        }
        BedrockItemStackUserDataCodec.DecodedMetadata metadata =
                BedrockItemStackUserDataCodec.decode(userData, definition.userData());
        if (count > entry.maxStackSize()) {
            throw new IllegalArgumentException("Item stack exceeds its registered maximum stack size");
        }
        return Optional.of(new ItemStack(entry.namespacedId(), count, entry.maxStackSize(),
                metadata.enchantments(), metadata.repairCost()));
    }

    public record DecodedTransaction(int transactionType, List<InventoryTransactionService.Action> actions) {
        public DecodedTransaction {
            actions = List.copyOf(actions);
        }
    }

    public record DecodedUseOnActor(
            long runtimeEntityId, int actionType, int slot, int hand, Optional<ItemStack> item,
            float fromX, float fromY, float fromZ, float hitX, float hitY, float hitZ
    ) {
        public DecodedUseOnActor {
            if (runtimeEntityId <= 0) throw new IllegalArgumentException("Invalid target runtime entity ID");
            Objects.requireNonNull(item, "item");
        }
    }

    public record DecodedItemRelease(
            int actionType, int hotbarSlot, Optional<ItemStack> heldItem,
            float headX, float headY, float headZ
    ) {
        public DecodedItemRelease {
            heldItem = Optional.ofNullable(heldItem).orElse(Optional.empty());
        }
    }

    private static final class Reader {
        private final ByteBuffer buffer;

        private Reader(byte[] payload) {
            if (payload == null || payload.length > 4 * 1024 * 1024) {
                throw new IllegalArgumentException("InventoryTransaction payload size is invalid");
            }
            buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        }

        int int8() {
            require(1);
            return buffer.get();
        }

        int int16() {
            require(Short.BYTES);
            return buffer.getShort();
        }

        float float32() {
            require(Float.BYTES);
            return buffer.getFloat();
        }

        boolean bool() {
            require(1);
            int value = Byte.toUnsignedInt(buffer.get());
            if (value > 1) throw new IllegalArgumentException("Invalid InventoryTransaction boolean");
            return value != 0;
        }

        long varUInt32() {
            long value = 0;
            for (int i = 0; i < 5; i++) {
                require(1);
                int current = Byte.toUnsignedInt(buffer.get());
                if (i == 4 && (current & 0xf0) != 0) {
                    throw new IllegalArgumentException("InventoryTransaction VarUInt32 overflow");
                }
                value |= (long) (current & 0x7f) << (7 * i);
                if ((current & 0x80) == 0) return value;
            }
            throw new IllegalArgumentException("InventoryTransaction VarUInt32 is too long");
        }

        int varUInt32Int() {
            long value = varUInt32();
            if (value > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("InventoryTransaction integer exceeds supported range");
            }
            return (int) value;
        }

        long varUInt64() {
            long value = 0;
            for (int i = 0; i < 10; i++) {
                require(1);
                int current = Byte.toUnsignedInt(buffer.get());
                if (i == 9 && (current & 0xfe) != 0) {
                    throw new IllegalArgumentException("InventoryTransaction VarUInt64 overflow");
                }
                value |= (long) (current & 0x7f) << (7 * i);
                if ((current & 0x80) == 0) {
                    if (value < 0) throw new IllegalArgumentException("Runtime entity ID exceeds signed long range");
                    return value;
                }
            }
            throw new IllegalArgumentException("InventoryTransaction VarUInt64 is too long");
        }

        int varInt32() {
            int value = varUInt32Int();
            return (value >>> 1) ^ -(value & 1);
        }

        byte[] byteArray(int maximumLength) {
            long lengthValue = varUInt32();
            if (lengthValue > maximumLength) {
                throw new IllegalArgumentException("Inventory item user data exceeds size limit");
            }
            int length = (int) lengthValue;
            require(length);
            byte[] result = new byte[length];
            buffer.get(result);
            return result;
        }

        void requireExhausted() {
            if (buffer.hasRemaining()) {
                throw new IllegalArgumentException("Trailing bytes in InventoryTransaction packet");
            }
        }

        private void require(int length) {
            if (length < 0 || buffer.remaining() < length) {
                throw new IllegalArgumentException("Truncated InventoryTransaction packet");
            }
        }
    }
}
