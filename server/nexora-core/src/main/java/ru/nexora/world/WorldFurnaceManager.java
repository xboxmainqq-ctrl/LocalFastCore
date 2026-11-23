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
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/** World-scoped furnace inventories and processing state with durable storage. */
public final class WorldFurnaceManager {
    private final ContainerInventoryStorage inventoryStorage;
    private final FurnaceStateStorage stateStorage;
    private final FurnaceService service = new FurnaceService();
    private final Map<Position, ContainerInventory> loaded = new HashMap<>();
    private final Map<Position, FurnaceService.Kind> kinds = new HashMap<>();
    private final CopyOnWriteArrayList<Consumer<FurnaceUpdate>> updateListeners = new CopyOnWriteArrayList<>();
    private final LongAdder tickBatches = new LongAdder();
    private final LongAdder furnacesProcessed = new LongAdder();
    private final LongAdder changedFurnaces = new LongAdder();
    private final LongAdder totalTickNanos = new LongAdder();
    private final java.util.concurrent.atomic.AtomicLong maxTickNanos = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong lastTickNanos = new java.util.concurrent.atomic.AtomicLong();

    public WorldFurnaceManager(ContainerInventoryStorage inventoryStorage, FurnaceStateStorage stateStorage) {
        this.inventoryStorage = Objects.requireNonNull(inventoryStorage, "inventoryStorage");
        this.stateStorage = Objects.requireNonNull(stateStorage, "stateStorage");
    }

    public synchronized ContainerInventory getOrCreate(int x, int y, int z) throws IOException {
        return getOrCreate(x, y, z, FurnaceService.Kind.FURNACE);
    }

    public synchronized ContainerInventory getOrCreate(
            int x, int y, int z, FurnaceService.Kind kind
    ) throws IOException {
        Objects.requireNonNull(kind, "kind");
        Position position = new Position(x, y, z);
        ContainerInventory existing = loaded.get(position);
        if (existing != null) {
            FurnaceService.Kind previousKind = kinds.put(position, kind);
            if (previousKind != null && previousKind != kind) {
                FurnaceService.FurnaceStateSnapshot state = service.snapshotState(x, y, z);
                int scaledProgress = (int) Math.round(
                        state.cookProgress() * (double) kind.cookTimeTicks() / previousKind.cookTimeTicks());
                scaledProgress = Math.min(kind.cookTimeTicks() - 1, scaledProgress);
                service.restoreState(x, y, z, state.burnTicksRemaining(),
                        scaledProgress, state.burnTicksTotal(), state.pendingExperienceMilli());
            }
            return existing;
        }
        ContainerInventory inventory = inventoryStorage.load(x, y, z)
                .orElseGet(() -> new ContainerInventory(3));
        if (inventory.size() != 3) throw new IOException("Stored furnace inventory must contain exactly 3 slots");
        stateStorage.load(x, y, z).ifPresent(state ->
                service.restoreState(x, y, z, state.burnTicksRemaining(), state.cookProgress(),
                        state.burnTicksTotal(), state.pendingExperienceMilli()));
        loaded.put(position, inventory);
        kinds.put(position, kind);
        return inventory;
    }

    /** Advances one loaded furnace in memory; save() or world autosave persists its inventory and timers. */
    public FurnaceService.TickResult tick(int x, int y, int z, long elapsedTicks) throws IOException {
        return tick(x, y, z, FurnaceService.Kind.FURNACE, elapsedTicks);
    }

    public FurnaceService.TickResult tick(
            int x, int y, int z, FurnaceService.Kind kind, long elapsedTicks
    ) throws IOException {
        FurnaceUpdate update;
        FurnaceService.TickResult result;
        synchronized (this) {
            ContainerInventory inventory = getOrCreate(x, y, z, kind);
            FurnaceService.FurnaceStateSnapshot before = service.snapshotState(x, y, z);
            result = service.tick(x, y, z, inventory, kind, elapsedTicks);
            update = updateIfChanged(x, y, z, inventory, before, result);
        }
        publish(update);
        return result;
    }

