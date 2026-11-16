package ru.nexora.inventory;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Authoritative player equipment state, kept separate from the 36 main inventory slots.
 * Logical magical slots are deliberately independent from Bedrock UI container IDs.
 */
public final class PlayerEquipment {
    public static final int ARMOR_SLOTS = 4;
    public static final int MAGIC_SLOTS = EquipmentSlot.values().length;

    private final ItemStack[] armor = new ItemStack[ARMOR_SLOTS];
    private final ItemStack[] magic = new ItemStack[MAGIC_SLOTS];
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
        return Arrays.stream(armor).map(Optional::ofNullable).toList();
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

    public synchronized Optional<ItemStack> magicSlot(EquipmentSlot slot) {
        return Optional.ofNullable(magic[magicIndex(slot)]);
    }

    public synchronized void setMagicSlot(EquipmentSlot slot, ItemStack stack) {
        magic[magicIndex(slot)] = stack;
    }

    public synchronized List<Optional<ItemStack>> magicSnapshot() {
        return Arrays.stream(magic).map(Optional::ofNullable).toList();
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(armorSnapshot(), Optional.ofNullable(offhand),
                Optional.ofNullable(cursor), magicSnapshot());
    }

    public synchronized void restore(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        for (int i = 0; i < ARMOR_SLOTS; i++) armor[i] = snapshot.armor().get(i).orElse(null);
        offhand = snapshot.offhand().orElse(null);
        cursor = snapshot.cursor().orElse(null);
        for (int i = 0; i < MAGIC_SLOTS; i++) magic[i] = snapshot.magicSlots().get(i).orElse(null);
    }

    private static int magicIndex(EquipmentSlot slot) {
        return Objects.requireNonNull(slot, "slot").ordinal();
    }

    private static void checkArmorSlot(int slot) {
        if (slot < 0 || slot >= ARMOR_SLOTS) {
            throw new IndexOutOfBoundsException("Invalid armor slot: " + slot);
        }
    }

    public record Snapshot(List<Optional<ItemStack>> armor, Optional<ItemStack> offhand,
                           Optional<ItemStack> cursor, List<Optional<ItemStack>> magicSlots) {
        /** Backward-compatible constructor for callers creating pre-magic-slot snapshots. */
        public Snapshot(List<Optional<ItemStack>> armor, Optional<ItemStack> offhand,
                        Optional<ItemStack> cursor) {
            this(armor, offhand, cursor,
                    java.util.Collections.nCopies(MAGIC_SLOTS, Optional.empty()));
        }

        public Snapshot {
            armor = List.copyOf(Objects.requireNonNull(armor, "armor"));
            if (armor.size() != ARMOR_SLOTS) {
                throw new IllegalArgumentException("Exactly four armor slots are required");
            }
            for (Optional<ItemStack> item : armor) Objects.requireNonNull(item, "armor item");
            Objects.requireNonNull(offhand, "offhand");
            Objects.requireNonNull(cursor, "cursor");
            magicSlots = List.copyOf(Objects.requireNonNull(magicSlots, "magicSlots"));
            if (magicSlots.size() != MAGIC_SLOTS) {
                throw new IllegalArgumentException("Invalid magical equipment slot count");
            }
            for (Optional<ItemStack> item : magicSlots) Objects.requireNonNull(item, "magic slot item");
        }
    }
}
