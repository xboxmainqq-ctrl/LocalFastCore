package ru.nexora.runtime;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.CRC32C;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;

/**
 * Atomic file-based persistence for the current protocol-independent world model.
 *
 * <p>The .nxw format is an internal NEXORA development format. It is not the
 * Minecraft Bedrock LevelDB world format and is not intended to be imported by
 * a Bedrock client.</p>
 */
public final class FileWorldStorage {
    private static final int MAGIC = 0x4E585752; // NXWR
    private static final short FORMAT_VERSION = 1;
    private static final int MAX_FILE_BYTES = 128 * 1024 * 1024;
    private static final int MAX_CHUNKS = 100_000;
    private static final int MAX_BLOCKS_PER_CHUNK = 65_536;
    private static final int MAX_PROPERTIES = 256;
    private static final Pattern WORLD_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final Path directory;

    public FileWorldStorage(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory")
                .toAbsolutePath().normalize();
    }

    /** Saves all currently loaded chunks using a temporary file and atomic replacement. */
    public void save(World world) throws IOException {
        Objects.requireNonNull(world, "world");
        validateWorldId(world.id());
        ensureDirectory();

        Path target = worldFile(world.id());
        if (Files.isSymbolicLink(target)) {
            throw new IOException("Refusing to replace a symbolic-link world file: " + target);
        }

        Path temporary = Files.createTempFile(directory, "." + world.id() + "-", ".tmp");
        boolean moved = false;
        try {
            CRC32C checksum = new CRC32C();
            try (OutputStream file = Files.newOutputStream(temporary, StandardOpenOption.WRITE);
                 CheckedOutputStream checked = new CheckedOutputStream(file, checksum);
                 DataOutputStream output = new DataOutputStream(checked)) {
                writePayload(output, world);
                output.flush();
            }

            try (DataOutputStream trailer = new DataOutputStream(Files.newOutputStream(
                    temporary, StandardOpenOption.WRITE, StandardOpenOption.APPEND))) {
                trailer.writeInt((int) checksum.getValue());
                trailer.flush();
            }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }

            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    /** Loads a saved world, or returns empty when no file exists for that ID. */
    public Optional<InMemoryWorld> load(String worldId) throws IOException {
        validateWorldId(worldId);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World storage path is not a safe directory: " + directory);
        }

        Path file = worldFile(worldId);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World file is not a regular file: " + file);
        }
        long size = Files.size(file);
        if (size < Integer.BYTES * 2L || size > MAX_FILE_BYTES) {
            throw new IOException("World file has an invalid size: " + size);
        }

        try (InputStream raw = Files.newInputStream(file);
             CheckedInputStream checked = new CheckedInputStream(raw, new CRC32C());
             DataInputStream input = new DataInputStream(checked)) {
            int magic = input.readInt();
            if (magic != MAGIC) {
                throw new IOException("Invalid NEXORA world file signature");
            }
            short version = input.readShort();
            if (version != FORMAT_VERSION) {
                throw new IOException("Unsupported NEXORA world format version: " + version);
            }
            String storedId = input.readUTF();
            if (!worldId.equals(storedId)) {
                throw new IOException("World ID in file does not match requested ID");
            }
            WorldHeight height = new WorldHeight(input.readInt(), input.readInt());
            int chunkCount = input.readInt();
            if (chunkCount < 0 || chunkCount > MAX_CHUNKS) {
                throw new IOException("Invalid chunk count: " + chunkCount);
            }

            InMemoryWorld world = new InMemoryWorld(storedId, height);
            Set<ChunkPosition> seenChunks = new HashSet<>();
            for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
                ChunkPosition chunk = new ChunkPosition(input.readInt(), input.readInt());
                if (!seenChunks.add(chunk)) {
                    throw new IOException("Duplicate chunk in world file: " + chunk);
                }
                int blockCount = input.readInt();
                if (blockCount < 0 || blockCount > MAX_BLOCKS_PER_CHUNK) {
                    throw new IOException("Invalid block count in chunk " + chunk + ": " + blockCount);
                }
                for (int blockIndex = 0; blockIndex < blockCount; blockIndex++) {
                    int localX = input.readUnsignedByte();
                    int y = input.readInt();
                    int localZ = input.readUnsignedByte();
                    if (localX > 15 || localZ > 15 || !height.contains(y)) {
                        throw new IOException("Invalid block coordinates in chunk " + chunk);
                    }
                    String identifier = input.readUTF();
                    int propertyCount = input.readUnsignedShort();
                    if (propertyCount > MAX_PROPERTIES) {
                        throw new IOException("Too many block properties: " + propertyCount);
                    }
                    Map<String, String> properties = new java.util.HashMap<>();
                    for (int property = 0; property < propertyCount; property++) {
                        String key = input.readUTF();
                        String value = input.readUTF();
                        if (properties.putIfAbsent(key, value) != null) {
                            throw new IOException("Duplicate block property: " + key);
                        }
                    }
                    int blockX = Math.addExact(Math.multiplyExact(chunk.x(), 16), localX);
                    int blockZ = Math.addExact(Math.multiplyExact(chunk.z(), 16), localZ);
                    BlockState state;
                    try {
                        state = new BlockState(identifier, properties);
                        world.setBlock(new BlockPosition(blockX, y, blockZ), state);
                    } catch (IllegalArgumentException | ArithmeticException exception) {
                        throw new IOException("Invalid block data in world file", exception);
                    }
                }
            }

