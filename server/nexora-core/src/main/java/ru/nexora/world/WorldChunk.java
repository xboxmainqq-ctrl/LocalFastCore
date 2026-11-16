package ru.nexora.world;

import java.util.Objects;

/** One 16x384x16 Overworld column, spanning block Y=-64 through Y=319. */
public final class WorldChunk {
    public static final int MIN_Y = -64;
    public static final int MAX_Y = 319;
    public static final int SECTION_COUNT = 24;

    private final int chunkX;
    private final int chunkZ;
    private final ChunkSection[] sections = new ChunkSection[SECTION_COUNT];

    public WorldChunk(int chunkX, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    public BlockType getBlock(int localX, int worldY, int localZ) {
        validateLocal(localX, localZ);
        int sectionIndex = sectionIndex(worldY);
        ChunkSection section = sections[sectionIndex];
        return section == null ? BlockType.AIR
                : section.get(localX, worldY & 15, localZ);
    }

    public void setBlock(int localX, int worldY, int localZ, BlockType type) {
        validateLocal(localX, localZ);
        int sectionIndex = sectionIndex(worldY);
        Objects.requireNonNull(type, "type");
        if (sections[sectionIndex] == null) {
            if (type == BlockType.AIR) return;
            sections[sectionIndex] = new ChunkSection();
        }
        sections[sectionIndex].set(localX, worldY & 15, localZ, type);
        if (type == BlockType.AIR && sections[sectionIndex].isEmpty()) {
            sections[sectionIndex] = null;
        }
    }

    public ChunkSection section(int index) {
        if (index < 0 || index >= SECTION_COUNT) throw new IndexOutOfBoundsException("Invalid section index");
        return sections[index];
    }

    public int highestNonEmptySectionCount() {
        for (int i = sections.length - 1; i >= 0; i--) {
            if (sections[i] != null && !sections[i].isEmpty()) return i + 1;
        }
        return 0;
    }

    private static int sectionIndex(int worldY) {
        if (worldY < MIN_Y || worldY > MAX_Y) {
            throw new IndexOutOfBoundsException("Overworld Y must be between -64 and 319");
        }
        return (worldY - MIN_Y) >> 4;
    }

    private static void validateLocal(int x, int z) {
        if (x < 0 || x > 15 || z < 0 || z > 15) {
            throw new IndexOutOfBoundsException("Chunk-local X/Z coordinates must be in 0..15");
        }
    }
}
