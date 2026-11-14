package ru.nexora.runtime;

import java.util.HashMap;
import java.util.Map;

/** Mutable, synchronized chunk storage backed by compact palette sections. */
final class WorldChunk {
    private final Map<Integer, ChunkSection> sections = new HashMap<>();

    static WorldChunk from(Map<BlockPosition, BlockState> snapshot) {
        WorldChunk chunk = new WorldChunk();
        snapshot.forEach((position, state) -> chunk.setBlock(position, state));
        return chunk;
    }

    synchronized BlockState blockAt(BlockPosition position) {
        int sectionY = Math.floorDiv(position.y(), 16);
        ChunkSection section = sections.get(sectionY);
        return section == null ? BlockState.AIR
                : section.get(position.localX(), Math.floorMod(position.y(), 16), position.localZ());
    }

    synchronized void setBlock(BlockPosition position, BlockState state) {
        int sectionY = Math.floorDiv(position.y(), 16);
        int localY = Math.floorMod(position.y(), 16);
        ChunkSection section = sections.get(sectionY);
        if (section == null) {
            if (state.isAir()) {
                return;
            }
            section = new ChunkSection();
            sections.put(sectionY, section);
        }
        section.set(position.localX(), localY, position.localZ(), state);
        if (section.isEmpty()) {
            sections.remove(sectionY);
        }
    }

    synchronized boolean isEmpty() {
        return sections.isEmpty();
    }

    synchronized Map<BlockPosition, BlockState> snapshot(ChunkPosition chunkPosition) {
        int baseX = Math.multiplyExact(chunkPosition.x(), 16);
        int baseZ = Math.multiplyExact(chunkPosition.z(), 16);
        Map<BlockPosition, BlockState> snapshot = new HashMap<>();
        sections.forEach((sectionY, section) -> {
            int baseY = Math.multiplyExact(sectionY, 16);
            for (int localY = 0; localY < 16; localY++) {
                int y = Math.addExact(baseY, localY);
                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int localX = 0; localX < 16; localX++) {
                        BlockState state = section.get(localX, localY, localZ);
                        if (!state.isAir()) {
                            snapshot.put(new BlockPosition(
                                    Math.addExact(baseX, localX),
                                    y,
                                    Math.addExact(baseZ, localZ)
                            ), state);
                        }
                    }
                }
            }
        });
        return Map.copyOf(snapshot);
    }
}
