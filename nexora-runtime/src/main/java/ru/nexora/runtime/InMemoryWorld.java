package ru.nexora.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Sparse in-memory world implementation for simulation and early development.
 *
 * <p>It is intentionally not a persistence implementation: chunks and their
 * block data are lost when this object is discarded.</p>
 */
public final class InMemoryWorld implements World {
    private final String id;
    private final WorldHeight height;
    private final ConcurrentMap<ChunkPosition, WorldChunk> chunks = new ConcurrentHashMap<>();

    public InMemoryWorld(String id, WorldHeight height) {
        Objects.requireNonNull(id, "id");
        this.height = Objects.requireNonNull(height, "height");
        String normalizedId = id.trim();
        if (normalizedId.isEmpty()) {
            throw new IllegalArgumentException("World id must not be blank");
        }
        this.id = normalizedId;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public WorldHeight height() {
        return height;
    }

    @Override
    public BlockState blockAt(BlockPosition position) {
        validatePosition(position);
        WorldChunk chunk = chunks.get(position.chunkPosition());
        return chunk == null ? BlockState.AIR : chunk.blockAt(position);
    }

    @Override
    public void setBlock(BlockPosition position, BlockState state) {
        validatePosition(position);
        Objects.requireNonNull(state, "state");
        ChunkPosition chunkPosition = position.chunkPosition();

        if (state.isAir()) {
            chunks.computeIfPresent(chunkPosition, (ignored, chunk) -> {
                chunk.setBlock(position, BlockState.AIR);
                return chunk.isEmpty() ? null : chunk;
            });
            return;
        }

        chunks.compute(chunkPosition, (ignored, chunk) -> {
            WorldChunk target = chunk == null ? new WorldChunk() : chunk;
            target.setBlock(position, state);
            return target;
        });
    }

    @Override
    public boolean isChunkLoaded(ChunkPosition position) {
        return chunks.containsKey(Objects.requireNonNull(position, "position"));
    }

    @Override
    public int loadedChunkCount() {
        return chunks.size();
    }

    @Override
    public List<ChunkPosition> loadedChunks() {
        return List.copyOf(chunks.keySet());
    }

    @Override
    public Map<BlockPosition, BlockState> blocksInChunk(ChunkPosition position) {
        Objects.requireNonNull(position, "position");
        WorldChunk chunk = chunks.get(position);
        return chunk == null ? Map.of() : chunk.snapshot(position);
    }

    private void validatePosition(BlockPosition position) {
        Objects.requireNonNull(position, "position");
        if (!height.contains(position.y())) {
            throw new IllegalArgumentException(
                    "Block Y " + position.y() + " is outside world height ["
                            + height.minY() + ", " + height.maxYExclusive() + ")"
            );
        }
    }

    private static final class WorldChunk {
        private final ConcurrentMap<LocalBlockPosition, BlockState> blocks =
                new ConcurrentHashMap<>();

        private synchronized BlockState blockAt(BlockPosition position) {
            return blocks.getOrDefault(
                    new LocalBlockPosition(position.localX(), position.y(), position.localZ()),
                    BlockState.AIR
            );
        }

        private synchronized void setBlock(BlockPosition position, BlockState state) {
            LocalBlockPosition local = new LocalBlockPosition(
                    position.localX(), position.y(), position.localZ()
            );
            if (state.isAir()) {
                blocks.remove(local);
            } else {
                blocks.put(local, state);
            }
        }

        private synchronized boolean isEmpty() {
            return blocks.isEmpty();
        }

        private synchronized Map<BlockPosition, BlockState> snapshot(ChunkPosition chunkPosition) {
            int baseX = Math.multiplyExact(chunkPosition.x(), 16);
            int baseZ = Math.multiplyExact(chunkPosition.z(), 16);
            Map<BlockPosition, BlockState> snapshot = new java.util.HashMap<>();
            blocks.forEach((local, state) -> snapshot.put(
                    new BlockPosition(
                            Math.addExact(baseX, local.x()),
                            local.y(),
                            Math.addExact(baseZ, local.z())
                    ),
                    state
            ));
            return Map.copyOf(snapshot);
        }
    }

    private record LocalBlockPosition(int x, int y, int z) {
    }
}
