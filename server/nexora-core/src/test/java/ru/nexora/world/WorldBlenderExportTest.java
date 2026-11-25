package ru.nexora.world;

import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Produces a real generated NEXORA region and a PlayerPhysics trace for Blender.
 * The OBJ is deliberately built from WorldChunk block data, not a second terrain model.
 */
class WorldBlenderExportTest {
    private static final long SEED = 0x4e45584f5241L;
    private static final int REGION_MIN_CHUNK = 0;
    private static final int REGION_MAX_CHUNK = 7;

    @Test
    void exportsGeneratedWorldAndPlayerPhysicsTraceForBlender() throws IOException {
        Path output = Path.of("target", "blender-preview");
        Files.createDirectories(output);
        World world = new World("NEXORA-BlenderPreview", SEED, new NexoraTerrainGenerator());

        // A one-chunk border ensures exposed-face checks see the real neighboring terrain.
        for (int chunkX = REGION_MIN_CHUNK - 1; chunkX <= REGION_MAX_CHUNK + 1; chunkX++) {
            for (int chunkZ = REGION_MIN_CHUNK - 1; chunkZ <= REGION_MAX_CHUNK + 1; chunkZ++) {
                world.getChunk(chunkX, chunkZ);
            }
        }

        Path materialFile = output.resolve("nexora-world.mtl");
        Path spawnObjectFile = output.resolve("nexora-spawn-region.obj");
        Path mountainObjectFile = output.resolve("nexora-mountain-region.obj");
        writeMaterials(materialFile);
        long spawnFaces = writeWorldObject(world, spawnObjectFile,
                materialFile.getFileName().toString(),
                REGION_MIN_CHUNK, REGION_MAX_CHUNK, REGION_MIN_CHUNK, REGION_MAX_CHUNK);

        int[] mountainCenter = highestTerrainSample();
        int centerChunkX = Math.floorDiv(mountainCenter[0], 16);
        int centerChunkZ = Math.floorDiv(mountainCenter[1], 16);
        int mountainMinX = centerChunkX - 4;
        int mountainMaxX = centerChunkX + 3;
        int mountainMinZ = centerChunkZ - 4;
        int mountainMaxZ = centerChunkZ + 3;
        for (int chunkX = mountainMinX - 1; chunkX <= mountainMaxX + 1; chunkX++) {
            for (int chunkZ = mountainMinZ - 1; chunkZ <= mountainMaxZ + 1; chunkZ++) {
                world.getChunk(chunkX, chunkZ);
            }
        }
        long mountainFaces = writeWorldObject(world, mountainObjectFile,
                materialFile.getFileName().toString(),
                mountainMinX, mountainMaxX, mountainMinZ, mountainMaxZ);
        Files.writeString(output.resolve("mountain-center.txt"),
                mountainCenter[0] + "," + mountainCenter[1], StandardCharsets.UTF_8);

        Path traceFile = output.resolve("player-physics.csv");
        int tracePoints = writePhysicsTrace(world, traceFile);

        assertTrue(spawnFaces > 10_000, "spawn region should contain substantial generated block geometry");
        assertTrue(mountainFaces > 10_000, "mountain region should contain substantial generated block geometry");
        assertTrue(tracePoints > 20, "physics trace should contain a useful movement path");
        assertTrue(Files.size(spawnObjectFile) > 100_000, "spawn OBJ should contain real generated terrain");
        assertTrue(Files.size(mountainObjectFile) > 100_000, "mountain OBJ should contain real generated terrain");
        assertTrue(Files.size(traceFile) > 500, "physics CSV should contain player coordinates");
    }

    private static int[] highestTerrainSample() {
        int bestX = 0;
        int bestZ = 0;
        int maximum = Integer.MIN_VALUE;
        for (int x = -2048; x <= 2048; x += 16) {
            for (int z = -2048; z <= 2048; z += 16) {
                int height = NexoraTerrainGenerator.surfaceHeight(x, z, SEED);
                if (height > maximum) {
                    maximum = height;
                    bestX = x;
                    bestZ = z;
                }
            }
        }
        return new int[]{bestX, bestZ};
    }

