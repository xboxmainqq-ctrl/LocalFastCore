package ru.nexora.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Small deterministic flat-terrain generator intended for tests and the first
 * playable-world milestone. It creates a foundation layer, filler layers,
 * and a surface layer across all 256 columns in a chunk.
 */
public final class FlatChunkGenerator implements ChunkGenerator {
    private static final int MAX_DEPTH = 512;
    private final int surfaceY;
    private final int depth;
    private final BlockState surface;
    private final BlockState filler;
    private final BlockState foundation;

    /**
     * @param surfaceY absolute Y coordinate of the top layer
     * @param depth vertical distance in blocks from the foundation layer to the surface layer;
     *              must be between one and the configured safety limit
     */
    public FlatChunkGenerator(
            int surfaceY,
            int depth,
            BlockState surface,
            BlockState filler,
            BlockState foundation
    ) {
        if (depth < 1 || depth > MAX_DEPTH) {
            throw new IllegalArgumentException("Flat terrain depth must be between 1 and " + MAX_DEPTH);
        }
        this.surfaceY = surfaceY;
        this.depth = depth;
        this.surface = requireSolid(surface, "surface");
        this.filler = requireSolid(filler, "filler");
        this.foundation = requireSolid(foundation, "foundation");
    }

    @Override
    public GeneratedChunk generate(ChunkPosition position, WorldHeight height, long seed) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(height, "height");
        int foundationY;
        try {
            foundationY = Math.subtractExact(surfaceY, depth);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Flat terrain depth exceeds supported Y coordinates", exception);
        }
        if (!height.contains(surfaceY) || !height.contains(foundationY)) {
            throw new IllegalArgumentException(
                    "Flat terrain layers [" + foundationY + ", " + surfaceY
                            + "] are outside world height [" + height.minY()
                            + ", " + height.maxYExclusive() + ")"
            );
        }

        int baseX;
        int baseZ;
        try {
            baseX = Math.multiplyExact(position.x(), 16);
            baseZ = Math.multiplyExact(position.z(), 16);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Chunk coordinates exceed supported block coordinates", exception);
        }

        Map<BlockPosition, BlockState> blocks = new HashMap<>(256 * (depth + 1));
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int x = Math.addExact(baseX, localX);
                int z = Math.addExact(baseZ, localZ);
                blocks.put(new BlockPosition(x, foundationY, z), foundation);
                for (int y = foundationY + 1; y < surfaceY; y++) {
                    blocks.put(new BlockPosition(x, y, z), filler);
                }
                blocks.put(new BlockPosition(x, surfaceY, z), surface);
            }
        }
        return new GeneratedChunk(position, blocks);
    }

    private static BlockState requireSolid(BlockState state, String name) {
        Objects.requireNonNull(state, name);
        if (state.isAir()) {
            throw new IllegalArgumentException("Flat terrain " + name + " block must not be air");
        }
        return state;
    }
}
