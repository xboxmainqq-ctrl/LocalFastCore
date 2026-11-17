package ru.nexora.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.io.IOException;
import java.util.function.Predicate;

/**
 * Bounded, server-authoritative mob spawner simulation.
 * It never loads chunks itself: only registered spawners in already loaded/placed blocks are ticked.
 */
public final class MobSpawnerManager {
    public static final int ACTIVATION_RADIUS = 16;
    public static final int SPAWN_RADIUS = 4;
    public static final int MAX_NEARBY_SAME_TYPE = 6;
    public static final int MAX_SPAWN_ATTEMPTS = 4;
    public static final int MAX_TRACKED_SPAWNERS = 10_000;

    private final Map<Position, MobSpawnerState> spawners = new LinkedHashMap<>();
    private final MobSpawnerStorage storage;

    public MobSpawnerManager() {
        this.storage = null;
    }

    public MobSpawnerManager(MobSpawnerStorage storage) throws IOException {
        this.storage = Objects.requireNonNull(storage, "storage");
        for (MobSpawnerState state : storage.load()) {
            Position position = new Position(state.x(), state.y(), state.z());
            if (spawners.putIfAbsent(position, state) != null) {
                throw new IOException("Duplicate mob spawner position");
            }
        }
    }

    public synchronized void save() throws IOException {
        if (storage != null) storage.save(snapshot());
    }

    public void onBlockChanged(World.BlockChange change, long currentTick) {
        if (change.current() == BlockType.SPAWNER) register(change.x(), change.y(), change.z(), currentTick);
        else if (change.previous() == BlockType.SPAWNER) remove(change.x(), change.y(), change.z());
    }
    private long spawnCount;
    private long cappedAttempts;
    private long inactiveTicks;
    private long invalidPositionAttempts;

    public synchronized boolean register(int x, int y, int z, long currentTick) {
        if (currentTick < 0 || (spawners.size() >= MAX_TRACKED_SPAWNERS
                && !spawners.containsKey(new Position(x, y, z)))) return false;
        Position position = new Position(x, y, z);
        if (spawners.containsKey(position)) return true;
        spawners.put(position, new MobSpawnerState(x, y, z, "minecraft:zombie",
                MobSpawnerState.DEFAULT_DELAY_TICKS, safeNextTick(currentTick,
                MobSpawnerState.DEFAULT_DELAY_TICKS)));
        return true;
    }

    public synchronized boolean remove(int x, int y, int z) {
        return spawners.remove(new Position(x, y, z)) != null;
    }

