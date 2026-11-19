package ru.nexora.network.bedrock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bounded, deterministic chunk-view traversal for one Bedrock session.
 *
 * <p>The queue stores chunk coordinates as packed keys and exposes small batches so
 * packet encoding and transport submission can be spread across player input ticks.</p>
 */
final class ChunkStreamingQueue {
    private final int centerX;
    private final int centerZ;
    private final int radius;
    private final List<Long> orderedChunkKeys;
    private final Set<Long> desiredChunkKeys;
    private int cursor;

    ChunkStreamingQueue(int centerX, int centerZ, int radius) {
        if (radius < 0 || radius > 32) {
            throw new IllegalArgumentException("radius must be between 0 and 32");
        }
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = radius;
        int diameter = radius * 2 + 1;
        List<Long> ordered = new ArrayList<>(diameter * diameter);
        Set<Long> desired = new HashSet<>(diameter * diameter * 2);
        for (int ring = 0; ring <= radius; ring++) {
            if (ring == 0) {
                add(ordered, desired, centerX, centerZ);
                continue;
            }
            int min = -ring;
            int max = ring;
            for (int offsetX = min; offsetX <= max; offsetX++) {
                add(ordered, desired, centerX + offsetX, centerZ + min);
                add(ordered, desired, centerX + offsetX, centerZ + max);
            }
            for (int offsetZ = min + 1; offsetZ < max; offsetZ++) {
                add(ordered, desired, centerX + min, centerZ + offsetZ);
                add(ordered, desired, centerX + max, centerZ + offsetZ);
            }
        }
        this.orderedChunkKeys = List.copyOf(ordered);
        this.desiredChunkKeys = Set.copyOf(desired);
    }

    private static void add(List<Long> ordered, Set<Long> desired, int x, int z) {
        long key = chunkKey(x, z);
        if (desired.add(key)) ordered.add(key);
    }

    static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffff_ffffL);
    }

    List<Long> peekBatch(int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("maximum must be positive");
        int end = Math.min(orderedChunkKeys.size(), cursor + maximum);
        return List.copyOf(orderedChunkKeys.subList(cursor, end));
    }

    void advance(int count) {
        if (count < 0 || count > remaining()) {
            throw new IllegalArgumentException("advance count exceeds remaining chunks");
        }
        cursor += count;
    }

    List<Long> nextBatch(int maximum) {
        List<Long> batch = peekBatch(maximum);
        advance(batch.size());
        return batch;
    }

    boolean isComplete() {
        return cursor >= orderedChunkKeys.size();
    }

    int remaining() {
        return orderedChunkKeys.size() - cursor;
    }

    int centerX() { return centerX; }
    int centerZ() { return centerZ; }
    int radius() { return radius; }
    Set<Long> desiredChunkKeys() { return desiredChunkKeys; }
}
