package ru.nexora.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MobPathCacheTest {
    @Test
    void reusesARecentPathForTheSameMobAndRequest() {
        World world = new World("mob-path-cache", 0L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        MobPathCache cache = new MobPathCache(8, 150);

        var first = cache.findNextStep(7L, world, 0.5, 65, 0.5, 3.5, 0.5, 1_000);
        var second = cache.findNextStep(7L, world, 0.5, 65, 0.5, 3.5, 0.5, 1_050);

        assertEquals(first, second);
        assertEquals(1, cache.size());
        assertEquals(1, cache.stats().hits());
        assertEquals(1, cache.stats().misses());
        assertEquals(1, cache.stats().recomputations());
        assertEquals(0.5, cache.stats().hitRate());
    }

    @Test
    void revalidatesCachedWaypointWhenWorldChanges() {
        World world = new World("mob-path-cache-change", 0L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        MobPathCache cache = new MobPathCache(8, 150);
        var original = cache.findNextStep(9L, world, 0.5, 65, 0.5, 3.5, 0.5, 2_000)
                .orElseThrow();

        world.setBlock((int) Math.floor(original.x()), 65,
                (int) Math.floor(original.z()), BlockType.STONE);

        var replacement = cache.findNextStep(9L, world, 0.5, 65, 0.5, 3.5, 0.5, 2_050)
                .orElseThrow();

        assertNotEquals(original, replacement);
        assertTrue(WorldCollision.canStepMob(world, "minecraft:zombie",
                0.5, 65, 0.5, replacement.x(), replacement.y(), replacement.z()));
        assertEquals(1, cache.stats().blockedWaypoints());
        assertEquals(2, cache.stats().recomputations());
    }

    @Test
    void doesNotCacheNoPathResultsWhenTerrainCanChange() {
        World world = new World("mob-path-cache-open", 0L, new FlatWorldGenerator());
        world.setBlock(1, 65, 0, BlockType.STONE);
        world.setBlock(1, 66, 0, BlockType.STONE);
        world.setBlock(-1, 65, 0, BlockType.STONE);
        world.setBlock(-1, 66, 0, BlockType.STONE);
        world.setBlock(0, 65, 1, BlockType.STONE);
        world.setBlock(0, 66, 1, BlockType.STONE);
        world.setBlock(0, 65, -1, BlockType.STONE);
        world.setBlock(0, 66, -1, BlockType.STONE);
        MobPathCache cache = new MobPathCache(8, 150);

        assertTrue(cache.findNextStep(11L, world, 0.5, 65, 0.5, 3.5, 0.5, 3_000).isEmpty());
        assertEquals(0, cache.size());

        world.setBlock(1, 65, 0, BlockType.AIR);
        world.setBlock(1, 66, 0, BlockType.AIR);
        assertTrue(cache.findNextStep(11L, world, 0.5, 65, 0.5, 3.5, 0.5, 3_050).isPresent());
        assertEquals(2, cache.stats().recomputations());
        assertEquals(2, cache.stats().misses());
    }

    @Test
    void expiresEntriesAndKeepsTheCacheBounded() {
        World world = new World("mob-path-cache-bound", 0L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        MobPathCache cache = new MobPathCache(2, 10);

        cache.findNextStep(1L, world, 0.5, 65, 0.5, 3.5, 0.5, 100);
        cache.findNextStep(2L, world, 0.5, 65, 1.5, 3.5, 1.5, 100);
        cache.findNextStep(3L, world, 0.5, 65, 2.5, 3.5, 2.5, 100);
        assertEquals(2, cache.size());
        assertEquals(1, cache.stats().evictions());

        cache.findNextStep(3L, world, 0.5, 65, 2.5, 3.5, 2.5, 111);
        assertEquals(2, cache.size());
        assertEquals(4, cache.stats().misses());
        cache.remove(3L);
        assertEquals(1, cache.size());
    }

    @Test
    void rejectsNegativeMobIdsAndNavigationTimestamps() {
        World world = new World("mob-path-cache-invalid-time", 0L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        MobPathCache cache = new MobPathCache();

        assertThrows(IllegalArgumentException.class, () ->
                cache.findNextStep(-1L, world, 0.5, 65, 0.5, 2.5, 0.5, 1_000));
        assertThrows(IllegalArgumentException.class, () ->
                cache.findNextStep(0L, world, 0.5, 65, 0.5, 2.5, 0.5, 1_000));
        assertThrows(IllegalArgumentException.class, () ->
                cache.findNextStep(1L, world, 0.5, 65, 0.5, 2.5, 0.5, -1));
        assertEquals(0, cache.stats().requests());
    }

    @Test
    void ttlAdditionSaturatesInsteadOfOverflowingAtLargeTimestamps() {
        World world = new World("mob-path-cache-time-overflow", 0L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        MobPathCache cache = new MobPathCache(4, 10);
        long nearMaximum = Long.MAX_VALUE - 5;

        var first = cache.findNextStep(4L, world, 0.5, 65, 0.5, 2.5, 0.5, nearMaximum);
        var second = cache.findNextStep(4L, world, 0.5, 65, 0.5, 2.5, 0.5, nearMaximum);

        assertTrue(first.isPresent());
        assertEquals(first, second);
        assertEquals(1, cache.stats().hits());
        assertEquals(1, cache.stats().recomputations());
    }

    @Test
    void emptyCacheHasZeroHitRate() {
        MobPathCache cache = new MobPathCache();
        assertEquals(0.0, cache.stats().hitRate());
        assertEquals(0, cache.stats().requests());
    }

    @Test
    void requestCountSaturatesInsteadOfOverflowing() {
        MobPathCache.Stats stats = new MobPathCache.Stats(
                Long.MAX_VALUE, 1, 0, 0, 0, 0);

        assertEquals(Long.MAX_VALUE, stats.requests());
        assertEquals(1.0, stats.hitRate());
    }

    @Test
    void statsSummaryIsCompactAndContainsOnlyAggregateCounters() {
        MobPathCache.Stats stats = new MobPathCache.Stats(9, 1, 1, 0, 0, 1);

        assertEquals("hits=9, misses=1, hitRate=90.0%, recomputations=1, blocked=0, evictions=0, entries=1",
                stats.summary());
    }
    @Test
    void clearRemovesAllCachedPathsWithoutChangingHistoricalCounters() {
        World world = new World("mob-path-cache-clear", 0L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        MobPathCache cache = new MobPathCache();
        cache.findNextStep(77L, world, 0.5, 65, 0.5, 2.5, 0.5, 1_000);
        cache.findNextStep(77L, world, 0.5, 65, 0.5, 2.5, 0.5, 1_010);

        cache.clear();

        assertEquals(0, cache.size());
        assertEquals(1, cache.stats().hits());
        assertEquals(1, cache.stats().recomputations());
    }

}
