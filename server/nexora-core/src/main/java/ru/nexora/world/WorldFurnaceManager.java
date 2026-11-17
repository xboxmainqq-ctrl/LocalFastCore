package ru.nexora.world;

import ru.nexora.inventory.ContainerInventory;
import ru.nexora.inventory.ContainerInventoryStorage;
import ru.nexora.inventory.FurnaceService;
import ru.nexora.inventory.FurnaceStateStorage;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** World-scoped furnace inventories and processing state with durable storage. */
public final class WorldFurnaceManager {
    private final ContainerInventoryStorage inventoryStorage;
    private final FurnaceStateStorage stateStorage;
    private final FurnaceService service = new FurnaceService();
    private final Map<Position, ContainerInventory> loaded = new HashMap<>();

    public WorldFurnaceManager(ContainerInventoryStorage inventoryStorage, FurnaceStateStorage stateStorage) {
        this.inventoryStorage = Objects.requireNonNull(inventoryStorage, "inventoryStorage");
        this.stateStorage = Objects.requireNonNull(stateStorage, "stateStorage");
    }

    public synchronized ContainerInventory getOrCreate(int x, int y, int z) throws IOException {
        Position position = new Position(x, y, z);
        ContainerInventory existing = loaded.get(position);
        if (existing != null) return existing;
        ContainerInventory inventory = inventoryStorage.load(x, y, z)
                .orElseGet(() -> new ContainerInventory(3));
        if (inventory.size() != 3) throw new IOException("Stored furnace inventory must contain exactly 3 slots");
        stateStorage.load(x, y, z).ifPresent(state ->
                service.restoreState(x, y, z, state.burnTicksRemaining(), state.cookProgress()));
        loaded.put(position, inventory);
        return inventory;
    }

    /** Advances one loaded furnace and persists its inventory and timers. */
    public synchronized FurnaceService.TickResult tick(int x, int y, int z, long elapsedTicks)
            throws IOException {
        ContainerInventory inventory = getOrCreate(x, y, z);
        // Processing stays in memory; World.saveAll() flushes all furnace state in one autosave pass.
        return service.tick(x, y, z, inventory, elapsedTicks);
    }

    public synchronized void save(int x, int y, int z) throws IOException {
        ContainerInventory inventory = loaded.get(new Position(x, y, z));
        if (inventory == null) return;
        inventoryStorage.save(x, y, z, inventory);
        FurnaceService.FurnaceStateSnapshot state = service.snapshotState(x, y, z);
        stateStorage.save(x, y, z, state);
    }

    public synchronized void saveAll() throws IOException {
        IOException failure = null;
        for (var entry : loaded.entrySet()) {
            Position p = entry.getKey();
            try {
                inventoryStorage.save(p.x(), p.y(), p.z(), entry.getValue());
                FurnaceService.FurnaceStateSnapshot state =
                        service.snapshotState(p.x(), p.y(), p.z());
                stateStorage.save(p.x(), p.y(), p.z(), state);
            } catch (IOException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
        if (failure != null) throw failure;
    }

    public synchronized void remove(int x, int y, int z) throws IOException {
        Position p = new Position(x, y, z);
        loaded.remove(p);
        service.remove(x, y, z);
        inventoryStorage.delete(x, y, z);
        stateStorage.delete(x, y, z);
    }

    public synchronized int loadedCount() { return loaded.size(); }

    private record Position(int x, int y, int z) { }
}
