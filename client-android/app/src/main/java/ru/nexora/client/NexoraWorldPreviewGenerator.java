package ru.nexora.client;

import ru.nexora.runtime.BlockPosition;
import ru.nexora.runtime.BlockState;
import ru.nexora.runtime.ChunkPosition;
import ru.nexora.runtime.GeneratedChunk;
import ru.nexora.runtime.OverworldChunkGenerator;
import ru.nexora.runtime.WorldHeight;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Converts the authoritative NEXORA Core generator output into the Android
 * renderer's transport-neutral chunk view. Generation itself is shared with
 * the standalone server; this class contains presentation adaptation only.
 */
final class NexoraWorldPreviewGenerator {
    private static final WorldHeight WORLD_HEIGHT = new WorldHeight(-64, 320);
    private static final OverworldChunkGenerator GENERATOR = new OverworldChunkGenerator();

    private NexoraWorldPreviewGenerator() { }

    static PreviewData generate(long seed) {
        List<NexoraAndroidProtocolCodec.ChunkData> chunks = new ArrayList<>(9);
        int spawnY = WORLD_HEIGHT.minY() + 2;

        for (int chunkX = -1; chunkX <= 1; chunkX++) {
            for (int chunkZ = -1; chunkZ <= 1; chunkZ++) {
                GeneratedChunk generated = GENERATOR.generate(
                        new ChunkPosition(chunkX, chunkZ), WORLD_HEIGHT, seed);
                List<NexoraAndroidProtocolCodec.ChunkBlock> blocks =
                        new ArrayList<>(generated.blocks().size() + generated.fluids().size());

                for (Map.Entry<BlockPosition, BlockState> entry : generated.blocks().entrySet()) {
                    BlockPosition position = entry.getKey();
                    blocks.add(new NexoraAndroidProtocolCodec.ChunkBlock(
                            position.localX(), position.y(), position.localZ(),
                            entry.getValue().identifier()));
                    if (chunkX == 0 && chunkZ == 0
                            && position.localX() == 0 && position.localZ() == 0) {
                        spawnY = Math.max(spawnY, position.y() + 1);
                    }
                }
                for (Map.Entry<BlockPosition, BlockState> entry : generated.fluids().entrySet()) {
                    BlockPosition position = entry.getKey();
                    blocks.add(new NexoraAndroidProtocolCodec.ChunkBlock(
                            position.localX(), position.y(), position.localZ(),
                            entry.getValue().identifier()));
                }
                blocks.sort(Comparator
                        .comparingInt(NexoraAndroidProtocolCodec.ChunkBlock::localX)
                        .thenComparingInt(NexoraAndroidProtocolCodec.ChunkBlock::localZ)
                        .thenComparingInt(NexoraAndroidProtocolCodec.ChunkBlock::y)
                        .thenComparing(NexoraAndroidProtocolCodec.ChunkBlock::blockId));
                chunks.add(new NexoraAndroidProtocolCodec.ChunkData(
                        chunkX, chunkZ, List.copyOf(blocks)));
            }
        }
        return new PreviewData(List.copyOf(chunks), spawnY);
    }

    record PreviewData(List<NexoraAndroidProtocolCodec.ChunkData> chunks, int spawnY) { }
}
