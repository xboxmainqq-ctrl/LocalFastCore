package ru.nexora.runtime;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable result of chunk generation, including blocks, biome cells, biome columns and fluids. */
public record GeneratedChunk(
        ChunkPosition position,
        Map<BlockPosition, BlockState> blocks,
        Map<BlockPosition, Integer> biomes,
        Map<BlockPosition, BlockState> fluids,
        Map<BiomeColumnPosition, Integer> biomeColumns,
        WorldHeight biomeWorldHeight
) {
    public GeneratedChunk(ChunkPosition position, Map<BlockPosition, BlockState> blocks) {
        this(position, blocks, Map.of(), Map.of(), Map.of(), null);
    }

    public GeneratedChunk(
            ChunkPosition position,
            Map<BlockPosition, BlockState> blocks,
            Map<BlockPosition, Integer> biomes,
            Map<BlockPosition, BlockState> fluids
    ) {
        this(position, blocks, biomes, fluids, Map.of(), null);
    }

    public GeneratedChunk {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(blocks, "blocks");
        Objects.requireNonNull(biomes, "biomes");
        Objects.requireNonNull(fluids, "fluids");
        Objects.requireNonNull(biomeColumns, "biomeColumns");
        if (!biomeColumns.isEmpty()) Objects.requireNonNull(biomeWorldHeight, "biomeWorldHeight");

        Map<BlockPosition, BlockState> blockCopy = new HashMap<>();
        blocks.forEach((blockPosition, state) -> {
            requireChunkPosition(position, blockPosition);
            Objects.requireNonNull(state, "block state");
            if (state.isAir()) {
                throw new IllegalArgumentException("Generated chunk snapshots must omit air blocks");
            }
            blockCopy.put(blockPosition, state);
        });
        blocks = Collections.unmodifiableMap(blockCopy);

        Map<BlockPosition, Integer> biomeCopy = new HashMap<>();
        biomes.forEach((blockPosition, biomeId) -> {
            requireChunkPosition(position, blockPosition);
            requireBiomeId(biomeId);
            if (biomeId != BiomeSection.DEFAULT_BIOME_ID) biomeCopy.put(blockPosition, biomeId);
        });
        biomes = Collections.unmodifiableMap(biomeCopy);

        Map<BlockPosition, BlockState> fluidCopy = new HashMap<>();
        fluids.forEach((blockPosition, state) -> {
            requireChunkPosition(position, blockPosition);
            FluidStates.requireFluidOrAir(state);
            if (!state.isAir()) fluidCopy.put(blockPosition, state);
        });
        fluids = Collections.unmodifiableMap(fluidCopy);

        Map<BiomeColumnPosition, Integer> columnCopy = new HashMap<>();
        biomeColumns.forEach((column, biomeId) -> {
            Objects.requireNonNull(column, "biome column");
            if (!column.chunkPosition().equals(position)) {
                throw new IllegalArgumentException("Biome column does not belong to chunk " + position + ": " + column);
            }
            requireBiomeId(biomeId);
            if (biomeId != BiomeSection.DEFAULT_BIOME_ID) columnCopy.put(column, biomeId);
        });
        biomeColumns = Collections.unmodifiableMap(columnCopy);
    }

    private static void requireBiomeId(Integer biomeId) {
        Objects.requireNonNull(biomeId, "biome ID");
        if (biomeId < 0) throw new IllegalArgumentException("Biome ID must be non-negative");
    }

    private static void requireChunkPosition(ChunkPosition chunk, BlockPosition block) {
        Objects.requireNonNull(block, "block position");
        if (!block.chunkPosition().equals(chunk)) {
            throw new IllegalArgumentException("Generated data does not belong to chunk " + chunk + ": " + block);
        }
    }
}
