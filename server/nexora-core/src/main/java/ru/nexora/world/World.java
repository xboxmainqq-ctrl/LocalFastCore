package ru.nexora.world;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Optional;
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
    private final ConcurrentMap<Long, WorldChunk> chunks = new ConcurrentHashMap<>();
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
        addBlockChangeListener(change -> mobSpawners.onBlockChanged(change, System.currentTimeMillis() / 50L));
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
        WorldChunk chunk = getChunk(chunkX, chunkZ);
        // Keep each chunk's mutation and corresponding event publication ordered,
        // without serialising independent chunks against one another.
        synchronized (chunk) {
            BlockType previous = chunk.setBlockAndGetPrevious(localX, y, localZ, type);
            if (previous == type) return;
            BlockChange change = new BlockChange(blockX, y, blockZ, previous, type);
            for (WorldBlockChangeListener listener : blockChangeListeners) {
                listener.onBlockChanged(change);
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

    public int loadedChunkCount() {
        return chunks.size();
    }

    /** Checks residency without causing a chunk load. */
    public boolean isChunkLoadedAt(int blockX, int blockZ) {
        return chunks.containsKey(chunkKey(Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16)));
    }

    public void saveAll() throws IOException {
        if (storage != null) {
            for (WorldChunk chunk : chunks.values()) storage.saveChunk(chunk);
        }
        itemEntities.save();
        livingEntities.save();
        mobSpawners.save();
        if (containers != null) containers.saveAll();
        if (furnaces != null) furnaces.saveAll();
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
