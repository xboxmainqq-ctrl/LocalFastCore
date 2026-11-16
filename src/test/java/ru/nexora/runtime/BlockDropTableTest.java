package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlockDropTableTest {
    @Test
    void stoneDropsCobblestoneAndGrassDropsDirt() {
        assertEquals("minecraft:cobblestone",
                BlockDropTable.dropFor(new BlockState("minecraft:stone")));
        assertEquals("minecraft:dirt",
                BlockDropTable.dropFor(new BlockState("minecraft:grass_block")));
    }

    @Test
    void oresDropTheirBasicSurvivalResources() {
        assertEquals("minecraft:raw_iron",
                BlockDropTable.dropFor(new BlockState("minecraft:iron_ore")));
        assertEquals("minecraft:diamond",
                BlockDropTable.dropFor(new BlockState("minecraft:deepslate_diamond_ore")));
        assertEquals("minecraft:coal",
                BlockDropTable.dropFor(new BlockState("minecraft:coal_ore")));
    }

    @Test
    void unsupportedBlocksDoNotCreateItems() {
        assertNull(BlockDropTable.dropFor(new BlockState("minecraft:glass")));
        assertNull(BlockDropTable.dropFor(new BlockState("minecraft:bedrock")));
    }

    @Test
    void rejectsNullState() {
        assertThrows(NullPointerException.class, () -> BlockDropTable.dropFor(null));
    }
}