            long actualChecksum = checked.getChecksum().getValue();
            int expectedChecksum = raw.read() << 24;
            expectedChecksum |= raw.read() << 16;
            expectedChecksum |= raw.read() << 8;
            expectedChecksum |= raw.read();
            if (raw.read() != -1) {
                throw new IOException("Unexpected trailing bytes in world file");
            }
            if (expectedChecksum != (int) actualChecksum) {
                throw new IOException("World file checksum mismatch");
            }
            return Optional.of(world);
        } catch (ArithmeticException exception) {
            throw new IOException("World file contains coordinates outside supported range", exception);
        }
    }

    private void writePayload(DataOutputStream output, World world) throws IOException {
        output.writeInt(MAGIC);
        output.writeShort(FORMAT_VERSION);
        output.writeUTF(world.id());
        output.writeInt(world.height().minY());
        output.writeInt(world.height().maxYExclusive());

        var chunks = world.loadedChunks();
        if (chunks.size() > MAX_CHUNKS) {
            throw new IOException("World contains too many loaded chunks: " + chunks.size());
        }
        output.writeInt(chunks.size());
        for (ChunkPosition chunk : chunks) {
            Map<BlockPosition, BlockState> blocks = world.blocksInChunk(chunk);
            if (blocks.size() > MAX_BLOCKS_PER_CHUNK) {
                throw new IOException("Chunk contains too many blocks: " + chunk);
            }
            output.writeInt(chunk.x());
            output.writeInt(chunk.z());
            output.writeInt(blocks.size());
            for (Map.Entry<BlockPosition, BlockState> entry : blocks.entrySet()) {
                BlockPosition position = entry.getKey();
                BlockState state = entry.getValue();
                if (!position.chunkPosition().equals(chunk) || state.isAir()
                        || !world.height().contains(position.y())) {
                    throw new IOException("World returned an invalid block snapshot");
                }
                output.writeByte(position.localX());
                output.writeInt(position.y());
                output.writeByte(position.localZ());
                output.writeUTF(state.identifier());
                if (state.properties().size() > MAX_PROPERTIES) {
                    throw new IOException("Block has too many properties at " + position);
                }
                output.writeShort(state.properties().size());
                for (Map.Entry<String, String> property : state.properties().entrySet()) {
                    output.writeUTF(property.getKey());
                    output.writeUTF(property.getValue());
                }
            }
        }
    }

    private void ensureDirectory() throws IOException {
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World storage path is not a safe directory: " + directory);
        }
    }

    private Path worldFile(String worldId) {
        return directory.resolve(worldId + ".nxw");
    }

    private static void validateWorldId(String worldId) {
        Objects.requireNonNull(worldId, "worldId");
        if (!WORLD_ID.matcher(worldId).matches()) {
            throw new IllegalArgumentException(
                    "World ID may contain only letters, digits, '_' and '-' (1-64 characters)"
            );
        }
    }
}
