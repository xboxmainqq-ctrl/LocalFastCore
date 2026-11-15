package ru.nexora.examples;

import ru.nexora.runtime.BlockPosition;
import ru.nexora.runtime.BlockState;
import ru.nexora.runtime.ChunkGenerator;
import ru.nexora.runtime.ChunkPosition;
import ru.nexora.runtime.GeneratedChunk;
import ru.nexora.runtime.WorldGeneratorExtension;
import ru.nexora.runtime.WorldHeight;

import java.util.HashMap;
import java.util.Map;

/** Small example extension that creates a deterministic grassy flat world. */
public final class FlatWorldGeneratorExtension implements WorldGeneratorExtension {
    @Override
    public String id() {
        return "example_flat";
    }

    @Override
    public ChunkGenerator createGenerator() {
        return FlatGenerator.INSTANCE;
    }

    private static final class FlatGenerator implements ChunkGenerator {
        private static final FlatGenerator INSTANCE = new FlatGenerator();
        private static final BlockState BEDROCK = new BlockState("minecraft:bedrock");
        private static final BlockState STONE = new BlockState("minecraft:stone");
        private static final BlockState DIRT = new BlockState("minecraft:dirt");
        private static final BlockState GRASS = new BlockState("minecraft:grass_block");

        @Override
        public GeneratedChunk generate(ChunkPosition position, WorldHeight height, long seed) {
            int surfaceY = Math.min(height.maxYExclusive() - 1, height.minY() + 5);
            int baseX = Math.multiplyExact(position.x(), 16);
            int baseZ = Math.multiplyExact(position.z(), 16);
            Map<BlockPosition, BlockState> blocks = new HashMap<>(16 * 16 * 6);

            for (int localX = 0; localX < 16; localX++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    int x = Math.addExact(baseX, localX);
                    int z = Math.addExact(baseZ, localZ);
                    blocks.put(new BlockPosition(x, height.minY(), z), BEDROCK);
                    for (int y = height.minY() + 1; y < surfaceY - 3; y++) {
                        blocks.put(new BlockPosition(x, y, z), STONE);
                    }
                    for (int y = Math.max(height.minY() + 1, surfaceY - 3); y < surfaceY; y++) {
                        blocks.put(new BlockPosition(x, y, z), DIRT);
                    }
                    blocks.put(new BlockPosition(x, surfaceY, z), GRASS);
                }
            }
            return new GeneratedChunk(position, blocks);
        }
    }
}
