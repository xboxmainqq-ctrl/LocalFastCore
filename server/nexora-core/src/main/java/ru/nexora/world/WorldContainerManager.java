package ru.nexora.world;

import ru.nexora.inventory.ContainerInventory;
import ru.nexora.inventory.ContainerInventoryStorage;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Lazy, world-scoped cache for persistent block-container inventories. */
public final class WorldContainerManager {
    private final ContainerInventoryStorage storage;
    private final Map<Position, ContainerInventory> loaded = new HashMap<>();

    public WorldContainerManager(ContainerInventoryStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    /** Loads a container from disk once, or creates an empty inventory when none exists. */
    public synchronized ContainerInventory getOrCreate(int x, int y, int z) throws IOException {
        Position position = new Position(x, y, z);
        ContainerInventory existing = loaded.get(position);
        if (existing != null) return existing;
        ContainerInventory inventory = storage.load(x, y, z).orElseGet(ContainerInventory::new);
        loaded.put(position, inventory);
        return inventory;
    }

    /** Returns a container only when it has already been loaded in this world session. */
    public synchronized Optional<ContainerInventory> loaded(int x, int y, int z) {
        return Optional.ofNullable(loaded.get(new Position(x, y, z)));
    }

    public synchronized void save(int x, int y, int z) throws IOException {
        ContainerInventory inventory = loaded.get(new Position(x, y, z));
        if (inventory != null) storage.save(x, y, z, inventory);
    }

    /** Removes a container from memory and deletes its persisted contents. */
    public synchronized void remove(int x, int y, int z) throws IOException {
        Position position = new Position(x, y, z);
        // Keep the authoritative in-memory inventory until both primary and backup
        // files have been deleted successfully. A storage error must not orphan it.
        storage.delete(x, y, z);
        loaded.remove(position);
    }

    /** Saves every loaded container; all failures are reported without skipping later containers. */
    public synchronized void saveAll() throws IOException {
        IOException failure = null;
        for (var entry : loaded.entrySet()) {
            try {
                Position p = entry.getKey();
                storage.save(p.x(), p.y(), p.z(), entry.getValue());
            } catch (IOException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
        if (failure != null) throw failure;
    }

    public synchronized int loadedCount() {
        return loaded.size();
    }

    private record Position(int x, int y, int z) {
    }
}
