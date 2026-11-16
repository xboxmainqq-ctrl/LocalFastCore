package ru.nexora.runtime;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Exports a top-down PNG preview from the actual authoritative chunk generator.
 * This is a diagnostic rendering of generated blocks and fluids, not concept art.
 */
public final class WorldPreviewRenderer {
    private static final WorldHeight DEFAULT_HEIGHT = new WorldHeight(-64, 320);

    private WorldPreviewRenderer() {
    }

    /**
     * Generates a square region of (2 * radiusChunks + 1) chunks and writes a PNG.
     *
     * @param seed world seed
     * @param radiusChunks chunk radius around the origin, from 0 to 16
     * @param output destination PNG path
     */
    public static Path render(long seed, int radiusChunks, Path output) throws IOException {
        if (radiusChunks < 0 || radiusChunks > 16) {
            throw new IllegalArgumentException("radiusChunks must be between 0 and 16");
        }
        if (output == null) {
            throw new NullPointerException("output");
        }

        int chunkDiameter = radiusChunks * 2 + 1;
        int size = chunkDiameter * 16;
        int[][] surfaceY = new int[size][size];
        String[][] surfaceBlock = new String[size][size];
        boolean[][] water = new boolean[size][size];
        int[][] biomeIds = new int[size][size];
        for (int[] row : surfaceY) {
            Arrays.fill(row, DEFAULT_HEIGHT.minY() - 1);
        }

        OverworldChunkGenerator generator = new OverworldChunkGenerator();
        for (int chunkZ = -radiusChunks; chunkZ <= radiusChunks; chunkZ++) {
            for (int chunkX = -radiusChunks; chunkX <= radiusChunks; chunkX++) {
                GeneratedChunk chunk = generator.generate(
                        new ChunkPosition(chunkX, chunkZ), DEFAULT_HEIGHT, seed);
                int offsetX = (chunkX + radiusChunks) * 16;
                int offsetZ = (chunkZ + radiusChunks) * 16;

                for (Map.Entry<BlockPosition, BlockState> entry : chunk.blocks().entrySet()) {
                    BlockPosition position = entry.getKey();
                    int x = offsetX + Math.floorMod(position.x(), 16);
                    int z = offsetZ + Math.floorMod(position.z(), 16);
                    if (position.y() >= surfaceY[x][z]) {
                        surfaceY[x][z] = position.y();
                        surfaceBlock[x][z] = entry.getValue().identifier();
                    }
                }
                for (Map.Entry<BiomeColumnPosition, Integer> entry : chunk.biomeColumns().entrySet()) {
                    BiomeColumnPosition position = entry.getKey();
                    int x = offsetX + Math.floorMod(position.x(), 16);
                    int z = offsetZ + Math.floorMod(position.z(), 16);
                    biomeIds[x][z] = entry.getValue();
                }
                for (BlockPosition position : chunk.fluids().keySet()) {
                    int x = offsetX + Math.floorMod(position.x(), 16);
                    int z = offsetZ + Math.floorMod(position.z(), 16);
                    if (position.y() > surfaceY[x][z]) {
                        water[x][z] = true;
                    }
                }
            }
        }

        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int elevation = surfaceY[x][z];
                String block = surfaceBlock[x][z] == null ? "" : surfaceBlock[x][z];
                int rgb = water[x][z] ? waterColor(elevation) : biomeColor(biomeIds[x][z], block, elevation);
                int west = surfaceY[Math.max(0, x - 1)][z];
                int east = surfaceY[Math.min(size - 1, x + 1)][z];
                int north = surfaceY[x][Math.max(0, z - 1)];
                int south = surfaceY[x][Math.min(size - 1, z + 1)];
                double light = 1.0 + (west - east + north - south) * 0.018;
                image.setRGB(x, z, shade(rgb, Math.max(0.68, Math.min(1.28, light))));
            }
        }

        Path absolute = output.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!ImageIO.write(image, "png", absolute.toFile())) {
            throw new IOException("No PNG image writer is available");
        }
        return absolute;
    }

    private static int biomeColor(int biomeId, String block, int elevation) {
        return switch (biomeId) {
            case BiomeRegistry.MISTWOOD_ID -> block.contains("leaves") ? 0x286B38 : 0x56864B;
            case BiomeRegistry.GLASSGRASS_STEPPE_ID -> 0xA5A65A;
            case BiomeRegistry.EMBERFEN_ID -> 0x526D43;
            case BiomeRegistry.FROSTVEIL_ID -> 0xC6D7D4;
            case BiomeRegistry.SKYGLASS_HIGHLANDS_ID -> elevation > 100 ? 0x858C91 : 0x777C80;
            case BiomeRegistry.SALTWIND_BASIN_ID -> 0xD8C27A;
            case BiomeRegistry.BLOOMVEIL_GROVE_ID -> 0x5E9B79;
            case BiomeRegistry.ASHEN_WASTES_ID -> 0x625B58;
            case BiomeRegistry.MOONFROST_TUNDRA_ID -> 0xB7D9E8;
            default -> terrainColor(block, elevation);
        };
    }

    private static int terrainColor(String block, int elevation) {
        if (block.contains("leaves")) return 0x397B35;
        if (block.contains("grass")) return elevation > 105 ? 0x82936C : 0x6E9B4C;
        if (block.contains("sand")) return 0xD8C27A;
        if (block.contains("snow") || block.contains("ice")) return 0xE4EDF0;
        if (block.contains("stone") || block.contains("deepslate")) return 0x777C80;
        if (block.contains("dirt") || block.contains("podzol")) return 0x876447;
        if (block.contains("log")) return 0x80603E;
        return 0x8B806A;
    }

    private static int waterColor(int elevation) {
        return elevation > OverworldChunkGenerator.DEFAULT_SEA_LEVEL + 8 ? 0x438FAF : 0x347EA8;
    }

    private static int shade(int rgb, double factor) {
        int red = Math.min(255, (int) (((rgb >>> 16) & 0xff) * factor));
        int green = Math.min(255, (int) (((rgb >>> 8) & 0xff) * factor));
        int blue = Math.min(255, (int) ((rgb & 0xff) * factor));
        return (red << 16) | (green << 8) | blue;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: WorldPreviewRenderer <seed> <radius-chunks 0..16> <output.png>");
            System.exit(2);
        }
        long seed = Long.parseLong(args[0]);
        int radius = Integer.parseInt(args[1]);
        Path output = render(seed, radius, Path.of(args[2]));
        System.out.println("Generated NEXORA world preview: " + output);
        System.out.println("Seed: " + seed + " | chunks: " + ((radius * 2 + 1) * (radius * 2 + 1)));
    }
}