    public synchronized boolean configure(int x, int y, int z, String identifier,
                                          int delayTicks, long currentTick) {
        Position position = new Position(x, y, z);
        MobSpawnerState existing = spawners.get(position);
        if (existing == null || currentTick < 0) return false;
        try {
            spawners.put(position, existing.withConfiguration(identifier, delayTicks,
                    safeNextTick(currentTick, delayTicks)));
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public TickResult tick(World world, long currentTick, List<PlayerPosition> players,
                           Predicate<SpawnRequest> spawnHandler) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(players, "players");
        Objects.requireNonNull(spawnHandler, "spawnHandler");
        if (currentTick < 0) throw new IllegalArgumentException("currentTick must be non-negative");

        List<MobSpawnerState> snapshot;
        synchronized (this) { snapshot = List.copyOf(spawners.values()); }
        List<WorldLivingEntity> livingSnapshot = world.livingEntities().snapshot();
        List<SpawnRequest> spawnedThisTick = new ArrayList<>();
        int active = 0;
        int spawned = 0;
        int attempts = 0;
        int capped = 0;
        int invalid = 0;
        for (MobSpawnerState state : snapshot) {
            if (!world.isChunkLoadedAt(state.x(), state.z())) continue;
            if (world.getBlock(state.x(), state.y(), state.z()) != BlockType.SPAWNER) {
                remove(state.x(), state.y(), state.z());
                continue;
            }
            if (!isPlayerNearby(state, players)) {
                synchronized (this) { inactiveTicks++; }
                continue;
            }
            active++;
            if (currentTick < state.nextSpawnTick()) continue;

            int nearby = countNearby(livingSnapshot, spawnedThisTick, state);
            if (nearby >= MAX_NEARBY_SAME_TYPE) {
                capped++;
                updateNext(state, currentTick);
                continue;
            }

            int spawnedFromThis = 0;
            for (int attempt = 0; attempt < MAX_SPAWN_ATTEMPTS
                    && nearby + spawnedFromThis < MAX_NEARBY_SAME_TYPE; attempt++) {
                attempts++;
                SpawnRequest request = candidate(state, currentTick, attempt);
                if (!validSpawnPosition(world, request)) {
                    invalid++;
                    continue;
                }
                if (spawnHandler.test(request)) {
                    spawnedFromThis++;
                    spawned++;
                    spawnedThisTick.add(request);
                }
            }
            updateNext(state, currentTick);
        }
        synchronized (this) {
            spawnCount += spawned;
            cappedAttempts += capped;
            invalidPositionAttempts += invalid;
        }
        return new TickResult(snapshot.size(), active, attempts, spawned, capped, invalid);
    }

    public synchronized List<MobSpawnerState> snapshot() {
        List<MobSpawnerState> result = new ArrayList<>(spawners.values());
        result.sort(Comparator.comparingInt(MobSpawnerState::x)
                .thenComparingInt(MobSpawnerState::y).thenComparingInt(MobSpawnerState::z));
        return List.copyOf(result);
    }

    public synchronized Stats stats() {
        return new Stats(spawners.size(), spawnCount, cappedAttempts, inactiveTicks,
                invalidPositionAttempts);
    }

    private synchronized void updateNext(MobSpawnerState state, long currentTick) {
        Position key = new Position(state.x(), state.y(), state.z());
        MobSpawnerState current = spawners.get(key);
        if (current != null) spawners.put(key,
                current.withNextSpawnTick(safeNextTick(currentTick, current.delayTicks())));
    }

    private static boolean isPlayerNearby(MobSpawnerState state, List<PlayerPosition> players) {
        double centerX = state.x() + 0.5;
        double centerY = state.y() + 0.5;
        double centerZ = state.z() + 0.5;
        double radiusSquared = ACTIVATION_RADIUS * ACTIVATION_RADIUS;
        for (PlayerPosition player : players) {
            double dx = player.x() - centerX;
            double dy = player.y() - centerY;
            double dz = player.z() - centerZ;
            if (dx * dx + dy * dy + dz * dz <= radiusSquared) return true;
        }
        return false;
    }

    private static int countNearby(List<WorldLivingEntity> entities, List<SpawnRequest> spawned,
                                   MobSpawnerState state) {
        int count = 0;
        for (WorldLivingEntity entity : entities) {
            if (!entity.identifier().equals(state.entityIdentifier())) continue;
            if (Math.abs(entity.x() - (state.x() + 0.5)) <= 4.5
                    && Math.abs(entity.y() - (state.y() + 0.5)) <= 4.5
                    && Math.abs(entity.z() - (state.z() + 0.5)) <= 4.5) count++;
        }
        for (SpawnRequest entity : spawned) {
            if (!entity.identifier().equals(state.entityIdentifier())) continue;
            if (Math.abs(entity.x() - (state.x() + 0.5)) <= 4.5
                    && Math.abs(entity.y() - (state.y() + 0.5)) <= 4.5
                    && Math.abs(entity.z() - (state.z() + 0.5)) <= 4.5) count++;
        }
        return count;
    }

    private static SpawnRequest candidate(MobSpawnerState state, long tick, int attempt) {
        long mixed = tick * 0x9E3779B97F4A7C15L + state.x() * 31L
                + state.y() * 131L + state.z() * 8191L + attempt * 0x632BE59BD9B4E019L;
        int dx = Math.floorMod((int) (mixed ^ (mixed >>> 32)), SPAWN_RADIUS * 2 + 1) - SPAWN_RADIUS;
        int dz = Math.floorMod((int) ((mixed >>> 17) ^ (mixed >>> 41)), SPAWN_RADIUS * 2 + 1)
                - SPAWN_RADIUS;
        return new SpawnRequest(state.entityIdentifier(), state.x() + 0.5 + dx,
                state.y(), state.z() + 0.5 + dz);
    }

    private static boolean validSpawnPosition(World world, SpawnRequest request) {
        int x = (int) Math.floor(request.x());
        int y = (int) Math.floor(request.y());
        int z = (int) Math.floor(request.z());
        if (y <= WorldChunk.MIN_Y || y >= WorldChunk.MAX_Y - 1
                || !world.isChunkLoadedAt(x, z)) return false;
        return world.getBlock(x, y, z) == BlockType.AIR
                && world.getBlock(x, y + 1, z) == BlockType.AIR
                && world.getBlock(x, y - 1, z) != BlockType.AIR;
    }

    private static long safeNextTick(long tick, int delay) {
        if (delay < MobSpawnerState.MIN_DELAY_TICKS || delay > MobSpawnerState.MAX_DELAY_TICKS
                || tick > Long.MAX_VALUE - delay) {
            throw new IllegalArgumentException("Invalid spawner delay or tick");
        }
        return tick + delay;
    }

    private record Position(int x, int y, int z) { }

    public record PlayerPosition(double x, double y, double z) {
        public PlayerPosition {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("Player position must be finite");
            }
        }
    }

    public record SpawnRequest(String identifier, double x, double y, double z) { }

    public record TickResult(int trackedSpawners, int activeSpawners, int attempts,
                             int spawned, int capped, int invalidPositions) { }

    public record Stats(int trackedSpawners, long spawned, long cappedAttempts,
                        long inactiveTicks, long invalidPositionAttempts) {
        public String summary() {
            return "tracked=" + trackedSpawners + ", spawned=" + spawned
                    + ", capped=" + cappedAttempts + ", inactiveTicks=" + inactiveTicks
                    + ", invalidPositions=" + invalidPositionAttempts;
        }
    }
}
