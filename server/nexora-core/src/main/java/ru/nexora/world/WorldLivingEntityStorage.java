package ru.nexora.world;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32C;

/** Versioned, checksummed atomic persistence for living world actors. */
public final class WorldLivingEntityStorage {
    private static final int MAGIC = 0x4e58454c; // NXEL
    private static final int VERSION = 1;
    private static final int MAX_ENTITIES = 5_000;
    private static final int MAX_IDENTIFIER_BYTES = 128;
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private final Path file;

    public WorldLivingEntityStorage(Path rootDirectory, String worldName) throws IOException {
        Objects.requireNonNull(rootDirectory, "rootDirectory");
        if (worldName == null || !worldName.matches("[A-Za-z0-9 _-]{1,64}")) {
            throw new IllegalArgumentException("Invalid world name");
        }
        Path root = rootDirectory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        rejectSymlink(root);
        Path worldDirectory = root.resolve(worldName).normalize();
        if (!worldDirectory.startsWith(root)) throw new IOException("Living actor path escapes root");
        Files.createDirectories(worldDirectory);
        rejectSymlink(worldDirectory);
        Path entitiesDirectory = worldDirectory.resolve("entities");
        Files.createDirectories(entitiesDirectory);
        rejectSymlink(entitiesDirectory);
        file = entitiesDirectory.resolve("living.nxe");
        rejectSymlink(file);
    }

    public synchronized void save(List<WorldLivingEntity> entities) throws IOException {
        Objects.requireNonNull(entities, "entities");
        if (entities.size() > MAX_ENTITIES) throw new IOException("Too many living world entities");
        ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream(Math.min(4096, entities.size() * 128));
        Set<Long> runtimeIds = new HashSet<>();
        try (DataOutputStream out = new DataOutputStream(bodyBytes)) {
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            out.writeInt(entities.size());
            for (WorldLivingEntity entity : entities) {
                Objects.requireNonNull(entity, "entity");
                if (!runtimeIds.add(entity.runtimeId())) throw new IOException("Duplicate living actor runtime ID");
                writeEntity(out, entity);
                if (bodyBytes.size() > MAX_FILE_BYTES - Integer.BYTES) {
                    throw new IOException("Living world entity file exceeds size limit");
                }
            }
        }
        byte[] body = bodyBytes.toByteArray();
        CRC32C crc = new CRC32C();
        crc.update(body, 0, body.length);
        ByteArrayOutputStream complete = new ByteArrayOutputStream(body.length + Integer.BYTES);
        complete.writeBytes(body);
        try (DataOutputStream out = new DataOutputStream(complete)) {
            out.writeInt((int) crc.getValue());
        }
        if (complete.size() > MAX_FILE_BYTES) throw new IOException("Living world entity file exceeds size limit");

        rejectSymlink(file);
        Path temporary = Files.createTempFile(file.getParent(), ".nexora-living-", ".tmp");
        try {
            Files.write(temporary, complete.toByteArray());
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public synchronized List<WorldLivingEntity> load() throws IOException {
        rejectSymlink(file);
        if (!Files.exists(file)) return List.of();
        long size = Files.size(file);
        if (size < 13 || size > MAX_FILE_BYTES) throw new IOException("Invalid living world entity file size");
        byte[] bytes = Files.readAllBytes(file);
        int bodyLength = bytes.length - Integer.BYTES;
        int storedChecksum = ByteBuffer.wrap(bytes, bodyLength, Integer.BYTES).getInt();
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bodyLength);
        if ((int) crc.getValue() != storedChecksum) throw new IOException("Living world entity checksum mismatch");

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, bodyLength))) {
            if (in.readInt() != MAGIC) throw new IOException("Invalid living world entity magic");
            int version = in.readUnsignedByte();
            if (version != VERSION) throw new IOException("Unsupported living world entity version: " + version);
            int count = in.readInt();
            if (count < 0 || count > MAX_ENTITIES) throw new IOException("Invalid living world entity count");
            List<WorldLivingEntity> result = new ArrayList<>(count);
            Set<Long> runtimeIds = new HashSet<>();
            for (int i = 0; i < count; i++) {
                WorldLivingEntity entity = readEntity(in);
                if (!runtimeIds.add(entity.runtimeId())) throw new IOException("Duplicate living actor runtime ID");
                result.add(entity);
            }
            if (in.available() != 0) throw new IOException("Trailing living world entity data");
            return List.copyOf(result);
        } catch (EOFException | IllegalArgumentException exception) {
            throw new IOException("Malformed living world entity file", exception);
        }
    }

    private static void writeEntity(DataOutputStream out, WorldLivingEntity entity) throws IOException {
        out.writeLong(entity.uniqueId());
        out.writeLong(entity.runtimeId());
        byte[] identifier = entity.identifier().getBytes(StandardCharsets.UTF_8);
        if (identifier.length == 0 || identifier.length > MAX_IDENTIFIER_BYTES) {
            throw new IOException("Invalid living actor identifier length");
        }
        out.writeByte(identifier.length);
        out.write(identifier);
        out.writeByte(entity.family().ordinal());
        out.writeDouble(entity.x());
        out.writeDouble(entity.y());
        out.writeDouble(entity.z());
        out.writeDouble(entity.velocityX());
        out.writeDouble(entity.velocityY());
        out.writeDouble(entity.velocityZ());
        out.writeFloat(entity.health());
        out.writeFloat(entity.maxHealth());
        out.writeLong(entity.fireUntilMillis());
        out.writeLong(entity.nextFireDamageMillis());
    }

    private static WorldLivingEntity readEntity(DataInputStream in) throws IOException {
        long uniqueId = in.readLong();
        long runtimeId = in.readLong();
        int identifierLength = in.readUnsignedByte();
        if (identifierLength == 0 || identifierLength > MAX_IDENTIFIER_BYTES) {
            throw new IOException("Invalid living actor identifier length");
        }
        byte[] identifierBytes = new byte[identifierLength];
        in.readFully(identifierBytes);
        String identifier = decodeUtf8(identifierBytes);
        int familyId = in.readUnsignedByte();
        WorldLivingEntity.Family[] families = WorldLivingEntity.Family.values();
        if (familyId >= families.length) throw new IOException("Invalid living actor family");
        double x = in.readDouble();
        double y = in.readDouble();
        double z = in.readDouble();
        double velocityX = in.readDouble();
        double velocityY = in.readDouble();
        double velocityZ = in.readDouble();
        float health = in.readFloat();
        float maxHealth = in.readFloat();
        long fireUntilMillis = in.readLong();
        long nextFireDamageMillis = in.readLong();
        return new WorldLivingEntity(uniqueId, runtimeId, identifier, families[familyId],
                x, y, z, velocityX, velocityY, velocityZ, health, maxHealth,
                fireUntilMillis, nextFireDamageMillis);
    }

    private static String decodeUtf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("Invalid UTF-8 in living actor data", exception);
        }
    }

    private static void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Symbolic links are not allowed in living actor storage");
    }
}
