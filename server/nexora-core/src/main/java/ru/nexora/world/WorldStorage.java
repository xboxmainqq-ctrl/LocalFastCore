package ru.nexora.world;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.CRC32C;

/**
 * Versioned, checksummed chunk persistence for the initial NEXORA world format.
 * Writes use a temporary file and atomic replacement where supported.
 */
public final class WorldStorage {
    private static final int MAGIC = 0x4e584348; // NXCH
    private static final int FORMAT_VERSION = 1;
    private static final int HEADER_BYTES = 4 + 1 + 4 + 4 + 1;
    private static final int MAX_FILE_BYTES =
            HEADER_BYTES + WorldChunk.SECTION_COUNT * (1 + ChunkSection.VOLUME) + Integer.BYTES;
    private static final Pattern SAFE_WORLD_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final Path chunksDirectory;

    public WorldStorage(Path rootDirectory, String worldName) throws IOException {
        Objects.requireNonNull(rootDirectory, "rootDirectory");
        if (worldName == null || !SAFE_WORLD_NAME.matcher(worldName).matches()) {
            throw new IllegalArgumentException("World name must contain only letters, digits, '_' or '-'");
        }
        Path root = rootDirectory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        rejectSymlink(root, "world storage root");

        Path worldDirectory = root.resolve(worldName).normalize();
        if (!worldDirectory.startsWith(root)) throw new IOException("World path escapes storage root");
        Files.createDirectories(worldDirectory);
        rejectSymlink(worldDirectory, "world directory");

        this.chunksDirectory = worldDirectory.resolve("chunks");
        Files.createDirectories(chunksDirectory);
        rejectSymlink(chunksDirectory, "chunks directory");
    }

    public void saveChunk(WorldChunk chunk) throws IOException {
        Objects.requireNonNull(chunk, "chunk");
        Path target = chunkPath(chunk.chunkX(), chunk.chunkZ());
        rejectSymlink(target, "chunk file");

        ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream(8192);
        try (DataOutputStream output = new DataOutputStream(bodyBytes)) {
            output.writeInt(MAGIC);
            output.writeByte(FORMAT_VERSION);
            output.writeInt(chunk.chunkX());
            output.writeInt(chunk.chunkZ());
            output.writeByte(WorldChunk.SECTION_COUNT);
            for (int sectionIndex = 0; sectionIndex < WorldChunk.SECTION_COUNT; sectionIndex++) {
                byte[] raw = chunk.copySectionRaw(sectionIndex);
                output.writeBoolean(raw != null);
                if (raw != null) output.write(raw);
            }
        }

        byte[] body = bodyBytes.toByteArray();
        CRC32C checksum = new CRC32C();
        checksum.update(body, 0, body.length);

        ByteArrayOutputStream fileBytes = new ByteArrayOutputStream(body.length + Integer.BYTES);
        fileBytes.writeBytes(body);
        try (DataOutputStream output = new DataOutputStream(fileBytes)) {
            output.writeInt((int) checksum.getValue());
        }

        Path temporary = Files.createTempFile(chunksDirectory, ".nexora-chunk-", ".tmp");
        try {
            Files.write(temporary, fileBytes.toByteArray());
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public Optional<WorldChunk> loadChunk(int chunkX, int chunkZ) throws IOException {
        Path file = chunkPath(chunkX, chunkZ);
        rejectSymlink(file, "chunk file");
        if (!Files.exists(file)) return Optional.empty();

        long fileSize = Files.size(file);
        if (fileSize < HEADER_BYTES + Integer.BYTES || fileSize > MAX_FILE_BYTES) {
            throw new IOException("Invalid NEXORA chunk file size: " + fileSize);
        }

        byte[] bytes = Files.readAllBytes(file);
        int bodyLength = bytes.length - Integer.BYTES;
        int storedChecksum = ((bytes[bodyLength] & 0xff) << 24)
                | ((bytes[bodyLength + 1] & 0xff) << 16)
                | ((bytes[bodyLength + 2] & 0xff) << 8)
                | (bytes[bodyLength + 3] & 0xff);
        CRC32C checksum = new CRC32C();
        checksum.update(bytes, 0, bodyLength);
        if ((int) checksum.getValue() != storedChecksum) {
            throw new IOException("NEXORA chunk checksum mismatch: " + file);
        }

        try (DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(bytes, 0, bodyLength))) {
            if (input.readInt() != MAGIC) throw new IOException("Invalid NEXORA chunk magic");
            int version = input.readUnsignedByte();
            if (version != FORMAT_VERSION) {
                throw new IOException("Unsupported NEXORA chunk format version: " + version);
            }
            int storedX = input.readInt();
            int storedZ = input.readInt();
            if (storedX != chunkX || storedZ != chunkZ) {
                throw new IOException("NEXORA chunk coordinates do not match its file name");
            }
            int sectionCount = input.readUnsignedByte();
            if (sectionCount != WorldChunk.SECTION_COUNT) {
                throw new IOException("Unexpected NEXORA chunk section count: " + sectionCount);
            }

            WorldChunk chunk = new WorldChunk(chunkX, chunkZ);
            for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
                if (input.readBoolean()) {
                    byte[] raw = new byte[ChunkSection.VOLUME];
                    input.readFully(raw);
                    try {
                        chunk.loadSectionRaw(sectionIndex, raw);
                    } catch (IllegalArgumentException exception) {
                        throw new IOException("Invalid block ID in NEXORA chunk section " + sectionIndex, exception);
                    }
                }
            }
            if (input.available() != 0) throw new IOException("Trailing data in NEXORA chunk file");
            return Optional.of(chunk);
        }
    }

    private Path chunkPath(int x, int z) {
        return chunksDirectory.resolve(x + "_" + z + ".nxc");
    }

    private static void rejectSymlink(Path path, String description) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("NEXORA " + description + " must not be a symbolic link: " + path);
        }
    }
}
