package ru.nexora.combat;

import org.junit.jupiter.api.Test;
import ru.nexora.world.BlockType;

import static org.junit.jupiter.api.Assertions.*;

class GhastExplosionTest {
    @Test
    void damageFallsOffWithDistanceAndStopsAtRadius() {
        assertEquals(8.0f, GhastExplosion.damageAtDistance(0.0), 0.0001f);
        assertEquals(4.0f, GhastExplosion.damageAtDistance(2.0), 0.0001f);
        assertEquals(0.0f, GhastExplosion.damageAtDistance(4.0), 0.0001f);
        assertThrows(IllegalArgumentException.class, () -> GhastExplosion.damageAtDistance(Double.NaN));
    }

    @Test
    void blockBlastHasBoundedRadiusAndProtectsContainersAndBlastImmuneBlocks() {
        assertTrue(GhastExplosion.isInsideBlockBlast(1.0, 1.0, 1.0));
        assertFalse(GhastExplosion.isInsideBlockBlast(3.0, 0.0, 0.0));
        assertTrue(GhastExplosion.canDestroy(BlockType.STONE));
        assertTrue(GhastExplosion.canDestroy(BlockType.OAK_PLANKS));
        assertFalse(GhastExplosion.canDestroy(BlockType.CHEST));
        assertFalse(GhastExplosion.canDestroy(BlockType.SPAWNER));
        assertFalse(GhastExplosion.canDestroy(BlockType.OBSIDIAN));
        assertFalse(GhastExplosion.canDestroy(BlockType.BEDROCK));
    }
}