    private static long writeWorldObject(
            World world, Path file, String materialName,
            int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ
    ) throws IOException {
        long vertex = 1;
        long faceCount = 0;
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("# NEXORA generated chunk export; seed=" + SEED + "\n");
            out.write("mtllib " + materialName + "\n");
            out.write("o NEXORA_Generated_World\n");
            String activeMaterial = "";
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    int originX = chunkX * 16;
                    int originZ = chunkZ * 16;
                    for (int localX = 0; localX < 16; localX++) {
                        for (int localZ = 0; localZ < 16; localZ++) {
                            int worldX = originX + localX;
                            int worldZ = originZ + localZ;
                            int ceiling = Math.min(WorldChunk.MAX_Y,
                                    NexoraTerrainGenerator.surfaceHeight(worldX, worldZ, SEED) + 32);
                            for (int y = WorldChunk.MIN_Y; y <= ceiling; y++) {
                                BlockType block = world.getChunk(chunkX, chunkZ)
                                        .getBlock(localX, y, localZ);
                                if (block == BlockType.AIR) continue;
                                if (!activeMaterial.equals(block.name())) {
                                    activeMaterial = block.name();
                                    out.write("usemtl " + activeMaterial + "\n");
                                }
                                if (y > WorldChunk.MIN_Y && world.getBlock(worldX, y - 1, worldZ) == BlockType.AIR) {
                                    vertex = writeFace(out, vertex, worldX, y, worldZ, 0);
                                    faceCount++;
                                }
                                if (y < WorldChunk.MAX_Y
                                        && world.getBlock(worldX, y + 1, worldZ) == BlockType.AIR) {
                                    vertex = writeFace(out, vertex, worldX, y, worldZ, 1);
                                    faceCount++;
                                }
                                if (world.getBlock(worldX - 1, y, worldZ) == BlockType.AIR) {
                                    vertex = writeFace(out, vertex, worldX, y, worldZ, 2);
                                    faceCount++;
                                }
                                if (world.getBlock(worldX + 1, y, worldZ) == BlockType.AIR) {
                                    vertex = writeFace(out, vertex, worldX, y, worldZ, 3);
                                    faceCount++;
                                }
                                if (world.getBlock(worldX, y, worldZ - 1) == BlockType.AIR) {
                                    vertex = writeFace(out, vertex, worldX, y, worldZ, 4);
                                    faceCount++;
                                }
                                if (world.getBlock(worldX, y, worldZ + 1) == BlockType.AIR) {
                                    vertex = writeFace(out, vertex, worldX, y, worldZ, 5);
                                    faceCount++;
                                }
                            }
                        }
                    }
                }
            }
        }
        return faceCount;
    }

    /** Writes four independent vertices and one outward-facing quad. */
    private static long writeFace(BufferedWriter out, long first, int x, int y, int z, int side)
            throws IOException {
        double[][] p = switch (side) {
            case 0 -> new double[][]{{x,y,z},{x,y,z+1},{x+1,y,z+1},{x+1,y,z}};
            case 1 -> new double[][]{{x,y+1,z},{x+1,y+1,z},{x+1,y+1,z+1},{x,y+1,z+1}};
            case 2 -> new double[][]{{x,y,z},{x,y+1,z},{x,y+1,z+1},{x,y,z+1}};
            case 3 -> new double[][]{{x+1,y,z},{x+1,y,z+1},{x+1,y+1,z+1},{x+1,y+1,z}};
            case 4 -> new double[][]{{x,y,z},{x+1,y,z},{x+1,y+1,z},{x,y+1,z}};
            case 5 -> new double[][]{{x,y,z+1},{x,y+1,z+1},{x+1,y+1,z+1},{x+1,y,z+1}};
            default -> throw new IllegalArgumentException("Unknown cube face");
        };
        // Swapping Y/Z to Blender's Z-up coordinates reverses handedness,
        // so reverse the quad order to keep its normal pointing outwards.
        for (int i = 0; i < 4; i++) {
            double[] point = p[3 - i];
            out.write(String.format(Locale.ROOT, "v %.3f %.3f %.3f%n", point[0], point[2], point[1]));
        }
        out.write("f " + first + " " + (first + 1) + " " + (first + 2) + " " + (first + 3) + "\n");
        return first + 4;
    }

    private static void writeMaterials(Path file) throws IOException {
        Map<BlockType, String> colors = new EnumMap<>(BlockType.class);
        colors.put(BlockType.STONE, "0.43 0.45 0.48");
        colors.put(BlockType.DIRT, "0.38 0.23 0.12");
        colors.put(BlockType.GRASS_BLOCK, "0.28 0.52 0.19");
        colors.put(BlockType.BEDROCK, "0.12 0.12 0.14");
        colors.put(BlockType.COBBLESTONE, "0.36 0.37 0.39");
        colors.put(BlockType.GRAVEL, "0.48 0.46 0.42");
        colors.put(BlockType.GLASS, "0.55 0.78 0.86");
        colors.put(BlockType.OBSIDIAN, "0.19 0.12 0.27");
        colors.put(BlockType.OAK_LOG, "0.39 0.25 0.12");
        colors.put(BlockType.OAK_PLANKS, "0.58 0.39 0.20");
        colors.put(BlockType.OAK_LEAVES, "0.16 0.39 0.13");
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (Map.Entry<BlockType, String> entry : colors.entrySet()) {
                out.write("newmtl " + entry.getKey().name() + "\n");
                out.write("Ka " + entry.getValue() + "\n");
                out.write("Kd " + entry.getValue() + "\n");
                out.write("Ks 0.04 0.04 0.04\n");
                out.write("d 1.0\n");
                out.write("illum 2\n\n");
            }
        }
    }

    private static int writePhysicsTrace(World world, Path file) throws IOException {
        PlayerPhysics.State state = PlayerPhysics.spawn(world, 8.5, 65.0, 8.5);
        int points = 0;
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("tick,x,y,z,on_ground\n");
            for (int tick = 0; tick <= 100; tick++) {
                out.write(String.format(Locale.ROOT, "%d,%.5f,%.5f,%.5f,%s%n",
                        tick, state.x(), state.y(), state.z(), state.onGround()));
                points++;
                if (tick == 100) break;
                boolean jump = tick == 8;
                state = PlayerPhysics.tick(world, state,
                        new PlayerPhysics.Input(0, 1, -90, false, jump), 0.05);
            }
        }
        return points;
    }
}
