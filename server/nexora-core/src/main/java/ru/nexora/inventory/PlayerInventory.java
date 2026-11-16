package ru.nexora.inventory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Authoritative player inventory model.
 *
 * All mutations are synchronized and all returned stacks/snapshots are immutable.
 * This class is deliberately independent of Bedrock packet encoding: network item
 * descriptors must be decoded and validated before calling these methods.
 */
public final class PlayerInventory {
    public static final int DEFAULT_SIZE = 36;
    public static final int HOTBAR_SIZE = 9;

    private final ItemStack[] slots;
    private int selectedHotbarSlot;

    public PlayerInventory() {
        this(DEFAULT_SIZE);
    }

    public PlayerInventory(int size) {
        if (size < HOTBAR_SIZE || size > 256) {
            throw new IllegalArgumentException("Inventory size must be 9..256");
        }
        slots = new ItemStack[size];
    }

    public int size() {
        return slots.length;
    }

    public synchronized int selectedHotbarSlot() {
        return selectedHotbarSlot;
    }

    public synchronized void selectHotbarSlot(int slot) {
        if (slot < 0 || slot >= HOTBAR_SIZE || slot >= slots.length) {
            throw new IllegalArgumentException("Selected slot must be within the hotbar");
        }
        selectedHotbarSlot = slot;
    }

    public synchronized Optional<ItemStack> getSlot(int slot) {
        checkSlot(slot);
        return Optional.ofNullable(slots[slot]);
    }

    public synchronized Optional<ItemStack> heldItem() {
        return Optional.ofNullable(slots[selectedHotbarSlot]);
    }

    public synchronized void setSlot(int slot, ItemStack stack) {
        checkSlot(slot);
        slots[slot] = stack;
    }

    /** Returns an immutable slot-order snapshot; empty slots are represented by Optional.empty(). */
    public synchronized List<Optional<ItemStack>> snapshot() {
        List<Optional<ItemStack>> result = new ArrayList<>(slots.length);
        for (ItemStack stack : slots) result.add(Optional.ofNullable(stack));
        return List.copyOf(result);
    }

    /**
     * Adds as much as possible, first merging compatible stacks and then filling empty slots.
     * Returns the number of items that did not fit.
     */
    public synchronized int add(ItemStack incoming) {
        Objects.requireNonNull(incoming, "incoming");
        int remaining = incoming.count();

        for (int i = 0; i < slots.length && remaining > 0; i++) {
            ItemStack current = slots[i];
            if (current == null || !current.canStackWith(incoming)) continue;
            int moved = Math.min(remaining, current.maxStackSize() - current.count());
            if (moved > 0) {
                slots[i] = current.withCount(current.count() + moved);
                remaining -= moved;
            }
        }

        for (int i = 0; i < slots.length && remaining > 0; i++) {
            if (slots[i] != null) continue;
            int moved = Math.min(remaining, incoming.maxStackSize());
            slots[i] = incoming.withCount(moved);
            remaining -= moved;
        }
        return remaining;
    }

    /** Removes exactly the requested quantity, or leaves the inventory unchanged. */
    public synchronized boolean remove(String itemId, int quantity) {
        Objects.requireNonNull(itemId, "itemId");
        if (quantity < 1) throw new IllegalArgumentException("quantity must be positive");
        int available = 0;
        for (ItemStack stack : slots) {
            if (stack != null && stack.itemId().equals(itemId)) available += stack.count();
        }
        if (available < quantity) return false;

        int remaining = quantity;
        for (int i = 0; i < slots.length && remaining > 0; i++) {
            ItemStack current = slots[i];
            if (current == null || !current.itemId().equals(itemId)) continue;
            int removed = Math.min(remaining, current.count());
            remaining -= removed;
            slots[i] = removed == current.count() ? null : current.withCount(current.count() - removed);
        }
        return true;
    }

    /**
     * Moves up to quantity items from one slot to another.
     *
     * The operation is atomic. It only moves into an empty slot or a compatible
     * stack with available capacity; it never silently swaps or overwrites items.
     * Returns the number of items moved.
     */
    public synchronized int move(int sourceSlot, int destinationSlot, int quantity) {
        checkSlot(sourceSlot);
        checkSlot(destinationSlot);
        if (quantity < 1) throw new IllegalArgumentException("quantity must be positive");
        if (sourceSlot == destinationSlot) return 0;

        ItemStack source = slots[sourceSlot];
        if (source == null) return 0;

        ItemStack destination = slots[destinationSlot];
        if (destination != null && !destination.canStackWith(source)) return 0;

        int capacity = destination == null
                ? source.maxStackSize()
                : destination.maxStackSize() - destination.count();
        int moved = Math.min(Math.min(quantity, source.count()), capacity);
        if (moved <= 0) return 0;

        slots[sourceSlot] = moved == source.count()
                ? null : source.withCount(source.count() - moved);
        slots[destinationSlot] = destination == null
                ? source.withCount(moved)
                : destination.withCount(destination.count() + moved);
        return moved;
    }

    /** Swaps two slots atomically without dropping or merging either stack. */
    public synchronized void swapSlots(int firstSlot, int secondSlot) {
        checkSlot(firstSlot);
        checkSlot(secondSlot);
        if (firstSlot == secondSlot) return;
        ItemStack first = slots[firstSlot];
        slots[firstSlot] = slots[secondSlot];
        slots[secondSlot] = first;
    }

    public synchronized int count(String itemId) {
        Objects.requireNonNull(itemId, "itemId");
        int total = 0;
        for (ItemStack stack : slots) {
            if (stack != null && stack.itemId().equals(itemId)) total += stack.count();
        }
        return total;
    }

    private void checkSlot(int slot) {
        if (slot < 0 || slot >= slots.length) throw new IndexOutOfBoundsException("Invalid inventory slot: " + slot);
    }
}
