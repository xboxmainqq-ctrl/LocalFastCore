package ru.nexora.world;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Optional;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Server-authoritative in-memory Overworld. Persistence is a separate layer added next. */
public final class World {
    private final String name;
    private final long seed;
    private final WorldGenerator generator;
    private final int spawnX;
    private final int spawnY;
    private final int spawnZ;
    private final WorldStorage storage;
    private final WorldChunkLoadService chunkLoadService;
    private final WorldChunkSaveService chunkSaveService;
    private final ConcurrentMap<Long, WorldChunk> chunks = new ConcurrentHashMap<>();
    private final Object pendingChunkLoadLock = new Object();
    private final LinkedHashSet<Long> pendingChunkLoads = new LinkedHashSet<>();
    private final WorldItemEntityManager itemEntities;
    private final WorldLivingEntityManager livingEntities;
    private final WorldContainerManager containers;
    private final WorldFurnaceManager furnaces;
    private final MobSpawnerManager mobSpawners;
    private final CopyOnWriteArrayList<WorldBlockChangeListener> blockChangeListeners = new CopyOnWriteArrayList<>();

    public World(String name, long seed, WorldGenerator generator) {
        this(name, seed, generator, 0, 65, 0, null);
    }

    public World(String name, long seed, WorldGenerator generator, int spawnX, int spawnY, int spawnZ) {
        this(name, seed, generator, spawnX, spawnY, spawnZ, null);
    }

