package ru.nexora.runtime;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Sparse in-memory world implementation for simulation and early development.
 *
 * <p>Chunks are split into 16x16x16 sections. Each section stores block states
 * through a compact local palette instead of allocating a coordinate object
 * and hash-map entry for every block.</p>
 *
 * <p>This is intentionally not a persistence implementation: chunks and their
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
    public BlockState fluidAt(BlockPosition position) {
        validatePosition(position);
        WorldChunk chunk = chunks.get(position.chunkPosition());
        return chunk == null ? BlockState.AIR : chunk.fluidAt(position);
    }

    @Override
    public void setFluid(BlockPosition position, BlockState fluidState) {
        validatePosition(position);
        FluidStates.requireFluidOrAir(fluidState);
        ChunkPosition chunkPosition = position.chunkPosition();
        if (fluidState.isAir()) {
            chunks.computeIfPresent(chunkPosition, (ignored, chunk) -> {
                chunk.setFluid(position, BlockState.AIR);
                return chunk.isEmpty() ? null : chunk;
            });
            return;
        }
        chunks.compute(chunkPosition, (ignored, chunk) -> {
            WorldChunk target = chunk == null ? new WorldChunk() : chunk;
            target.setFluid(position, fluidState);
            return target;
        });
    }

    @Override
    public Map<BlockPosition, BlockState> fluidsInChunk(ChunkPosition position) {
        Objects.requireNonNull(position, "position");
        WorldChunk chunk = chunks.get(position);
        return chunk == null ? Map.of() : chunk.fluidSnapshot(position);
    }

    @Override
    public int biomeAt(BlockPosition position) {
        validatePosition(position);
        WorldChunk chunk = chunks.get(position.chunkPosition());
        return chunk == null ? BiomeSection.DEFAULT_BIOME_ID : chunk.biomeAt(position);
    }

    @Override
    public void setBiome(BlockPosition position, int biomeId) {
        validatePosition(position);
        if (biomeId < 0) {
            throw new IllegalArgumentException("Biome ID must be non-negative");
        }
        ChunkPosition chunkPosition = position.chunkPosition();
        if (biomeId == BiomeSection.DEFAULT_BIOME_ID) {
            chunks.computeIfPresent(chunkPosition, (ignored, chunk) -> {
                chunk.setBiome(position, biomeId);
                return chunk.isEmpty() ? null : chunk;
            });
            return;
        }
        chunks.compute(chunkPosition, (ignored, chunk) -> {
            WorldChunk target = chunk == null ? new WorldChunk() : chunk;
            target.setBiome(position, biomeId);
            return target;
        });
    }

    @Override
    public int[] biomesInSection(ChunkPosition position, int sectionY) {
        Objects.requireNonNull(position, "position");
        int minSectionY = Math.floorDiv(height.minY(), 16);
        int maxSectionY = Math.floorDiv(height.maxYExclusive() - 1, 16);
        if (sectionY < minSectionY || sectionY > maxSectionY) {
            throw new IllegalArgumentException("Biome section Y is outside world height");
        }
        WorldChunk chunk = chunks.get(position);
        if (chunk == null) {
            int[] defaults = new int[BiomeSection.CELL_COUNT];
            java.util.Arrays.fill(defaults, BiomeSection.DEFAULT_BIOME_ID);
            return defaults;
        }
        return chunk.biomesInSection(sectionY);
    }

    @Override
    public List<Integer> biomeSectionsInChunk(ChunkPosition position) {
        Objects.requireNonNull(position, "position");
        WorldChunk chunk = chunks.get(position);
        return chunk == null ? List.of() : chunk.biomeSectionYs();
    }

    @Override
    public boolean loadChunk(GeneratedChunk generatedChunk) {
        Objects.requireNonNull(generatedChunk, "generatedChunk");
        for (BlockPosition position : generatedChunk.blocks().keySet()) {
            validatePosition(position);
        }
        for (BlockPosition position : generatedChunk.biomes().keySet()) {
            validatePosition(position);
        }
        for (BlockPosition position : generatedChunk.fluids().keySet()) {
            validatePosition(position);
        }
        WorldChunk chunk = WorldChunk.from(generatedChunk);
        return chunks.putIfAbsent(generatedChunk.position(), chunk) == null;
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
}
