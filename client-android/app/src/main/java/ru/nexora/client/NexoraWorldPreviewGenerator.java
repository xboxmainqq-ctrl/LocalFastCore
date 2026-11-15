package ru.nexora.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight offline terrain preview used only by the Android client before
 * it connects to a server. The authoritative world generator lives on NEXORA
 * Core; this preview intentionally has no dependency on server-side classes.
 */
final class NexoraWorldPreviewGenerator {
    private static final int MIN_Y = -64;
    private static final int WATER_LEVEL = 62;

    private NexoraWorldPreviewGenerator() { }

    static PreviewData generate(long seed) {
        List<NexoraAndroidProtocolCodec.ChunkData> chunks = new ArrayList<>(9);
        int spawnY = MIN_Y + 2;

        for (int chunkX = -1; chunkX <= 1; chunkX++) {
            for (int chunkZ = -1; chunkZ <= 1; chunkZ++) {
                List<NexoraAndroidProtocolCodec.ChunkBlock> blocks = new ArrayList<>(32_000);
                for (int localX = 0; localX < 16; localX++) {
                    for (int localZ = 0; localZ < 16; localZ++) {
                        int worldX = chunkX * 16 + localX;
                        int worldZ = chunkZ * 16 + localZ;
                        int surfaceY = surfaceHeight(worldX, worldZ, seed);
                        for (int y = MIN_Y; y <= surfaceY; y++) {
                            String block;
                            if (y == MIN_Y) {
                                block = "minecraft:bedrock";
                            } else if (y == surfaceY) {
                                block = surfaceY <= WATER_LEVEL + 1
                                        ? "minecraft:sand" : "minecraft:grass_block";
                            } else if (y >= surfaceY - 3) {
                                block = surfaceY <= WATER_LEVEL + 1
                                        ? "minecraft:sand" : "minecraft:dirt";
                            } else {
                                block = "minecraft:stone";
                            }
                            blocks.add(new NexoraAndroidProtocolCodec.ChunkBlock(
                                    localX, y, localZ, block));
                        }
                        if (surfaceY < WATER_LEVEL) {
                            for (int y = surfaceY + 1; y <= WATER_LEVEL; y++) {
                                blocks.add(new NexoraAndroidProtocolCodec.ChunkBlock(
                                        localX, y, localZ, "minecraft:water"));
                            }
                        }
                        if (chunkX == 0 && chunkZ == 0 && worldX == 0 && worldZ == 0) {
                            spawnY = surfaceY + 1;
                        }
                    }
                }
                chunks.add(new NexoraAndroidProtocolCodec.ChunkData(
                        chunkX, chunkZ, List.copyOf(blocks)));
            }
        }
        return new PreviewData(List.copyOf(chunks), spawnY);
    }

    private static int surfaceHeight(int x, int z, long seed) {
        double broad = Math.sin((x + seed * 0.00013) * 0.075)
                * 5.0 + Math.cos((z - seed * 0.00017) * 0.068) * 4.0;
        double detail = Math.sin((x + z) * 0.19 + seed * 0.00031) * 2.0
                + Math.cos((x - z) * 0.15 - seed * 0.00023) * 1.5;
        return 68 + (int) Math.round(broad + detail);
    }

    record PreviewData(List<NexoraAndroidProtocolCodec.ChunkData> chunks, int spawnY) { }
}