    /** Advances every loaded furnace by the supplied number of server ticks. */
    public void tickAll(long elapsedTicks) {
        long started = System.nanoTime();
        java.util.List<FurnaceUpdate> updates = new java.util.ArrayList<>();
        synchronized (this) {
            for (var entry : loaded.entrySet()) {
                Position p = entry.getKey();
                furnacesProcessed.increment();
                FurnaceService.Kind kind = kinds.getOrDefault(p, FurnaceService.Kind.FURNACE);
                FurnaceService.FurnaceStateSnapshot before = service.snapshotState(p.x(), p.y(), p.z());
                FurnaceService.TickResult result =
                        service.tick(p.x(), p.y(), p.z(), entry.getValue(), kind, elapsedTicks);
                FurnaceUpdate update = updateIfChanged(p.x(), p.y(), p.z(), entry.getValue(), before, result);
                if (update != null) updates.add(update);
            }
        }
        updates.forEach(this::publish);
        changedFurnaces.add(updates.size());
        tickBatches.increment();
        long elapsedNanos = Math.max(0L, System.nanoTime() - started);
        totalTickNanos.add(elapsedNanos);
        maxTickNanos.accumulateAndGet(elapsedNanos, Math::max);
        lastTickNanos.set(elapsedNanos);
    }

    /** Returns cumulative, aggregate-only furnace workload and latency measurements. */
    public FurnaceTickStats tickStats() {
        long batches = tickBatches.sum();
        long processed = furnacesProcessed.sum();
        long total = totalTickNanos.sum();
        return new FurnaceTickStats(batches, processed, changedFurnaces.sum(),
                total, maxTickNanos.get(), lastTickNanos.get(),
                batches == 0 ? 0.0 : total / (double) batches / 1_000_000.0);
    }

    /** Captures burn, cook, and pending-experience state for an atomic block-break rollback. */
    public synchronized FurnaceService.FurnaceStateSnapshot snapshotState(int x, int y, int z) {
        return service.snapshotState(x, y, z);
    }

    /** Restores a previously captured furnace state after a failed inventory/drop transaction. */
    public synchronized void restoreState(
            int x, int y, int z, FurnaceService.FurnaceStateSnapshot snapshot
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        service.restoreState(x, y, z, snapshot.burnTicksRemaining(), snapshot.cookProgress(),
                snapshot.burnTicksTotal(), snapshot.pendingExperienceMilli());
    }

    /** Whether a furnace still owns unclaimed smelting experience that must not be discarded. */
    public synchronized boolean hasPendingExperience(int x, int y, int z) {
        return service.snapshotState(x, y, z).pendingExperienceMilli() > 0;
    }

    /** Claims the pending smelting experience for items removed from the output slot. */
    public synchronized int collectExperience(int x, int y, int z, int removedItems, int previousOutputCount) {
        return service.collectExperience(x, y, z, removedItems, previousOutputCount);
    }

    /** Restores claimed experience when the corresponding inventory transaction is rolled back. */
    public synchronized void restorePendingExperience(int x, int y, int z, int amount) {
        service.restorePendingExperience(x, y, z, amount);
    }

    public AutoCloseable addUpdateListener(Consumer<FurnaceUpdate> listener) {
        Objects.requireNonNull(listener, "listener");
        updateListeners.add(listener);
        return () -> updateListeners.remove(listener);
    }

    private FurnaceUpdate updateIfChanged(int x, int y, int z, ContainerInventory inventory,
                                          FurnaceService.FurnaceStateSnapshot before,
                                          FurnaceService.TickResult result) {
        FurnaceService.FurnaceStateSnapshot after = service.snapshotState(x, y, z);
        return before.equals(after) && !result.inventoryChanged()
                ? null : new FurnaceUpdate(x, y, z, inventory, result);
    }

    private void publish(FurnaceUpdate update) {
        if (update == null) return;
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
        kinds.remove(p);
        service.remove(x, y, z);
        inventoryStorage.delete(x, y, z);
        stateStorage.delete(x, y, z);
    }

    public synchronized int loadedCount() { return loaded.size(); }

    public record FurnaceTickStats(long tickBatches, long furnacesProcessed, long changedFurnaces,
                                   long totalExecutionNanos, long maxExecutionNanos,
                                   long lastExecutionNanos, double averageExecutionMillis) {
    }

    public record FurnaceUpdate(int x, int y, int z, ContainerInventory inventory,
                                FurnaceService.TickResult result) {
        public FurnaceUpdate {
            Objects.requireNonNull(inventory, "inventory");
            Objects.requireNonNull(result, "result");
        }
    }

    private record Position(int x, int y, int z) { }
}
