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
    private long revision;
    private long persistedRevision = -1L;

    public WorldChunk(int chunkX, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }

    public synchronized BlockType getBlock(int localX, int worldY, int localZ) {
        validateLocal(localX, localZ);
        int sectionIndex = sectionIndex(worldY);
        ChunkSection section = sections[sectionIndex];
        return section == null ? BlockType.AIR
                : section.get(localX, worldY & 15, localZ);
    }

    public synchronized void setBlock(int localX, int worldY, int localZ, BlockType type) {
        setBlockAndGetPrevious(localX, worldY, localZ, type);
    }

    /** Mutates one block and returns its previous value under the same chunk lock. */
    public synchronized BlockType setBlockAndGetPrevious(int localX, int worldY, int localZ, BlockType type) {
        validateLocal(localX, localZ);
        int sectionIndex = sectionIndex(worldY);
        Objects.requireNonNull(type, "type");
        ChunkSection section = sections[sectionIndex];
        BlockType previous = section == null ? BlockType.AIR : section.get(localX, worldY & 15, localZ);
        if (previous == type) return previous;
        if (section == null) {
            if (type == BlockType.AIR) return previous;
            section = new ChunkSection();
            sections[sectionIndex] = section;
        }
        section.set(localX, worldY & 15, localZ, type);
        revision++;
        if (type == BlockType.AIR && section.isEmpty()) {
            sections[sectionIndex] = null;
        }
        return previous;
    }

    /**
     * Returns a detached section copy. Mutating the returned value cannot bypass
     * the chunk's synchronization or alter persisted/networked world state.
     */
    public synchronized ChunkSection section(int index) {
        if (index < 0 || index >= SECTION_COUNT) throw new IndexOutOfBoundsException("Invalid section index");
        byte[] raw = sections[index] == null ? null : sections[index].copyRawBlocks();
        if (raw == null) return null;
        ChunkSection copy = new ChunkSection();
        copy.loadRawBlocks(raw);
        return copy;
    }

    public synchronized byte[] copySectionRaw(int index) {
        if (index < 0 || index >= SECTION_COUNT) throw new IndexOutOfBoundsException("Invalid section index");
        return sections[index] == null ? null : sections[index].copyRawBlocks();
    }

    /** Exact bytes held by the chunk's allocated block-section arrays (excludes JVM object overhead). */
    public synchronized long allocatedBlockStorageBytes() {
        long bytes = 0L;
        for (ChunkSection section : sections) {
            if (section != null) bytes += ChunkSection.VOLUME;
        }
        return bytes;
    }

    /** Number of allocated non-empty block sections. */
    public synchronized int allocatedSectionCount() {
        int count = 0;
        for (ChunkSection section : sections) if (section != null) count++;
        return count;
    }

    /** Monotonic mutation version used to detect writes racing persistence. */
    public synchronized long revision() { return revision; }

    /** True when this chunk contains generated or mutated data not yet durably stored. */
    public synchronized boolean isDirty() {
        return revision != persistedRevision;
    }

    /**
     * Marks exactly the written revision as durable. If the chunk changed while the
     * write was in progress, its newer revision remains dirty for the next write.
     */
    public synchronized void markPersisted(long savedRevision) {
        if (savedRevision < 0 || savedRevision > revision) {
            throw new IllegalArgumentException("Persisted chunk revision is outside the current revision range");
        }
        if (savedRevision > persistedRevision) persistedRevision = savedRevision;
    }

    /** Consistent detached persistence image and its mutation version. */
    public synchronized PersistenceSnapshot persistenceSnapshot() {
        byte[][] raw = new byte[SECTION_COUNT][];
        for (int index = 0; index < SECTION_COUNT; index++) {
            raw[index] = sections[index] == null ? null : sections[index].copyRawBlocks();
        }
        return new PersistenceSnapshot(revision, raw);
    }

    public record PersistenceSnapshot(long revision, byte[][] sections) {
        public PersistenceSnapshot {
            if (revision < 0 || sections == null || sections.length != SECTION_COUNT) {
                throw new IllegalArgumentException("Invalid chunk persistence snapshot");
            }
            byte[][] detached = new byte[sections.length][];
            for (int index = 0; index < sections.length; index++) {
                byte[] section = sections[index];
                if (section != null && section.length != ChunkSection.VOLUME) {
                    throw new IllegalArgumentException("Invalid chunk persistence section length");
                }
                detached[index] = section == null ? null : section.clone();
            }
            sections = detached;
        }

        /** Returns a deep copy so persistence consumers cannot mutate the captured image. */
        @Override
        public byte[][] sections() {
            byte[][] detached = new byte[sections.length][];
            for (int index = 0; index < sections.length; index++) {
                detached[index] = sections[index] == null ? null : sections[index].clone();
            }
            return detached;
        }
    }

    public synchronized byte[][] copySectionsRaw() {
        byte[][] snapshot = new byte[SECTION_COUNT][];
        for (int index = 0; index < SECTION_COUNT; index++) {
            snapshot[index] = sections[index] == null ? null : sections[index].copyRawBlocks();
        }
        return snapshot;
    }

    public synchronized void loadSectionRaw(int index, byte[] raw) {
        if (index < 0 || index >= SECTION_COUNT) throw new IndexOutOfBoundsException("Invalid section index");
        if (raw == null) {
            if (sections[index] != null) {
                sections[index] = null;
                revision++;
            }
            return;
        }
        ChunkSection section = new ChunkSection();
        section.loadRawBlocks(raw);
        sections[index] = section.isEmpty() ? null : section;
        revision++;
    }

    public synchronized int highestNonEmptySectionCount() {
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
