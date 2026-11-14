package ru.nexora.runtime;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Topmost non-air block in each occupied column of one chunk, for the first client preview. */
public record NexoraChunkData(int chunkX, int chunkZ, List<Column> columns) {
    public NexoraChunkData {
        Objects.requireNonNull(columns, "columns");
        if (columns.size() > 256) throw new IllegalArgumentException("A chunk has at most 256 columns");
        columns = List.copyOf(columns);
        Set<Integer> indices = new HashSet<>();
        for (Column column : columns) {
            if (!indices.add(column.index())) throw new IllegalArgumentException("Duplicate chunk column");
        }
    }

    public record Column(int index, int y, String blockIdentifier) {
        public Column {
            Objects.requireNonNull(blockIdentifier, "blockIdentifier");
            if (index < 0 || index >= 256 || blockIdentifier.isBlank()
                    || blockIdentifier.length() > 128) {
                throw new IllegalArgumentException("Invalid NEXORA chunk column");
            }
        }
    }

    public static NexoraChunkData topSurface(World world, int chunkX, int chunkZ) {
        Objects.requireNonNull(world, "world");
        ChunkPosition position = new ChunkPosition(chunkX, chunkZ);
        java.util.Map<Integer, Column> top = new java.util.HashMap<>();
        for (java.util.Map.Entry<BlockPosition, BlockState> entry
                : world.blocksInChunk(position).entrySet()) {
            BlockPosition block = entry.getKey();
            BlockState state = entry.getValue();
            if (state.isAir()) continue;
            int index = block.localZ() * 16 + block.localX();
            Column previous = top.get(index);
            if (previous == null || block.y() > previous.y()) {
                top.put(index, new Column(index, block.y(), state.identifier()));
            }
        }
        return new NexoraChunkData(chunkX, chunkZ,
                top.values().stream().sorted(java.util.Comparator.comparingInt(Column::index)).toList());
    }
}
