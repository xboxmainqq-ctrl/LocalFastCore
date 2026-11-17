package ru.nexora.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MobPathCacheTest {
    @Test
    void reusesARecentPathForTheSameMobAndRequest() {
        World world = new World("mob-path-cache", 0L, new FlatWorldGenerator());
        MobPathCache cache = new MobPathCache(8, 150);

        var first = cache.findNextStep(7L, world, 0.5, 65, 0.5, 3.5, 0.5, 1_000);
        var second = cache.findNextStep(7L, world, 0.5, 65, 0.5, 3.5, 0.5, 1_050);

        assertEquals(first, second);
        assertEquals(1, cache.size());
    }

    @Test
    void revalidatesCachedWaypointWhenWorldChanges() {
        World world = new World("mob-path-cache-change", 0L, new FlatWorldGenerator());
        MobPathCache cache = new MobPathCache(8, 150);
        var original = cache.findNextStep(9L, world, 0.5, 65, 0.5, 3.5, 0.5, 2_000)
                .orElseThrow();

        world.setBlock((int) Math.floor(original.x()), 65,
                (int) Math.floor(original.z()), BlockType.STONE);

        var replacement = cache.findNextStep(9L, world, 0.5, 65, 0.5, 3.5, 0.5, 2_050)
                .orElseThrow();

        assertNotEquals(original, replacement);
        assertTrue(WorldCollision.canMove(world, 0.5, 65, 0.5,
                replacement.x(), 65, replacement.z()));
    }

    @Test
    void doesNotCacheNoPathResultsWhenTerrainCanChange() {
        World world = new World("mob-path-cache-open", 0L, new FlatWorldGenerator());
        world.setBlock(1, 65, 0, BlockType.STONE);
        world.setBlock(-1, 65, 0, BlockType.STONE);
        world.setBlock(0, 65, 1, BlockType.STONE);
        world.setBlock(0, 65, -1, BlockType.STONE);
        MobPathCache cache = new MobPathCache(8, 150);

        assertTrue(cache.findNextStep(11L, world, 0.5, 65, 0.5, 3.5, 0.5, 3_000).isEmpty());
        assertEquals(0, cache.size());

        world.setBlock(1, 65, 0, BlockType.AIR);
        assertTrue(cache.findNextStep(11L, world, 0.5, 65, 0.5, 3.5, 0.5, 3_050).isPresent());
    }

    @Test
    void expiresEntriesAndKeepsTheCacheBounded() {
        World world = new World("mob-path-cache-bound", 0L, new FlatWorldGenerator());
        MobPathCache cache = new MobPathCache(2, 10);

        cache.findNextStep(1L, world, 0.5, 65, 0.5, 3.5, 0.5, 100);
        cache.findNextStep(2L, world, 0.5, 65, 1.5, 3.5, 1.5, 100);
        cache.findNextStep(3L, world, 0.5, 65, 2.5, 3.5, 2.5, 100);
        assertEquals(2, cache.size());

        cache.findNextStep(3L, world, 0.5, 65, 2.5, 3.5, 2.5, 111);
        assertEquals(2, cache.size());
        cache.remove(3L);
        assertEquals(1, cache.size());
    }
}


## Mob path cache metrics

Each `MobPathCache` instance exposes a cumulative, thread-safe `stats()` snapshot: cache hits, misses, path recomputations, cached waypoints rejected by collision revalidation, LRU evictions, and current entry count. The snapshot also provides request count and hit rate. Counters are instance-local and do not claim TPS or CPU savings; compare them with pathfinding latency under a controlled mob load. No-path results remain uncached so terrain changes can be observed immediately.
