package ru.nexora.runtime;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Bounded sparse block snapshot for one NEXORA chunk. */
public record NexoraChunkData(int chunkX, int chunkZ, List<Block> blocks) {
    public static final int MAX_BLOCKS = 32768;

    public NexoraChunkData {
        Objects.requireNonNull(blocks, "blocks");
        if (blocks.size() > MAX_BLOCKS) {
            throw new IllegalArgumentException("NEXORA chunk contains too many blocks");
        }
        blocks = List.copyOf(blocks);
        Set<Long> positions = new HashSet<>();
        for (Block block : blocks) {
            long key = ((long) block.y() << 8) | (block.localZ() << 4) | block.localX();
            if (!positions.add(key)) throw new IllegalArgumentException("Duplicate NEXORA block position");
        }
    }

    public record Block(int localX, int y, int localZ, String blockIdentifier) {
        public Block {
            Objects.requireNonNull(blockIdentifier, "blockIdentifier");
            if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15
                    || blockIdentifier.isBlank() || blockIdentifier.length() > 128) {
                throw new IllegalArgumentException("Invalid NEXORA chunk block");
            }
        }
    }

    public static NexoraChunkData volume(World world, int chunkX, int chunkZ) {
        Objects.requireNonNull(world, "world");
        ChunkPosition position = new ChunkPosition(chunkX, chunkZ);
        List<Block> blocks = world.blocksInChunk(position).entrySet().stream()
                .filter(entry -> !entry.getValue().isAir())
                .map(entry -> new Block(entry.getKey().localX(), entry.getKey().y(),
                        entry.getKey().localZ(), entry.getValue().identifier()))
                .sorted(java.util.Comparator.comparingInt(Block::y)
                        .thenComparingInt(Block::localZ).thenComparingInt(Block::localX))
                .toList();
        return new NexoraChunkData(chunkX, chunkZ, blocks);
    }
}
