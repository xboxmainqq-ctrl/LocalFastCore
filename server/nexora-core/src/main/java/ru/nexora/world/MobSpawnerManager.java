package ru.nexora.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
    /** Hard per-server-tick work budget; remaining spawners are handled round-robin. */
    public static final int MAX_SPAWNERS_PER_TICK = 128;

    private final Map<Position, MobSpawnerState> spawners = new LinkedHashMap<>();
    private final Map<Position, Long> lastVisitedTicks = new LinkedHashMap<>();
    private final MobSpawnerStorage storage;

    public MobSpawnerManager() {
        this.storage = null;
    }

    public MobSpawnerManager(MobSpawnerStorage storage) throws IOException {
        this.storage = Objects.requireNonNull(storage, "storage");
        long nowTick = currentServerTick();
        for (MobSpawnerState stored : storage.load()) {
            MobSpawnerState state;
            try {
                if (stored.nextSpawnTick() > stored.delayTicks()) {
                    throw new IllegalArgumentException("Persisted remaining delay exceeds configured delay");
                }
                state = stored.withNextSpawnTick(safeAddTicks(nowTick, stored.nextSpawnTick()));
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid persisted mob spawner timer", exception);
            }
            Position position = new Position(state.x(), state.y(), state.z());
            if (spawners.putIfAbsent(position, state) != null) {
                throw new IOException("Duplicate mob spawner position");
            }
            lastVisitedTicks.put(position, nowTick);
        }
    }

    public synchronized void save() throws IOException {
        if (storage == null) return;
        long nowTick = currentServerTick();
        List<MobSpawnerState> remaining = new ArrayList<>(spawners.size());
        for (MobSpawnerState state : spawners.values()) {
            long remainingTicks = Math.max(0L, state.nextSpawnTick() - nowTick);
            remaining.add(state.withNextSpawnTick(remainingTicks));
        }
        storage.save(List.copyOf(remaining));
    }

    private static long currentServerTick() {
        return Math.max(0L, System.nanoTime() / 50_000_000L);
    }

    public void onBlockChanged(World.BlockChange change, long currentTick) {
        if (change.current() == BlockType.SPAWNER) register(change.x(), change.y(), change.z(), currentTick);
        else if (change.previous() == BlockType.SPAWNER) remove(change.x(), change.y(), change.z());
    }
    private long spawnCount;
    private long spawnAttempts;
    private long cappedAttempts;
    private long inactiveTicks;
    private long invalidPositionAttempts;
    private int nextSpawnerCursor;
    private long lastProcessedTick = Long.MIN_VALUE;

    public synchronized boolean register(int x, int y, int z, long currentTick) {
        if (currentTick < 0 || (spawners.size() >= MAX_TRACKED_SPAWNERS
                && !spawners.containsKey(new Position(x, y, z)))) return false;
        Position position = new Position(x, y, z);
        if (spawners.containsKey(position)) return true;
        spawners.put(position, new MobSpawnerState(x, y, z, "minecraft:zombie",
                MobSpawnerState.DEFAULT_DELAY_TICKS, safeNextTick(currentTick,
                MobSpawnerState.DEFAULT_DELAY_TICKS)));
        lastVisitedTicks.put(position, currentTick);
        return true;
    }

    public synchronized boolean remove(int x, int y, int z) {
        Position position = new Position(x, y, z);
        lastVisitedTicks.remove(position);
        return spawners.remove(position) != null;
    }

    public synchronized boolean configure(int x, int y, int z, String identifier,
                                          int delayTicks, long currentTick) {
        Position position = new Position(x, y, z);
        MobSpawnerState existing = spawners.get(position);
        if (existing == null || currentTick < 0) return false;
        try {
            spawners.put(position, existing.withConfiguration(identifier, delayTicks,
                    safeNextTick(currentTick, delayTicks)));
            lastVisitedTicks.put(position, currentTick);
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
        int tracked;
        synchronized (this) {
            tracked = spawners.size();
            // Bedrock input can cause more than one world-AI callback in a server tick.
            // A spawner must advance at most once for a given tick, regardless of player count.
            if (currentTick == lastProcessedTick) {
                return new TickResult(tracked, 0, 0, 0, 0, 0, 0);
            }
            lastProcessedTick = currentTick;

            // Never let a large number of placed spawners monopolize one AI update.
            // Round-robin selection guarantees every registered spawner gets a turn.
            List<MobSpawnerState> all = List.copyOf(spawners.values());
            int processCount = Math.min(all.size(), MAX_SPAWNERS_PER_TICK);
            snapshot = new ArrayList<>(processCount);
            if (!all.isEmpty()) {
                int start = Math.floorMod(nextSpawnerCursor, all.size());
                for (int i = 0; i < processCount; i++) {
                    snapshot.add(all.get((start + i) % all.size()));
                }
                nextSpawnerCursor = (start + processCount) % all.size();
            } else {
                nextSpawnerCursor = 0;
            }
        }
        if (snapshot.isEmpty()) {
            return new TickResult(tracked, 0, 0, 0, 0, 0, 0);
        }
        Map<Long, List<WorldLivingEntity>> livingByChunk = null;
        List<SpawnRequest> spawnedThisTick = new ArrayList<>();
        int active = 0;
        int spawned = 0;
        int attempts = 0;
        int capped = 0;
        int invalid = 0;
        for (MobSpawnerState state : snapshot) {
            if (!isCurrent(state)) continue;
            long spawnerElapsedTicks = elapsedSinceLastVisit(state, currentTick);
            if (!world.isChunkLoadedAt(state.x(), state.z())) {
                pauseTimer(state, spawnerElapsedTicks);
                continue;
            }
            if (world.getBlock(state.x(), state.y(), state.z()) != BlockType.SPAWNER) {
                remove(state.x(), state.y(), state.z());
                continue;
            }
            if (!isPlayerNearby(state, players)) {
                synchronized (this) { inactiveTicks++; }
                pauseTimer(state, spawnerElapsedTicks);
                continue;
            }
            active++;
            if (currentTick < state.nextSpawnTick()) continue;

            if (livingByChunk == null) {
                livingByChunk = new HashMap<>();
                for (WorldLivingEntity entity : world.livingEntities().snapshot()) {
                    int chunkX = Math.floorDiv((int) Math.floor(entity.x()), 16);
                    int chunkZ = Math.floorDiv((int) Math.floor(entity.z()), 16);
                    livingByChunk.computeIfAbsent(chunkKey(chunkX, chunkZ),
                            ignored -> new ArrayList<>()).add(entity);
                }
            }
            int nearby = countNearby(livingByChunk, spawnedThisTick, state);
            if (nearby >= MAX_NEARBY_SAME_TYPE) {
                capped++;
                updateNext(state, currentTick);
                continue;
            }

            int spawnedFromThis = 0;
            for (int attempt = 0; attempt < MAX_SPAWN_ATTEMPTS
                    && nearby + spawnedFromThis < MAX_NEARBY_SAME_TYPE; attempt++) {
                if (!isCurrent(state)
                        || world.getBlock(state.x(), state.y(), state.z()) != BlockType.SPAWNER) break;
                attempts++;
                SpawnRequest request = candidate(state, currentTick, attempt);
                if (!validSpawnPosition(world, request)) {
                    invalid++;
                    continue;
                }
                if (!spawnHandler.test(request)) break;
                spawnedFromThis++;
                spawned++;
                spawnedThisTick.add(request);
            }
            updateNext(state, currentTick);
        }
        synchronized (this) {
            spawnCount += spawned;
            spawnAttempts += attempts;
            cappedAttempts += capped;
            invalidPositionAttempts += invalid;
        }
        return new TickResult(tracked, snapshot.size(), active, attempts, spawned, capped, invalid);
    }

    public synchronized List<MobSpawnerState> snapshot() {
        List<MobSpawnerState> result = new ArrayList<>(spawners.values());
        result.sort(Comparator.comparingInt(MobSpawnerState::x)
                .thenComparingInt(MobSpawnerState::y).thenComparingInt(MobSpawnerState::z));
        return List.copyOf(result);
    }

    public synchronized Stats stats() {
        return new Stats(spawners.size(), spawnCount, spawnAttempts, cappedAttempts, inactiveTicks,
                invalidPositionAttempts);
    }

    private synchronized long elapsedSinceLastVisit(MobSpawnerState state, long currentTick) {
        Position key = new Position(state.x(), state.y(), state.z());
        Long previous = lastVisitedTicks.put(key, currentTick);
        if (previous == null) return 1L;
        return currentTick >= previous ? currentTick - previous : 0L;
    }

    /** Pauses the countdown while the chunk is unloaded or no player activates the spawner. */
    private synchronized void pauseTimer(MobSpawnerState state, long elapsedTicks) {
        if (elapsedTicks <= 0) return;
        Position key = new Position(state.x(), state.y(), state.z());
        MobSpawnerState current = spawners.get(key);
        if (current == null || current.nextSpawnTick() == Long.MAX_VALUE) return;
        long pausedUntil = current.nextSpawnTick() > Long.MAX_VALUE - elapsedTicks
                ? Long.MAX_VALUE : current.nextSpawnTick() + elapsedTicks;
        spawners.put(key, current.withNextSpawnTick(pausedUntil));
    }

    private synchronized boolean isCurrent(MobSpawnerState expected) {
        return expected.equals(spawners.get(new Position(expected.x(), expected.y(), expected.z())));
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

    private static int countNearby(
            Map<Long, List<WorldLivingEntity>> entitiesByChunk,
            List<SpawnRequest> spawned, MobSpawnerState state
    ) {
        int count = 0;
        int centerChunkX = Math.floorDiv(state.x(), 16);
        int centerChunkZ = Math.floorDiv(state.z(), 16);
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                List<WorldLivingEntity> entities = entitiesByChunk.get(
                        chunkKey(centerChunkX + offsetX, centerChunkZ + offsetZ));
                if (entities == null) continue;
                for (WorldLivingEntity entity : entities) {
                    if (!entity.identifier().equals(state.entityIdentifier())) continue;
                    if (Math.abs(entity.x() - (state.x() + 0.5)) <= 4.5
                            && Math.abs(entity.y() - (state.y() + 0.5)) <= 4.5
                            && Math.abs(entity.z() - (state.z() + 0.5)) <= 4.5) count++;
                }
            }
        }
        for (SpawnRequest entity : spawned) {
            if (!entity.identifier().equals(state.entityIdentifier())) continue;
            if (Math.abs(entity.x() - (state.x() + 0.5)) <= 4.5
                    && Math.abs(entity.y() - (state.y() + 0.5)) <= 4.5
                    && Math.abs(entity.z() - (state.z() + 0.5)) <= 4.5) count++;
        }
        return count;
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffff_ffffL);
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
                || !WorldPathfinder.isMobFootprintLoaded(world, request.x(), request.z())) return false;
        if (requiresSolidFloor(request.identifier())
                && world.getBlock(x, y - 1, z) == BlockType.AIR) return false;
        int requiredAirBlocks = spawnHeightBlocks(request.identifier());
        for (int offset = 0; offset < requiredAirBlocks; offset++) {
            if (world.getBlock(x, y + offset, z) != BlockType.AIR) return false;
        }
        return true;
    }

    private static boolean requiresSolidFloor(String identifier) {
        return switch (identifier) {
            case "minecraft:allay", "minecraft:bat", "minecraft:bee", "minecraft:blaze",
                    "minecraft:ghast", "minecraft:phantom", "minecraft:vex",
                    "minecraft:parrot", "minecraft:ender_dragon" -> false;
            default -> true;
        };
    }

    private static int spawnHeightBlocks(String identifier) {
        return switch (identifier) {
            case "minecraft:ender_dragon" -> 8;
            case "minecraft:ghast" -> 4;
            case "minecraft:warden", "minecraft:iron_golem", "minecraft:ravager",
                    "minecraft:camel" -> 3;
            default -> 2;
        };
    }

    private static long safeNextTick(long tick, int delay) {
        if (delay < MobSpawnerState.MIN_DELAY_TICKS || delay > MobSpawnerState.MAX_DELAY_TICKS) {
            throw new IllegalArgumentException("Invalid spawner delay or tick");
        }
        return safeAddTicks(tick, delay);
    }

    private static long safeAddTicks(long tick, long ticks) {
        if (tick < 0 || ticks < 0 || tick > Long.MAX_VALUE - ticks) {
            throw new IllegalArgumentException("Invalid spawner timer");
        }
        return tick + ticks;
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

    public record TickResult(int trackedSpawners, int processedSpawners, int activeSpawners,
                             int attempts, int spawned, int capped, int invalidPositions) { }

    public record Stats(int trackedSpawners, long spawned, long spawnAttempts,
                        long cappedAttempts, long inactiveTicks, long invalidPositionAttempts) {
        public String summary() {
            return "tracked=" + trackedSpawners + ", spawned=" + spawned
                    + ", attempts=" + spawnAttempts + ", capped=" + cappedAttempts
                    + ", inactiveTicks=" + inactiveTicks
                    + ", invalidPositions=" + invalidPositionAttempts;
        }
    }
}
