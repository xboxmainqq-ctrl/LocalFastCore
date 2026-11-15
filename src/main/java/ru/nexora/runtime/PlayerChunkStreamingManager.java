package ru.nexora.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Shared, bounded chunk streaming coordinator for players in one world.
 *
 * <p>Each player holds tickets for chunks in a circular view distance. Tickets
 * are reference-counted across players, so moving one player cannot cancel a
 * chunk still needed by another. Call {@link #tick()} from the game loop.
 * The coordinator owns requests it creates through the supplied pipeline.</p>
 *
 * <p>Loaded-chunk eviction is deliberately not performed here: a world must
 * first provide dirty-chunk persistence and safe unload semantics.</p>
 */
public final class PlayerChunkStreamingManager {
    private final World world;
    private final WorldChunkPipeline pipeline;
    private final int viewDistance;
    private final int maximumRequestsPerTick;
    private final Map<UUID, PlayerView> players = new HashMap<>();
    private final Map<ChunkPosition, Integer> tickets = new HashMap<>();
    private final Set<ChunkPosition> inFlight = new HashSet<>();

    public PlayerChunkStreamingManager(
            World world,
            WorldChunkPipeline pipeline,
            int viewDistance,
            int maximumRequestsPerTick
    ) {
        this.world = Objects.requireNonNull(world, "world");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        if (viewDistance < 0 || viewDistance > 32) {
            throw new IllegalArgumentException("View distance must be between 0 and 32 chunks");
        }
        if (maximumRequestsPerTick < 1 || maximumRequestsPerTick > 4096) {
            throw new IllegalArgumentException("Maximum requests per tick must be between 1 and 4096");
        }
        this.viewDistance = viewDistance;
        this.maximumRequestsPerTick = maximumRequestsPerTick;
    }

    /** Updates one player's center using block coordinates. */
    public synchronized void updatePlayer(UUID playerId, int blockX, int blockZ) {
        Objects.requireNonNull(playerId, "playerId");
        ChunkPosition center = ChunkPosition.fromBlock(blockX, blockZ);
        PlayerView previous = players.get(playerId);
        if (previous != null && previous.center.equals(center)) return;

        Set<ChunkPosition> next = desiredChunks(center);
        if (previous != null) {
            for (ChunkPosition position : previous.chunks) removeTicket(position);
        }
        players.put(playerId, new PlayerView(center, next));
        for (ChunkPosition position : next) tickets.merge(position, 1, Integer::sum);
    }

    /** Releases every chunk ticket held by a player who left the world. */
    public synchronized void removePlayer(UUID playerId) {
        PlayerView previous = players.remove(Objects.requireNonNull(playerId, "playerId"));
        if (previous == null) return;
        for (ChunkPosition position : previous.chunks) removeTicket(position);
    }

    /**
     * Schedules the nearest missing chunks first, with a hard request budget.
     * Invoke once per game tick; queue saturation is retried on a later tick.
     *
     * @return number of new or already-pending requests accepted this tick
     */
    public synchronized int tick() {
        if (players.isEmpty()) return 0;
        Map<ChunkPosition, Long> priorities = new HashMap<>();
        for (Map.Entry<UUID, PlayerView> entry : players.entrySet()) {
            ChunkPosition center = entry.getValue().center;
            for (ChunkPosition position : entry.getValue().chunks) {
                long dx = (long) position.x() - center.x();
                long dz = (long) position.z() - center.z();
                long distance = dx * dx + dz * dz;
                priorities.merge(position, distance, Math::min);
            }
        }

        List<ChunkPosition> ordered = new ArrayList<>(priorities.keySet());
        ordered.sort(Comparator
                .comparingLong((ChunkPosition position) -> priorities.get(position))
                .thenComparingInt(ChunkPosition::x)
                .thenComparingInt(ChunkPosition::z));

        int scheduled = 0;
        for (ChunkPosition position : ordered) {
            if (world.isChunkLoaded(position)) {
                inFlight.remove(position);
                continue;
            }
            if (inFlight.contains(position)) {
                if (pipeline.isPending(position)) continue;
                // A failed generation result was consumed; allow a fresh attempt.
                inFlight.remove(position);
            }
            if (scheduled >= maximumRequestsPerTick) break;
            WorldChunkPipeline.RequestStatus status = pipeline.request(position);
            if (status == WorldChunkPipeline.RequestStatus.QUEUE_FULL
                    || status == WorldChunkPipeline.RequestStatus.STOPPED) {
                break;
            }
            if (status == WorldChunkPipeline.RequestStatus.ACCEPTED
                    || status == WorldChunkPipeline.RequestStatus.ALREADY_PENDING) {
                inFlight.add(position);
                scheduled++;
            }
        }
        return scheduled;
    }

    public synchronized int playerCount() {
        return players.size();
    }

    /** Number of unique chunks currently requested by at least one player. */
    public synchronized int ticketedChunkCount() {
        return tickets.size();
    }

    public synchronized int ticketCount(ChunkPosition position) {
        return tickets.getOrDefault(Objects.requireNonNull(position, "position"), 0);
    }

    private Set<ChunkPosition> desiredChunks(ChunkPosition center) {
        Set<ChunkPosition> result = new HashSet<>();
        int radiusSquared = viewDistance * viewDistance;
        for (int dx = -viewDistance; dx <= viewDistance; dx++) {
            for (int dz = -viewDistance; dz <= viewDistance; dz++) {
                if (dx * dx + dz * dz > radiusSquared) continue;
                result.add(new ChunkPosition(center.x() + dx, center.z() + dz));
            }
        }
        return Set.copyOf(result);
    }

    private void removeTicket(ChunkPosition position) {
        Integer count = tickets.get(position);
        if (count == null) return;
        if (count > 1) {
            tickets.put(position, count - 1);
        } else {
            tickets.remove(position);
            inFlight.remove(position);
            if (!world.isChunkLoaded(position)) pipeline.cancel(position);
        }
    }

    private record PlayerView(ChunkPosition center, Set<ChunkPosition> chunks) {
    }
}
