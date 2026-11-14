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
            result.add(stack);
        }
        return List.copyOf(result);
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
