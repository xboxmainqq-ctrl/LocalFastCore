package ru.nexora.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ru.nexora.runtime.BlockPosition;
import ru.nexora.runtime.BlockState;
import ru.nexora.runtime.ChunkPosition;
import ru.nexora.runtime.GeneratedChunk;
import ru.nexora.runtime.OverworldChunkGenerator;
import ru.nexora.runtime.WorldHeight;

/** Adapts the real core generator's immutable chunk snapshots to the Android preview renderer. */
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
                    if (chunkX == 0 && chunkZ == 0 && position.x() == 0
                            && position.z() == 0) {
                        spawnY = Math.max(spawnY, position.y() + 1);
                    }
                }
                for (Map.Entry<BlockPosition, BlockState> entry : generated.fluids().entrySet()) {
                    BlockPosition position = entry.getKey();
                    blocks.add(new NexoraAndroidProtocolCodec.ChunkBlock(
                            position.localX(), position.y(), position.localZ(),
                            entry.getValue().identifier()));
                }
                chunks.add(new NexoraAndroidProtocolCodec.ChunkData(
                        chunkX, chunkZ, List.copyOf(blocks)));
            }
        }
        return new PreviewData(List.copyOf(chunks), spawnY);
    }

    record PreviewData(List<NexoraAndroidProtocolCodec.ChunkData> chunks, int spawnY) { }
}
