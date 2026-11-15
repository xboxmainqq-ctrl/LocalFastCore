package ru.nexora.client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Durable local storage for Android single-player worlds.
 *
 * <p>Each chunk is stored independently so later world streaming can load and
 * save regions without rewriting the whole world. Writes use a temporary
 * file followed by an atomic-style rename to avoid leaving half-written saves.</p>
 */
final class LocalWorldStore {
    private static final int CHUNK_MAGIC = 0x4E585743; // NXWC
    private static final int PLAYER_MAGIC = 0x4E585750; // NXWP
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_BLOCKS_PER_CHUNK = 131_072;
    private static final long MAX_CHUNK_FILE_BYTES = 16L * 1024L * 1024L;

    private final File rootDirectory;

    LocalWorldStore(File rootDirectory) {
        if (rootDirectory == null) throw new IllegalArgumentException("rootDirectory is required");
        this.rootDirectory = rootDirectory;
    }

    void saveChunk(String worldId, NexoraAndroidProtocolCodec.ChunkData chunk) throws IOException {
        if (chunk == null) throw new IllegalArgumentException("chunk is required");
        if (chunk.blocks().size() > MAX_BLOCKS_PER_CHUNK) {
            throw new IOException("Chunk contains too many blocks");
        }
        File directory = worldDirectory(worldId);
        ensureDirectory(directory);
        File destination = new File(directory, chunkFileName(chunk.chunkX(), chunk.chunkZ()));
        File temporary = new File(directory, destination.getName() + ".tmp");
        try (DataOutputStream output = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(temporary)))) {
            output.writeInt(CHUNK_MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.writeInt(chunk.chunkX());
            output.writeInt(chunk.chunkZ());
            output.writeInt(chunk.blocks().size());
            for (NexoraAndroidProtocolCodec.ChunkBlock block : chunk.blocks()) {
                if (block.localX() < 0 || block.localX() > 15
                        || block.localZ() < 0 || block.localZ() > 15
                        || block.y() < -4096 || block.y() > 4096
                        || block.blockId() == null || block.blockId().isEmpty()
                        || block.blockId().length() > 256) {
                    throw new IOException("Chunk contains an invalid block entry");
                }
                output.writeByte(block.localX());
                output.writeInt(block.y());
                output.writeByte(block.localZ());
                output.writeUTF(block.blockId());
            }
            output.flush();
        } catch (IOException failure) {
            temporary.delete();
            throw failure;
        }
        replaceFile(temporary, destination);
    }

    List<NexoraAndroidProtocolCodec.ChunkData> loadChunks(String worldId) throws IOException {
        File directory = worldDirectory(worldId);
        if (!directory.exists()) return List.of();
        File[] files = directory.listFiles((parent, name) ->
                name.startsWith("chunk_") && name.endsWith(".nxwc"));
        if (files == null) throw new IOException("Could not list saved world chunks");
        List<NexoraAndroidProtocolCodec.ChunkData> chunks = new ArrayList<>(files.length);
        for (File file : files) {
            if (file.length() < 20 || file.length() > MAX_CHUNK_FILE_BYTES) {
                throw new IOException("Saved chunk has an invalid file size: " + file.getName());
            }
            try (DataInputStream input = new DataInputStream(
                    new BufferedInputStream(new FileInputStream(file)))) {
                if (input.readInt() != CHUNK_MAGIC || input.readInt() != FORMAT_VERSION) {
                    throw new IOException("Unsupported saved chunk format: " + file.getName());
                }
                int chunkX = input.readInt();
                int chunkZ = input.readInt();
                int count = input.readInt();
                if (count < 0 || count > MAX_BLOCKS_PER_CHUNK) {
                    throw new IOException("Saved chunk block count is invalid");
                }
                List<NexoraAndroidProtocolCodec.ChunkBlock> blocks = new ArrayList<>(count);
                for (int index = 0; index < count; index++) {
                    int localX = input.readUnsignedByte();
                    int y = input.readInt();
                    int localZ = input.readUnsignedByte();
                    String blockId = input.readUTF();
                    if (localX > 15 || localZ > 15 || y < -4096 || y > 4096
                            || blockId.isEmpty() || blockId.length() > 256) {
                        throw new IOException("Saved chunk contains an invalid block entry");
                    }
                    blocks.add(new NexoraAndroidProtocolCodec.ChunkBlock(
                            localX, y, localZ, blockId));
                }
                if (input.read() != -1) throw new IOException("Saved chunk has trailing data");
                chunks.add(new NexoraAndroidProtocolCodec.ChunkData(
                        chunkX, chunkZ, List.copyOf(blocks)));
            }
        }
        chunks.sort(Comparator.comparingInt(NexoraAndroidProtocolCodec.ChunkData::chunkX)
                .thenComparingInt(NexoraAndroidProtocolCodec.ChunkData::chunkZ));
        return List.copyOf(chunks);
    }

    private static final int INVENTORY_MAGIC = 0x4E585749; // NXWI
    private static final int HOTBAR_SLOTS = 9;

    void saveInventory(String worldId, List<InventorySlot> slots) throws IOException {
        if (slots == null || slots.size() != HOTBAR_SLOTS) {
            throw new IOException("Local hotbar must contain exactly nine slots");
        }
        File directory = worldDirectory(worldId);
        ensureDirectory(directory);
        File destination = new File(directory, "inventory.nxwi");
        File temporary = new File(directory, "inventory.nxwi.tmp");
        try (DataOutputStream output = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(temporary)))) {
            output.writeInt(INVENTORY_MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.writeInt(HOTBAR_SLOTS);
            for (InventorySlot slot : slots) {
                String itemId = slot == null ? "" : slot.itemId();
                int count = slot == null ? 0 : slot.count();
                if (count < 0 || count > 999 || (count == 0) != itemId.isEmpty()
                        || itemId.length() > 256
                        || (!itemId.isEmpty() && !itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))) {
                    throw new IOException("Local inventory contains an invalid slot");
                }
                output.writeUTF(itemId);
                output.writeInt(count);
            }
            output.flush();
        } catch (IOException failure) {
            temporary.delete();
            throw failure;
        }
        replaceFile(temporary, destination);
    }

    List<InventorySlot> loadInventory(String worldId) throws IOException {
        File file = new File(worldDirectory(worldId), "inventory.nxwi");
        if (!file.exists()) return null;
        if (file.length() < 12 || file.length() > 8192) {
            throw new IOException("Saved local inventory has an invalid size");
        }
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            if (input.readInt() != INVENTORY_MAGIC || input.readInt() != FORMAT_VERSION
                    || input.readInt() != HOTBAR_SLOTS) {
                throw new IOException("Unsupported local inventory format");
            }
            List<InventorySlot> slots = new ArrayList<>(HOTBAR_SLOTS);
            for (int index = 0; index < HOTBAR_SLOTS; index++) {
                String itemId = input.readUTF();
                int count = input.readInt();
                if (count < 0 || count > 999 || (count == 0) != itemId.isEmpty()
                        || itemId.length() > 256
                        || (!itemId.isEmpty() && !itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))) {
                    throw new IOException("Saved local inventory contains an invalid slot");
                }
                slots.add(new InventorySlot(itemId, count));
            }
            if (input.read() != -1) throw new IOException("Saved local inventory has trailing data");
            return List.copyOf(slots);
        }
    }

    record InventorySlot(String itemId, int count) { }

    void savePlayerPosition(String worldId, PlayerPosition position) throws IOException {
        if (position == null || !Double.isFinite(position.x())
                || !Double.isFinite(position.y()) || !Double.isFinite(position.z())
                || Math.abs(position.x()) > 30_000_000
                || Math.abs(position.z()) > 30_000_000
                || position.y() < -4096 || position.y() > 4096) {
            throw new IOException("Player position is outside the supported world bounds");
        }
        File directory = worldDirectory(worldId);
        ensureDirectory(directory);
        File destination = new File(directory, "player.nxwp");
        File temporary = new File(directory, "player.nxwp.tmp");
        try (DataOutputStream output = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(temporary)))) {
            output.writeInt(PLAYER_MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.writeDouble(position.x());
            output.writeDouble(position.y());
            output.writeDouble(position.z());
            output.flush();
        } catch (IOException failure) {
            temporary.delete();
            throw failure;
        }
        replaceFile(temporary, destination);
    }

    PlayerPosition loadPlayerPosition(String worldId) throws IOException {
        File file = new File(worldDirectory(worldId), "player.nxwp");
        if (!file.exists()) return null;
        if (file.length() != 32) throw new IOException("Saved player position has an invalid size");
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            if (input.readInt() != PLAYER_MAGIC || input.readInt() != FORMAT_VERSION) {
                throw new IOException("Unsupported saved player position format");
            }
            PlayerPosition position = new PlayerPosition(
                    input.readDouble(), input.readDouble(), input.readDouble());
            if (input.read() != -1 || !Double.isFinite(position.x())
                    || !Double.isFinite(position.y()) || !Double.isFinite(position.z())
                    || Math.abs(position.x()) > 30_000_000
                    || Math.abs(position.z()) > 30_000_000
                    || position.y() < -4096 || position.y() > 4096) {
                throw new IOException("Saved player position is invalid");
            }
            return position;
        }
    }

    void deleteWorld(String worldId) throws IOException {
        File directory = worldDirectory(worldId);
        if (!directory.exists()) return;
        deleteRecursively(directory);
    }

    private File worldDirectory(String worldId) {
        if (worldId == null || !worldId.matches("[A-Za-z0-9_-]{1,80}")) {
            throw new IllegalArgumentException("Invalid world id");
        }
        return new File(rootDirectory, worldId);
    }

    private static String chunkFileName(int x, int z) {
        return "chunk_" + x + "_" + z + ".nxwc";
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create world save directory");
        }
        if (!directory.isDirectory()) throw new IOException("World save path is not a directory");
    }

    private static void replaceFile(File temporary, File destination) throws IOException {
        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IOException("Could not replace previous world save");
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("Could not finalize world save");
        }
    }

    private static void deleteRecursively(File file) throws IOException {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        if (!file.delete()) throw new IOException("Could not delete world save data");
    }

    record PlayerPosition(double x, double y, double z) { }
}
