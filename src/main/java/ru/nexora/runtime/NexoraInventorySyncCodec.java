package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Wire codec for server-authoritative player inventory snapshots (message type 11). */
public final class NexoraInventorySyncCodec {
    private static final byte[] MAGIC = {'N', 'X', 'R', 'A'};
    private static final int MESSAGE_TYPE = 11;
    private static final int MAX_SLOTS = 36;
    private static final int MAX_ITEM_ID_BYTES = 96;

    private NexoraInventorySyncCodec() {
    }

    public record InventoryState(UUID playerId, List<ItemStack> stacks) {
        public InventoryState {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(stacks, "stacks");
            if (stacks.size() > MAX_SLOTS) {
                throw new IllegalArgumentException("Inventory contains too many stacks");
            }
            stacks = List.copyOf(stacks);
        }
    }

    public static byte[] encode(PlayerInventory inventory) {
        Objects.requireNonNull(inventory, "inventory");
        List<ItemStack> stacks = inventory.snapshot();
        int size = 7 + 16 + 1;
        for (ItemStack stack : stacks) {
            int itemBytes = stack.itemId().getBytes(StandardCharsets.UTF_8).length;
            if (itemBytes > MAX_ITEM_ID_BYTES) {
                throw new IllegalArgumentException("Item identifier is too long");
            }
            size += 2 + itemBytes;
        }
        ByteBuffer output = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        output.put(MAGIC).put((byte) 1).put((byte) 1).put((byte) MESSAGE_TYPE);
        UUID owner = inventory.ownerId();
        output.putLong(owner.getMostSignificantBits()).putLong(owner.getLeastSignificantBits());
        output.put((byte) stacks.size());
        for (ItemStack stack : stacks) {
            byte[] id = stack.itemId().getBytes(StandardCharsets.UTF_8);
            output.put((byte) id.length).put(id).put((byte) stack.count());
        }
        return output.array();
    }

    public static InventoryState decode(ByteBuffer source) {
        Objects.requireNonNull(source, "source");
        ByteBuffer input = source.slice().order(ByteOrder.BIG_ENDIAN);
        if (input.remaining() < 24) throw new IllegalArgumentException("Truncated inventory snapshot");
        for (byte expected : MAGIC) {
            if (input.get() != expected) throw new IllegalArgumentException("Invalid NEXORA packet magic");
        }
        if (Byte.toUnsignedInt(input.get()) != 1 || Byte.toUnsignedInt(input.get()) != 1
                || Byte.toUnsignedInt(input.get()) != MESSAGE_TYPE) {
            throw new IllegalArgumentException("Unexpected NEXORA inventory snapshot");
        }
        UUID owner = new UUID(input.getLong(), input.getLong());
        int count = Byte.toUnsignedInt(input.get());
        if (count > MAX_SLOTS) throw new IllegalArgumentException("Too many inventory stacks");
        List<ItemStack> stacks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (!input.hasRemaining()) throw new IllegalArgumentException("Truncated inventory stack");
            int length = Byte.toUnsignedInt(input.get());
            if (length < 1 || length > MAX_ITEM_ID_BYTES || input.remaining() < length + 1) {
                throw new IllegalArgumentException("Invalid inventory item identifier length");
            }
            byte[] id = new byte[length];
            input.get(id);
            String itemId = new String(id, StandardCharsets.UTF_8);
            int stackSize = Byte.toUnsignedInt(input.get());
            stacks.add(new ItemStack(itemId, stackSize));
        }
        if (input.hasRemaining()) throw new IllegalArgumentException("Trailing inventory snapshot bytes");
        return new InventoryState(owner, stacks);
    }
}
