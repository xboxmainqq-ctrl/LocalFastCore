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

    public record InventoryEntry(int slot, ItemStack stack) {
        public InventoryEntry {
            if (slot < 0 || slot >= MAX_SLOTS) throw new IllegalArgumentException("Invalid inventory slot");
            Objects.requireNonNull(stack, "stack");
        }
    }

    public record InventoryState(UUID playerId, List<InventoryEntry> stacks) {
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
        List<InventoryEntry> stacks = new ArrayList<>();
        for (int slot = 0; slot < inventory.slotCount(); slot++) {
            ItemStack stack = inventory.stackAt(slot);
            if (stack != null) stacks.add(new InventoryEntry(slot, stack));
        }
        int size = 7 + 16 + 1;
        for (InventoryEntry entry : stacks) {
            ItemStack stack = entry.stack();
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
        for (InventoryEntry entry : stacks) {
            ItemStack stack = entry.stack();
            byte[] id = stack.itemId().getBytes(StandardCharsets.UTF_8);
            output.put((byte) entry.slot()).put((byte) id.length).put(id).put((byte) stack.count());
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
        List<InventoryEntry> stacks = new ArrayList<>(count);
        boolean[] seenSlots = new boolean[MAX_SLOTS];
        for (int i = 0; i < count; i++) {
            if (!input.hasRemaining()) throw new IllegalArgumentException("Truncated inventory stack");
            int slot = Byte.toUnsignedInt(input.get());
            int length = Byte.toUnsignedInt(input.get());
            if (slot >= MAX_SLOTS || seenSlots[slot] || length < 1 || length > MAX_ITEM_ID_BYTES || input.remaining() < length + 1) {
                throw new IllegalArgumentException("Invalid inventory item identifier length");
            }
            byte[] id = new byte[length];
            input.get(id);
            String itemId = new String(id, StandardCharsets.UTF_8);
            int stackSize = Byte.toUnsignedInt(input.get());
            seenSlots[slot] = true;
            stacks.add(new InventoryEntry(slot, new ItemStack(itemId, stackSize)));
        }
        if (input.hasRemaining()) throw new IllegalArgumentException("Trailing inventory snapshot bytes");
        return new InventoryState(owner, stacks);
    }
}
