package ru.nexora.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Thread-safe fixed-slot player inventory with 64-item stack limits. */
public final class PlayerInventory {
    public static final int DEFAULT_SLOTS = 36;

    private final UUID ownerId;
    private final ItemStack[] slots;

    public PlayerInventory(UUID ownerId) {
        this(ownerId, DEFAULT_SLOTS);
    }

    public PlayerInventory(UUID ownerId, int slotCount) {
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        if (slotCount < 1 || slotCount > 256) {
            throw new IllegalArgumentException("Inventory must have between 1 and 256 slots");
        }
        this.slots = new ItemStack[slotCount];
    }

    public UUID ownerId() {
        return ownerId;
    }

    public int slotCount() {
        return slots.length;
    }

    /** Adds one item, merging existing stacks before using an empty slot. */
    public synchronized boolean addOne(String itemId) {
        Objects.requireNonNull(itemId, "itemId");
        new ItemStack(itemId, 1);
        for (int i = 0; i < slots.length; i++) {
            ItemStack stack = slots[i];
            if (stack != null && stack.itemId().equals(itemId)
                    && stack.count() < ItemStack.MAX_STACK_SIZE) {
                slots[i] = new ItemStack(itemId, stack.count() + 1);
                return true;
            }
        }
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null) {
                slots[i] = new ItemStack(itemId, 1);
                return true;
            }
        }
        return false;
    }

    public synchronized List<ItemStack> snapshot() {
        List<ItemStack> result = new ArrayList<>(slots.length);
        for (ItemStack stack : slots) {
            if (stack != null) {
                result.add(stack);
            }
        }
        return List.copyOf(result);
    }

    /** Returns the stack in an exact slot, or null when the slot is empty. */
    public synchronized ItemStack stackAt(int slot) {
        checkSlot(slot);
        return slots[slot];
    }

    /**
     * Moves a stack, merges compatible stacks up to 64, or swaps incompatible stacks.
     * Returns false for an empty source or an invalid slot.
     */
    public synchronized boolean moveOrSwap(int from, int to) {
        if (!validSlot(from) || !validSlot(to) || from == to || slots[from] == null) {
            return false;
        }
        ItemStack source = slots[from];
        ItemStack target = slots[to];
        if (target == null) {
            slots[to] = source;
            slots[from] = null;
            return true;
        }
        if (target.itemId().equals(source.itemId()) && target.count() < ItemStack.MAX_STACK_SIZE) {
            int moved = Math.min(source.count(), ItemStack.MAX_STACK_SIZE - target.count());
            slots[to] = new ItemStack(target.itemId(), target.count() + moved);
            int remaining = source.count() - moved;
            slots[from] = remaining == 0 ? null : new ItemStack(source.itemId(), remaining);
            return moved > 0;
        }
        slots[from] = target;
        slots[to] = source;
        return true;
    }

    /** Removes one item from an exact slot. */
    public synchronized ItemStack removeOne(int slot) {
        checkSlot(slot);
        ItemStack current = slots[slot];
        if (current == null) return null;
        if (current.count() == 1) {
            slots[slot] = null;
        } else {
            slots[slot] = new ItemStack(current.itemId(), current.count() - 1);
        }
        return new ItemStack(current.itemId(), 1);
    }

    /** Removes and returns the entire stack in an exact slot. */
    public synchronized ItemStack removeStack(int slot) {
        checkSlot(slot);
        ItemStack current = slots[slot];
        slots[slot] = null;
        return current;
    }

    private boolean validSlot(int slot) {
        return slot >= 0 && slot < slots.length;
    }

    private void checkSlot(int slot) {
        if (!validSlot(slot)) throw new IllegalArgumentException("Inventory slot is outside bounds");
    }

    public synchronized int count(String itemId) {
        Objects.requireNonNull(itemId, "itemId");
        int total = 0;
        for (ItemStack stack : slots) {
            if (stack != null && stack.itemId().equals(itemId)) {
                total += stack.count();
            }
        }
        return total;
    }

    public synchronized int occupiedSlots() {
        int occupied = 0;
        for (ItemStack stack : slots) {
            if (stack != null) {
                occupied++;
            }
        }
        return occupied;
    }

    public synchronized boolean isFull() {
        for (ItemStack stack : slots) {
            if (stack == null || stack.count() < ItemStack.MAX_STACK_SIZE) {
                return false;
            }
        }
        return true;
    }
}
