package ru.nexora.inventory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Authoritative equipment and UI cursor state, kept separate from the 36 main inventory slots. */
public final class PlayerEquipment {
    public static final int ARMOR_SLOTS = 4;
    private final ItemStack[] armor = new ItemStack[ARMOR_SLOTS];
    private ItemStack offhand;
    private ItemStack cursor;

    public synchronized Optional<ItemStack> armor(int slot) {
        checkArmorSlot(slot);
        return Optional.ofNullable(armor[slot]);
    }

    public synchronized void setArmor(int slot, ItemStack stack) {
        checkArmorSlot(slot);
        armor[slot] = stack;
    }

    public synchronized List<Optional<ItemStack>> armorSnapshot() {
        return java.util.Arrays.stream(armor).map(Optional::ofNullable).toList();
    }

    public synchronized Optional<ItemStack> offhand() {
        return Optional.ofNullable(offhand);
    }

    public synchronized void setOffhand(ItemStack stack) {
        offhand = stack;
    }

    public synchronized Optional<ItemStack> cursor() {
        return Optional.ofNullable(cursor);
    }

    public synchronized void setCursor(ItemStack stack) {
        cursor = stack;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(armorSnapshot(), Optional.ofNullable(offhand), Optional.ofNullable(cursor));
    }

    public synchronized void restore(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        for (int i = 0; i < ARMOR_SLOTS; i++) armor[i] = snapshot.armor().get(i).orElse(null);
        offhand = snapshot.offhand().orElse(null);
        cursor = snapshot.cursor().orElse(null);
    }

    private static void checkArmorSlot(int slot) {
        if (slot < 0 || slot >= ARMOR_SLOTS) throw new IndexOutOfBoundsException("Invalid armor slot: " + slot);
    }

    public record Snapshot(List<Optional<ItemStack>> armor, Optional<ItemStack> offhand,
                           Optional<ItemStack> cursor) {
        public Snapshot {
            armor = List.copyOf(Objects.requireNonNull(armor, "armor"));
            if (armor.size() != ARMOR_SLOTS) throw new IllegalArgumentException("Exactly four armor slots are required");
            for (Optional<ItemStack> item : armor) Objects.requireNonNull(item, "armor item");
            Objects.requireNonNull(offhand, "offhand");
            Objects.requireNonNull(cursor, "cursor");
        }
    }
}
