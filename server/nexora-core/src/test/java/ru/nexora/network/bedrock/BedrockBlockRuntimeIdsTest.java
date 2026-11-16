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
    }
}
