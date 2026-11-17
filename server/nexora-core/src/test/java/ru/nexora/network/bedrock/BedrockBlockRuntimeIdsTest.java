package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import ru.nexora.world.BlockType;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BedrockBlockRuntimeIdsTest {
    @Test
    void matchesProtocol2225VanillaBlockStateHashes() {
        assertEquals(0xdbf44120, BedrockBlockRuntimeIds.forBlock(BlockType.AIR));
        assertEquals(0x80310e21, BedrockBlockRuntimeIds.forBlock(BlockType.STONE));
        assertEquals(0x824eef86, BedrockBlockRuntimeIds.forBlock(BlockType.DIRT));
        assertEquals(0xde3128b4, BedrockBlockRuntimeIds.forBlock(BlockType.GRASS_BLOCK));
        assertEquals(0xf5ac7cfb, BedrockBlockRuntimeIds.forBlock(BlockType.BEDROCK));
        assertEquals(0x67d16e2e, BedrockBlockRuntimeIds.forBlock(BlockType.COBBLESTONE));
        assertEquals(0x5b235f1a, BedrockBlockRuntimeIds.forBlock(BlockType.GRAVEL));
        assertEquals(0x374b17d2, BedrockBlockRuntimeIds.forBlock(BlockType.GLASS));
        assertEquals(0xa5cfa9ba, BedrockBlockRuntimeIds.forBlock(BlockType.OBSIDIAN));
        assertEquals(0x803389c0, BedrockBlockRuntimeIds.forBlock(BlockType.NETHERRACK));
        assertEquals(0xb5438557, BedrockBlockRuntimeIds.forBlock(BlockType.END_STONE));
        assertEquals(0x7ec4fa68, BedrockBlockRuntimeIds.forBlock(BlockType.COAL_BLOCK));
        assertEquals(0xc806ec5f, BedrockBlockRuntimeIds.forBlock(BlockType.IRON_BLOCK));
        assertEquals(0x5c6024c3, BedrockBlockRuntimeIds.forBlock(BlockType.GOLD_BLOCK));
        assertEquals(0x57067910, BedrockBlockRuntimeIds.forBlock(BlockType.DIAMOND_BLOCK));
        assertEquals(0x2deae764, BedrockBlockRuntimeIds.forBlock(BlockType.EMERALD_BLOCK));
        assertEquals(0xbc85430e, BedrockBlockRuntimeIds.forBlock(BlockType.CHEST));
        assertEquals(0xc4f2d3e4, BedrockBlockRuntimeIds.forBlock(BlockType.OAK_LOG));
        assertEquals(0x37abcd4f, BedrockBlockRuntimeIds.forBlock(BlockType.OAK_PLANKS));
        assertEquals(0x68702cc0, BedrockBlockRuntimeIds.forBlock(BlockType.CRAFTING_TABLE));
        assertEquals(0xce70c659, BedrockBlockRuntimeIds.forBlock(BlockType.FURNACE));
        assertEquals(0x7fb511dc, BedrockBlockRuntimeIds.forBlock(BlockType.BLAST_FURNACE));
        assertEquals(0xccc0ac65, BedrockBlockRuntimeIds.forBlock(BlockType.SMOKER));
    }
}
