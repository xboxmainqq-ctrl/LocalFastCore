package ru.nexora.runtime;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a local single-player world at a fixed tick rate and streams nearby
 * chunks through bounded background workers.
 *
 * <p>World mutation and player physics are performed only on the game-loop
 * thread. Generation workers return immutable chunk snapshots which are
 * committed at the beginning of a tick.</p>
 */
public final class LocalWorldRuntime implements AutoCloseable {
    private static final int MAX_CHUNKS_APPLIED_PER_TICK = 4;

    private final LocalWorldSession session;
    private final World world;
    private final LocalPlayerController player;
    private final AsyncChunkGenerationService chunkGeneration;
    private final TickScheduler scheduler = new TickScheduler();
    private final GameLoop gameLoop;
    private final AtomicReference<LocalPlayerController.Input> input =
            new AtomicReference<>(LocalPlayerController.Input.idle());
    private final int viewDistance;
    private volatile RuntimeException lastChunkFailure;
    private volatile boolean started;
    private volatile boolean closed;

    public LocalWorldRuntime(
            LocalWorldSession session,
            ChunkGenerator generator,
            long seed,
            double spawnX,
            double spawnY,
            double spawnZ,
            float spawnYaw,
            int viewDistance
    ) {
        this.session = Objects.requireNonNull(session, "session");
        this.world = session.world();
        if (viewDistance < 1 || viewDistance > 8) {
            throw new IllegalArgumentException("View distance must be between 1 and 8 chunks");
        }
        this.viewDistance = viewDistance;
        this.player = new LocalPlayerController(world, spawnX, spawnY, spawnZ, spawnYaw);
        this.chunkGeneration = new AsyncChunkGenerationService(
                Objects.requireNonNull(generator, "generator"),
                world.height(),
                seed,
                Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)),
                128,
                128
        );
        scheduler.scheduleRepeating(1, 1, this::tick);
        this.gameLoop = new GameLoop(20, 3, scheduler, failure -> lastChunkFailure = failure);
    }

    /** Starts background chunk workers and the 20 TPS game loop. */
    public synchronized void start() {
        ensureNotClosed();
        if (started) {
            throw new IllegalStateException("Local world runtime is already started");
        }
        chunkGeneration.start();
        try {
            gameLoop.start();
            started = true;
        } catch (RuntimeException failure) {
            try {
                chunkGeneration.stop();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failure.addSuppressed(interrupted);
            }
            throw failure;
        }
    }

    /** Replaces the latest movement snapshot; safe to call from the UI thread. */
    public void submitInput(LocalPlayerController.Input nextInput) {
        ensureNotClosed();
        input.set(Objects.requireNonNull(nextInput, "nextInput"));
    }

    public LocalPlayerController.State playerState() {
        ensureNotClosed();
        return player.state();
    }

    public boolean isRunning() {
        return started && gameLoop.isRunning();
    }

    public long ticks() {
        return gameLoop.executedTicks();
    }

    public int loadedChunks() {
        return world.loadedChunkCount();
    }

    public int pendingChunkRequests() {
        return chunkGeneration.pendingCount();
    }

    public RuntimeException lastChunkFailure() {
        return lastChunkFailure;
    }

    private void tick() {
        chunkGeneration.drainCompleted(MAX_CHUNKS_APPLIED_PER_TICK, result -> {
            if (result.succeeded()) {
                world.loadChunk(result.chunk());
            } else {
                lastChunkFailure = result.failure();
            }
        });

        LocalPlayerController.State current = player.state();
        ChunkPosition center = current.chunkPosition();
        chunkRequests:
        for (int dx = -viewDistance; dx <= viewDistance; dx++) {
            for (int dz = -viewDistance; dz <= viewDistance; dz++) {
                ChunkPosition position = new ChunkPosition(
                        Math.addExact(center.x(), dx),
                        Math.addExact(center.z(), dz)
                );
                if (!world.isChunkLoaded(position)) {
                    AsyncChunkGenerationService.RequestStatus status =
                            chunkGeneration.request(position);
                    if (status == AsyncChunkGenerationService.RequestStatus.QUEUE_FULL) {
                        // Stop requesting this view's remaining chunks for now, but
                        // always advance player physics. The next tick retries them.
                        break chunkRequests;
                    }
                }
            }
        }
        player.tick(input.get());
    }

    @Override
    public synchronized void close() throws IOException, InterruptedException {
        if (closed) {
            return;
        }
        if (started) {
            gameLoop.stop();
            started = false;
            chunkGeneration.stop();
        }
        session.close();
        closed = true;
    }

    private void ensureNotClosed() {
        if (closed || session.isClosed()) {
            throw new IllegalStateException("Local world runtime is closed");
        }
    }
}