    public World(
            String name, long seed, WorldGenerator generator,
            int spawnX, int spawnY, int spawnZ, WorldStorage storage
    ) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("World name must not be blank");
        if (spawnY <= WorldChunk.MIN_Y || spawnY > WorldChunk.MAX_Y) {
            throw new IllegalArgumentException("Spawn Y must be inside the Overworld build height");
        }
        this.name = name;
        this.seed = seed;
        this.generator = Objects.requireNonNull(generator, "generator");
        this.spawnX = spawnX;
        this.spawnY = spawnY;
        this.spawnZ = spawnZ;
        this.storage = storage;
        this.chunkLoadService = storage == null ? null : new WorldChunkLoadService(storage);
        this.chunkSaveService = storage == null ? null : new WorldChunkSaveService(storage);
        this.itemEntities = new WorldItemEntityManager();
        this.livingEntities = new WorldLivingEntityManager();
        this.containers = null;
        this.furnaces = null;
        this.mobSpawners = createMobSpawnerManager(storage);
        addBlockChangeListener(change -> mobSpawners.onBlockChanged(change, Math.max(0L, System.nanoTime() / 50_000_000L)));
    }

    /** Creates a world with durable dropped-item actor storage loaded before play begins. */
    public World(
            String name, long seed, WorldGenerator generator,
            int spawnX, int spawnY, int spawnZ,
            WorldStorage storage, WorldItemEntityStorage itemStorage
    ) throws IOException {
        this(name, seed, generator, spawnX, spawnY, spawnZ, storage, itemStorage, null, null);
    }

    /** Creates a world with durable item actors and block-container inventories. */
    public World(
            String name, long seed, WorldGenerator generator,
            int spawnX, int spawnY, int spawnZ,
            WorldStorage storage, WorldItemEntityStorage itemStorage,
            ru.nexora.inventory.ContainerInventoryStorage containerStorage
    ) throws IOException {
        this(name, seed, generator, spawnX, spawnY, spawnZ,
                storage, itemStorage, containerStorage, null);
    }

    /** Creates a world with durable item actors, containers, and furnace state. */
    public World(
            String name, long seed, WorldGenerator generator,
            int spawnX, int spawnY, int spawnZ,
            WorldStorage storage, WorldItemEntityStorage itemStorage,
            ru.nexora.inventory.ContainerInventoryStorage containerStorage,
            ru.nexora.inventory.FurnaceStateStorage furnaceStateStorage
    ) throws IOException {
        this(name, seed, generator, spawnX, spawnY, spawnZ,
                storage, itemStorage, containerStorage, furnaceStateStorage, null);
    }

    /** Creates a world with durable dropped items, containers, furnaces and living actors. */
    public World(
            String name, long seed, WorldGenerator generator,
            int spawnX, int spawnY, int spawnZ,
            WorldStorage storage, WorldItemEntityStorage itemStorage,
            ru.nexora.inventory.ContainerInventoryStorage containerStorage,
            ru.nexora.inventory.FurnaceStateStorage furnaceStateStorage,
            WorldLivingEntityStorage livingEntityStorage
    ) throws IOException {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("World name must not be blank");
        if (spawnY <= WorldChunk.MIN_Y || spawnY > WorldChunk.MAX_Y) {
            throw new IllegalArgumentException("Spawn Y must be inside the Overworld build height");
        }
        this.name = name;
        this.seed = seed;
        this.generator = Objects.requireNonNull(generator, "generator");
        this.spawnX = spawnX;
        this.spawnY = spawnY;
        this.spawnZ = spawnZ;
        this.storage = storage;
        this.chunkLoadService = storage == null ? null : new WorldChunkLoadService(storage);
        this.chunkSaveService = storage == null ? null : new WorldChunkSaveService(storage);
        this.itemEntities = itemStorage == null
                ? new WorldItemEntityManager()
                : new WorldItemEntityManager(itemStorage);
        this.livingEntities = livingEntityStorage == null
                ? new WorldLivingEntityManager()
                : new WorldLivingEntityManager(livingEntityStorage);
        this.containers = containerStorage == null ? null : new WorldContainerManager(containerStorage);
        this.furnaces = containerStorage == null || furnaceStateStorage == null ? null
                : new WorldFurnaceManager(containerStorage, furnaceStateStorage);
        this.mobSpawners = createMobSpawnerManager(storage);
        addBlockChangeListener(change -> mobSpawners.onBlockChanged(change, Math.max(0L, System.nanoTime() / 50_000_000L)));
    }

    public String name() {
        return name;
    }

    public long seed() {
        return seed;
    }

    public int spawnX() { return spawnX; }
    public int spawnY() { return spawnY; }
    public int spawnZ() { return spawnZ; }

    public WorldChunk getChunk(int chunkX, int chunkZ) {
        long key = chunkKey(chunkX, chunkZ);
        return chunks.computeIfAbsent(key, ignored -> {
            if (storage != null) {
                try {
                    Optional<WorldChunk> stored = storage.loadChunk(chunkX, chunkZ);
                    if (stored.isPresent()) return stored.get();
                } catch (IOException exception) {
                    throw new UncheckedIOException("Unable to load NEXORA chunk " + chunkX + "," + chunkZ, exception);
                }
            }
            WorldChunk chunk = new WorldChunk(chunkX, chunkZ);
            generator.generate(chunk, seed);
            return chunk;
        });
    }

    /** Requests only the persisted chunk read; it never invokes the world generator on a worker. */
    public java.util.concurrent.CompletableFuture<Optional<WorldChunk>> requestStoredChunkLoad(
            int chunkX, int chunkZ
    ) {
        return requestStoredChunkLoad("default", chunkX, chunkZ);
    }

    public java.util.concurrent.CompletableFuture<Optional<WorldChunk>> requestStoredChunkLoad(
            String sessionKey, int chunkX, int chunkZ
    ) {
        WorldChunk loaded = chunks.get(chunkKey(chunkX, chunkZ));
        if (loaded != null) return java.util.concurrent.CompletableFuture.completedFuture(Optional.of(loaded));
        if (chunkLoadService == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
        }
        return chunkLoadService.request(sessionKey, chunkX, chunkZ);
    }

    /**
     * Completes a Bedrock streaming load on the world thread. A concurrently loaded live
     * chunk always wins over the detached disk snapshot.
     */
    public WorldChunk getChunkFromPreparedStorageLoad(
            int chunkX, int chunkZ, Optional<WorldChunk> prepared
    ) {
        Objects.requireNonNull(prepared, "prepared");
        long key = chunkKey(chunkX, chunkZ);
        WorldChunk existing = chunks.get(key);
        if (existing != null) return existing;
        if (prepared.isPresent()) {
            WorldChunk candidate = prepared.get();
            if (candidate.chunkX() != chunkX || candidate.chunkZ() != chunkZ) {
                throw new IllegalArgumentException("Prepared chunk coordinates do not match request");
            }
            WorldChunk raced = chunks.putIfAbsent(key, candidate);
            return raced == null ? candidate : raced;
        }
        return chunks.computeIfAbsent(key, ignored -> {
            WorldChunk generated = new WorldChunk(chunkX, chunkZ);
            generator.generate(generated, seed);
            return generated;
        });
    }

    public int activeChunkIoWorkers() {
        return chunkLoadService == null ? 0 : chunkLoadService.activeWorkers();
    }

    public int queuedChunkIoLoads() {
        return chunkLoadService == null ? 0 : chunkLoadService.queuedLoads();
    }

    public int inFlightChunkIoLoads() {
        return chunkLoadService == null ? 0 : chunkLoadService.inFlightLoads();
    }

    /** Stops bounded chunk-storage workers during world shutdown. */
    public void closeChunkLoadService() {
        RuntimeException failure = null;
        if (chunkLoadService != null) {
            try { chunkLoadService.close(); } catch (RuntimeException exception) { failure = exception; }
        }
        if (chunkSaveService != null) {
            try { chunkSaveService.close(); } catch (RuntimeException exception) {
                if (failure == null) failure = exception; else failure.addSuppressed(exception);
            }
        }
        if (failure != null) throw failure;
    }

    public int activeChunkSaveWorkers() {
        return chunkSaveService == null ? 0 : chunkSaveService.activeWorkers();
    }

    public int queuedChunkSaves() {
        return chunkSaveService == null ? 0 : chunkSaveService.queuedSaves();
    }

    public int inFlightChunkSaves() {
        return chunkSaveService == null ? 0 : chunkSaveService.inFlightSaves();
    }

    public BlockType getBlock(int blockX, int y, int blockZ) {
        int chunkX = Math.floorDiv(blockX, 16);
        int chunkZ = Math.floorDiv(blockZ, 16);
        int localX = Math.floorMod(blockX, 16);
        int localZ = Math.floorMod(blockZ, 16);
        return getChunk(chunkX, chunkZ).getBlock(localX, y, localZ);
    }

    /**
     * Changes one block atomically within its chunk and publishes a runtime change event.
     * Generator and storage loading paths intentionally bypass this runtime event stream.
     */
    public void setBlock(int blockX, int y, int blockZ, BlockType type) {
        Objects.requireNonNull(type, "type");
        int chunkX = Math.floorDiv(blockX, 16);
        int chunkZ = Math.floorDiv(blockZ, 16);
        int localX = Math.floorMod(blockX, 16);
        int localZ = Math.floorMod(blockZ, 16);
        long key = chunkKey(chunkX, chunkZ);
        while (true) {
            WorldChunk chunk = getChunk(chunkX, chunkZ);
            // An unload may have removed this instance while this mutation waited
            // for its monitor. Never mutate a detached chunk: retry against the
            // currently authoritative map entry instead.
            synchronized (chunk) {
                if (chunks.get(key) != chunk) continue;
                BlockType previous = chunk.setBlockAndGetPrevious(localX, y, localZ, type);
                if (previous == type) return;
                BlockChange change = new BlockChange(blockX, y, blockZ, previous, type);
                for (WorldBlockChangeListener listener : blockChangeListeners) {
                    listener.onBlockChanged(change);
                }
                return;
            }
        }
    }

    /** Registers a runtime block-change listener and returns a handle that unregisters it. */
    public AutoCloseable addBlockChangeListener(WorldBlockChangeListener listener) {
        Objects.requireNonNull(listener, "listener");
        blockChangeListeners.add(listener);
        return () -> blockChangeListeners.remove(listener);
    }

    public record BlockChange(int x, int y, int z, BlockType previous, BlockType current) {
        public BlockChange {
            Objects.requireNonNull(previous, "previous");
            Objects.requireNonNull(current, "current");
        }
    }

    /** Returns the authoritative dropped-item actor manager for this world. */
    public WorldItemEntityManager itemEntities() {
        return itemEntities;
    }

    /** Returns the authoritative living-actor manager for mobs and damageable world entities. */
    public WorldLivingEntityManager livingEntities() {
        return livingEntities;
    }

    /** Returns the persistent block-container manager when container storage is configured. */
    public Optional<WorldContainerManager> containers() {
        return Optional.ofNullable(containers);
    }

    /** Returns the persistent furnace manager when furnace storage is configured. */
    public Optional<WorldFurnaceManager> furnaces() {
        return Optional.ofNullable(furnaces);
    }

    /** Returns the world-scoped mob spawner controller. */
    public MobSpawnerManager mobSpawners() {
        return mobSpawners;
    }

    /**
     * Persists and evicts one cached chunk. Chunks are never evicted without durable
     * storage because doing so could discard player block changes.
     */
    public boolean unloadChunk(int chunkX, int chunkZ) {
        if (storage == null) return false;
        long key = chunkKey(chunkX, chunkZ);
        while (true) {
            WorldChunk chunk = chunks.get(key);
            if (chunk == null) return false;
            try {
                // Disk I/O runs on the bounded save workers, not the world tick.
                // A mutation racing this write leaves the chunk dirty and forces
                // another snapshot before eviction.
                chunkSaveService.request(chunk).join();
            } catch (java.util.concurrent.CompletionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof IOException io) {
                    throw new UncheckedIOException(
                            "Unable to persist NEXORA chunk before unload " + chunkX + "," + chunkZ, io);
                }
                throw failure;
            }
            synchronized (chunk) {
                if (chunks.get(key) != chunk) continue;
                if (chunk.isDirty()) continue;
                // setBlock verifies this same identity under the chunk monitor,
                // so no mutation can be applied to the chunk after this removal.
                return chunks.remove(key, chunk);
            }
        }
    }

    /**
     * Applies a residency plan to the real world cache. Requested unloads are persisted
     * before eviction; when durable storage is disabled, chunks remain cached safely.
     */
    public ChunkResidencyResult applyChunkResidencyPlan(
            WorldChunkResidencyManager.ResidencyPlan plan
    ) {
        return applyChunkResidencyPlan(plan, Integer.MAX_VALUE);
    }

    /**
     * Applies a residency plan while limiting synchronous chunk loads to a world-wide
     * budget. Deferred coordinates remain in insertion order and are deduplicated
     * across players and subsequent world ticks.
     */
    public ChunkResidencyResult applyChunkResidencyPlan(
            WorldChunkResidencyManager.ResidencyPlan plan, int maximumLoads
    ) {
        Objects.requireNonNull(plan, "plan");
        if (maximumLoads < 0) throw new IllegalArgumentException("maximumLoads must not be negative");

        int unloaded = 0;
        synchronized (pendingChunkLoadLock) {
            for (WorldChunkResidencyManager.ChunkCoordinate coordinate : plan.unload()) {
                pendingChunkLoads.remove(chunkKey(coordinate.chunkX(), coordinate.chunkZ()));
                if (unloadChunk(coordinate.chunkX(), coordinate.chunkZ())) unloaded++;
            }
            for (WorldChunkResidencyManager.ChunkCoordinate coordinate : plan.load()) {
                long key = chunkKey(coordinate.chunkX(), coordinate.chunkZ());
                if (!chunks.containsKey(key)) pendingChunkLoads.add(key);
            }

            int loadRequested = pendingChunkLoads.size();
            int loaded = 0;
            Iterator<Long> iterator = pendingChunkLoads.iterator();
            while (iterator.hasNext() && loaded < maximumLoads) {
                long key = iterator.next();
                int chunkX = (int) (key >> 32);
                int chunkZ = (int) key;
                if (!chunks.containsKey(key)) {
                    getChunk(chunkX, chunkZ);
                    loaded++;
                }
                iterator.remove();
            }
            return new ChunkResidencyResult(loadRequested, loaded,
                    plan.unload().size(), unloaded, plan.unload().size() - unloaded);
        }
    }

    /** Number of globally deferred chunk loads waiting for a future world tick. */
    public int pendingChunkLoadCount() {
        synchronized (pendingChunkLoadLock) {
            return pendingChunkLoads.size();
        }
    }

    public record ChunkResidencyResult(
            int loadRequested, int loaded, int unloadRequested, int unloaded, int retained
    ) {
        public ChunkResidencyResult {
            if (loadRequested < 0 || loaded < 0 || unloadRequested < 0
                    || unloaded < 0 || retained < 0
                    || loaded > loadRequested || unloaded + retained != unloadRequested) {
                throw new IllegalArgumentException("Invalid world chunk residency result");
            }
        }
    }

    public int loadedChunkCount() {
        return chunks.size();
    }

    /** Immutable coordinates for every chunk currently cached, including direct API loads. */
    public java.util.Set<WorldChunkResidencyManager.ChunkCoordinate> loadedChunkCoordinates() {
        java.util.Set<WorldChunkResidencyManager.ChunkCoordinate> coordinates = new java.util.HashSet<>();
        for (WorldChunk chunk : chunks.values()) {
            coordinates.add(new WorldChunkResidencyManager.ChunkCoordinate(
                    chunk.chunkX(), chunk.chunkZ()));
        }
        return java.util.Set.copyOf(coordinates);
    }

    /** Exact block-array payload across cached chunks; excludes maps, actors and JVM object overhead. */
    public long loadedChunkBlockStorageBytes() {
        long total = 0L;
        for (WorldChunk chunk : chunks.values()) {
            long bytes = chunk.allocatedBlockStorageBytes();
            total = total > Long.MAX_VALUE - bytes ? Long.MAX_VALUE : total + bytes;
        }
        return total;
    }

    /** Number of allocated 4096-byte block sections across the current world cache. */
    public long loadedChunkSectionCount() {
        long total = 0L;
        for (WorldChunk chunk : chunks.values()) {
            total += chunk.allocatedSectionCount();
        }
        return total;
    }

    /** Checks residency without causing a chunk load. */
    public boolean isChunkLoadedAt(int blockX, int blockZ) {
        return chunks.containsKey(chunkKey(Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16)));
    }

    /**
     * Reads a block only when its chunk is already resident.
     *
     * @return the block, or {@code null} when the chunk is not loaded; never triggers
     *         generation or disk I/O. Collision queries use this to avoid a separate
     *         residency lookup followed by a second chunk-map lookup.
     */
    public BlockType getLoadedBlockOrNull(int blockX, int y, int blockZ) {
        int chunkX = Math.floorDiv(blockX, 16);
        int chunkZ = Math.floorDiv(blockZ, 16);
        WorldChunk chunk = chunks.get(chunkKey(chunkX, chunkZ));
        if (chunk == null) return null;
        return chunk.getBlock(Math.floorMod(blockX, 16), y, Math.floorMod(blockZ, 16));
    }

    public void saveAll() throws IOException {
        if (storage != null) {
            java.util.ArrayList<java.util.concurrent.CompletableFuture<Void>> batch =
                    new java.util.ArrayList<>(128);
            for (WorldChunk chunk : chunks.values()) {
                if (!chunk.isDirty()) continue;
                batch.add(chunkSaveService.request(chunk));
                if (batch.size() == 128) {
                    awaitChunkSaveBatch(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) awaitChunkSaveBatch(batch);
        }
        itemEntities.save();
        livingEntities.save();
        mobSpawners.save();
        if (containers != null) containers.saveAll();
        if (furnaces != null) furnaces.saveAll();
    }

    private static void awaitChunkSaveBatch(java.util.List<java.util.concurrent.CompletableFuture<Void>> batch)
            throws IOException {
        try {
            java.util.concurrent.CompletableFuture.allOf(
                    batch.toArray(java.util.concurrent.CompletableFuture[]::new)).join();
        } catch (java.util.concurrent.CompletionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("Unable to persist NEXORA chunks", cause == null ? failure : cause);
        }
    }

    private static MobSpawnerManager createMobSpawnerManager(WorldStorage storage) {
        if (storage == null) return new MobSpawnerManager();
        try {
            return new MobSpawnerManager(new MobSpawnerStorage(storage.worldDirectory()));
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to load NEXORA mob spawners", exception);
        }
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffff_ffffL);
    }
}
