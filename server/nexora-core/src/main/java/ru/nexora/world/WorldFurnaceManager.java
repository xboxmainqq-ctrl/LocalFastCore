package ru.nexora.world;

import ru.nexora.inventory.ContainerInventory;
import ru.nexora.inventory.ContainerInventoryStorage;
import ru.nexora.inventory.FurnaceService;
import ru.nexora.inventory.FurnaceStateStorage;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** World-scoped furnace inventories and processing state with durable storage. */
public final class WorldFurnaceManager {
    private final ContainerInventoryStorage inventoryStorage;
    private final FurnaceStateStorage stateStorage;
    private final FurnaceService service = new FurnaceService();
    private final Map<Position, ContainerInventory> loaded = new HashMap<>();
    private final CopyOnWriteArrayList<Consumer<FurnaceUpdate>> updateListeners = new CopyOnWriteArrayList<>();

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
        FurnaceService.FurnaceStateSnapshot before = service.snapshotState(x, y, z);
        FurnaceService.TickResult result = service.tick(x, y, z, inventory, elapsedTicks);
        publishIfChanged(x, y, z, inventory, before, result);
        return result;
    }

    /** Advances every loaded furnace by the supplied number of server ticks. */
    public synchronized void tickAll(long elapsedTicks) {
        for (var entry : loaded.entrySet()) {
            Position p = entry.getKey();
            FurnaceService.FurnaceStateSnapshot before = service.snapshotState(p.x(), p.y(), p.z());
            FurnaceService.TickResult result =
                    service.tick(p.x(), p.y(), p.z(), entry.getValue(), elapsedTicks);
            publishIfChanged(p.x(), p.y(), p.z(), entry.getValue(), before, result);
        }
    }

    public AutoCloseable addUpdateListener(Consumer<FurnaceUpdate> listener) {
        Objects.requireNonNull(listener, "listener");
        updateListeners.add(listener);
        return () -> updateListeners.remove(listener);
    }

    private void publishIfChanged(int x, int y, int z, ContainerInventory inventory,
                                  FurnaceService.FurnaceStateSnapshot before,
                                  FurnaceService.TickResult result) {
        FurnaceService.FurnaceStateSnapshot after = service.snapshotState(x, y, z);
        if (before.equals(after) && !result.inventoryChanged()) return;
        FurnaceUpdate update = new FurnaceUpdate(x, y, z, inventory, result);
        for (Consumer<FurnaceUpdate> listener : updateListeners) {
            try { listener.accept(update); }
            catch (RuntimeException exception) {
                System.err.println("NEXORA: furnace update listener failed: " + exception.getMessage());
            }
        }
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

    public record FurnaceUpdate(int x, int y, int z, ContainerInventory inventory,
                                FurnaceService.TickResult result) {
        public FurnaceUpdate {
            Objects.requireNonNull(inventory, "inventory");
            Objects.requireNonNull(result, "result");
        }
    }

    private record Position(int x, int y, int z) { }
}
