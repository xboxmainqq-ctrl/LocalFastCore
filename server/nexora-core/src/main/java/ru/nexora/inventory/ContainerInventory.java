package ru.nexora.inventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Server-authoritative storage for a world container such as a chest.
 * The default chest size follows Bedrock's 27-slot single-chest layout.
 */
public final class ContainerInventory {
    public static final int CHEST_SIZE = 27;
    private static final Object TRANSFER_LOCK = new Object();

    private final ItemStack[] slots;

    public ContainerInventory() {
        this(CHEST_SIZE);
    }

    public ContainerInventory(int size) {
        if (size < 1 || size > 256) {
            throw new IllegalArgumentException("Container size must be 1..256");
        }
        slots = new ItemStack[size];
    }

    public int size() {
        return slots.length;
    }

    public synchronized Optional<ItemStack> getSlot(int slot) {
        checkSlot(slot);
        return Optional.ofNullable(slots[slot]);
    }

    public synchronized void setSlot(int slot, ItemStack stack) {
        checkSlot(slot);
        slots[slot] = stack;
    }

    public synchronized List<Optional<ItemStack>> snapshot() {
        List<Optional<ItemStack>> result = new ArrayList<>(slots.length);
        for (ItemStack stack : slots) result.add(Optional.ofNullable(stack));
        return List.copyOf(result);
    }

    /** Applies a client proposal only when every old value matches and item counts are conserved. */
    public synchronized boolean applySlotChanges(List<SlotChange> changes) {
        Objects.requireNonNull(changes, "changes");
        if (changes.isEmpty() || changes.size() > 64) return false;
        boolean[] touched = new boolean[slots.length];
        HashMap<String, Integer> deltas = new HashMap<>();
        for (SlotChange change : changes) {
            if (change == null || change.slot() < 0 || change.slot() >= slots.length
                    || touched[change.slot()]) return false;
            touched[change.slot()] = true;
            ItemStack current = slots[change.slot()];
            if (!Objects.equals(current, change.from().orElse(null))) return false;
            ItemStack replacement = change.to().orElse(null);
            if (current != null) deltas.merge(current.itemId(), -current.count(), Integer::sum);
            if (replacement != null) deltas.merge(replacement.itemId(), replacement.count(), Integer::sum);
        }
        if (deltas.values().stream().anyMatch(delta -> delta != 0)) return false;
        for (SlotChange change : changes) slots[change.slot()] = change.to().orElse(null);
        return true;
    }

    /** Moves a quantity between a player inventory and this container without creating or deleting items. */
    public int moveFrom(PlayerInventory player, int playerSlot, int containerSlot, int quantity) {
        Objects.requireNonNull(player, "player");
        checkSlot(containerSlot);
        if (quantity < 1) throw new IllegalArgumentException("quantity must be positive");
        synchronized (TRANSFER_LOCK) {
            synchronized (player) {
                synchronized (this) {
                    ItemStack source = player.getSlot(playerSlot).orElse(null);
                    if (source == null) return 0;
                    ItemStack destination = slots[containerSlot];
                    if (destination != null && !destination.canStackWith(source)) return 0;
                    int capacity = destination == null ? source.maxStackSize()
                            : destination.maxStackSize() - destination.count();
                    int moved = Math.min(Math.min(quantity, source.count()), capacity);
                    if (moved <= 0) return 0;
                    player.setSlot(playerSlot, moved == source.count()
                            ? null : source.withCount(source.count() - moved));
                    slots[containerSlot] = destination == null ? source.withCount(moved)
                            : destination.withCount(destination.count() + moved);
                    return moved;
                }
            }
        }
    }

    /** Moves a quantity from this container into a player inventory without losing overflow. */
    public int moveTo(PlayerInventory player, int containerSlot, int playerSlot, int quantity) {
        Objects.requireNonNull(player, "player");
        checkSlot(containerSlot);
        if (quantity < 1) throw new IllegalArgumentException("quantity must be positive");
        synchronized (TRANSFER_LOCK) {
            synchronized (player) {
                synchronized (this) {
                    ItemStack source = slots[containerSlot];
                    if (source == null) return 0;
                    ItemStack destination = player.getSlot(playerSlot).orElse(null);
                    if (destination != null && !destination.canStackWith(source)) return 0;
                    int capacity = destination == null ? source.maxStackSize()
                            : destination.maxStackSize() - destination.count();
                    int moved = Math.min(Math.min(quantity, source.count()), capacity);
                    if (moved <= 0) return 0;
                    slots[containerSlot] = moved == source.count()
                            ? null : source.withCount(source.count() - moved);
                    player.setSlot(playerSlot, destination == null ? source.withCount(moved)
                            : destination.withCount(destination.count() + moved));
                    return moved;
                }
            }
        }
    }

    public synchronized void restore(List<Optional<ItemStack>> snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.size() != slots.length) {
            throw new IllegalArgumentException("Container snapshot size does not match this container");
        }
        ItemStack[] restored = new ItemStack[slots.length];
        for (int i = 0; i < snapshot.size(); i++) {
            restored[i] = Objects.requireNonNull(snapshot.get(i), "snapshot item").orElse(null);
        }
        System.arraycopy(restored, 0, slots, 0, slots.length);
    }

    private void checkSlot(int slot) {
        if (slot < 0 || slot >= slots.length) {
            throw new IndexOutOfBoundsException("Invalid container slot: " + slot);
        }
    }

    public record SlotChange(int slot, Optional<ItemStack> from, Optional<ItemStack> to) {
        public SlotChange {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }
}
