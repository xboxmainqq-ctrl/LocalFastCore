package ru.nexora.world;

import ru.nexora.inventory.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
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

/** Versioned, checksummed persistence for dropped world item actors. */
public final class WorldItemEntityStorage {
    private static final int MAGIC = 0x4e584549; // NXEI
    private static final int VERSION = 1;
    private static final int MAX_ENTITIES = 10_000;
    private static final int MAX_ITEM_ID_BYTES = 128;
    private static final int MAX_OWNER_BYTES = 64;
    private static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    private final Path file;

    public WorldItemEntityStorage(Path rootDirectory, String worldName) throws IOException {
        Objects.requireNonNull(rootDirectory, "rootDirectory");
        if (worldName == null || !worldName.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid world name");
        }
        Path root = rootDirectory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        rejectSymlink(root);
        Path worldDirectory = root.resolve(worldName).normalize();
        if (!worldDirectory.startsWith(root)) throw new IOException("World item path escapes root");
        Files.createDirectories(worldDirectory);
        rejectSymlink(worldDirectory);
        Path itemsDirectory = worldDirectory.resolve("items");
        Files.createDirectories(itemsDirectory);
        rejectSymlink(itemsDirectory);
        file = itemsDirectory.resolve("entities.nxe");
        rejectSymlink(file);
    }

    public synchronized void save(List<WorldItemEntity> entities) throws IOException {
        Objects.requireNonNull(entities, "entities");
        if (entities.size() > MAX_ENTITIES) throw new IOException("Too many dropped world item entities");
        ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream(Math.min(4096, entities.size() * 96));
        Set<Long> runtimeIds = new HashSet<>();
        try (DataOutputStream out = new DataOutputStream(bodyBytes)) {
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            out.writeInt(entities.size());
            for (WorldItemEntity entity : entities) {
                Objects.requireNonNull(entity, "entity");
                if (!runtimeIds.add(entity.runtimeId())) throw new IOException("Duplicate dropped item runtime ID");
                writeEntity(out, entity);
                if (bodyBytes.size() > MAX_FILE_BYTES - Integer.BYTES) {
                    throw new IOException("Dropped world item file exceeds size limit");
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
        if (complete.size() > MAX_FILE_BYTES) throw new IOException("Dropped world item file exceeds size limit");

        rejectSymlink(file);
        Path temporary = Files.createTempFile(file.getParent(), ".nexora-items-", ".tmp");
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

    public synchronized List<WorldItemEntity> load() throws IOException {
        rejectSymlink(file);
        if (!Files.exists(file)) return List.of();
        long size = Files.size(file);
        if (size < 9 || size > MAX_FILE_BYTES) throw new IOException("Invalid dropped world item file size");
        byte[] bytes = Files.readAllBytes(file);
        int bodyLength = bytes.length - Integer.BYTES;
        int storedChecksum = ByteBuffer.wrap(bytes, bodyLength, Integer.BYTES).getInt();
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bodyLength);
        if ((int) crc.getValue() != storedChecksum) throw new IOException("Dropped world item checksum mismatch");

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, bodyLength))) {
            if (in.readInt() != MAGIC) throw new IOException("Invalid dropped world item magic");
            int version = in.readUnsignedByte();
            if (version != VERSION) throw new IOException("Unsupported dropped world item version: " + version);
            int count = in.readInt();
            if (count < 0 || count > MAX_ENTITIES) throw new IOException("Invalid dropped world item count");
            List<WorldItemEntity> result = new ArrayList<>(count);
            Set<Long> runtimeIds = new HashSet<>();
            for (int i = 0; i < count; i++) {
                WorldItemEntity entity = readEntity(in);
                if (!runtimeIds.add(entity.runtimeId())) throw new IOException("Duplicate dropped item runtime ID");
                result.add(entity);
            }
            if (in.available() != 0) throw new IOException("Trailing dropped world item data");
            return List.copyOf(result);
        } catch (java.io.EOFException | IllegalArgumentException exception) {
            throw new IOException("Malformed dropped world item file", exception);
        }
    }

    private static void writeEntity(DataOutputStream out, WorldItemEntity entity) throws IOException {
        out.writeLong(entity.uniqueId());
        out.writeLong(entity.runtimeId());
        byte[] itemId = entity.stack().itemId().getBytes(StandardCharsets.UTF_8);
        if (itemId.length == 0 || itemId.length > MAX_ITEM_ID_BYTES) throw new IOException("Invalid dropped item ID length");
        out.writeByte(itemId.length);
        out.write(itemId);
        out.writeByte(entity.stack().count());
        out.writeByte(entity.stack().maxStackSize());
        out.writeDouble(entity.x());
        out.writeDouble(entity.y());
        out.writeDouble(entity.z());
        out.writeDouble(entity.velocityX());
        out.writeDouble(entity.velocityY());
        out.writeDouble(entity.velocityZ());
        out.writeLong(entity.pickupAvailableAtMillis());
        out.writeBoolean(entity.ownerXuid() != null);
        if (entity.ownerXuid() != null) {
            byte[] owner = entity.ownerXuid().getBytes(StandardCharsets.UTF_8);
            if (owner.length == 0 || owner.length > MAX_OWNER_BYTES) throw new IOException("Invalid item owner XUID length");
            out.writeByte(owner.length);
            out.write(owner);
        }
    }

    private static WorldItemEntity readEntity(DataInputStream in) throws IOException {
        long uniqueId = in.readLong();
        long runtimeId = in.readLong();
        int itemIdLength = in.readUnsignedByte();
        if (itemIdLength == 0 || itemIdLength > MAX_ITEM_ID_BYTES) throw new IOException("Invalid dropped item ID length");
        byte[] itemIdBytes = new byte[itemIdLength];
        in.readFully(itemIdBytes);
        String itemId = decodeUtf8(itemIdBytes);
        int count = in.readUnsignedByte();
        int maxStackSize = in.readUnsignedByte();
        ItemStack stack = new ItemStack(itemId, count, maxStackSize);
        double x = in.readDouble();
        double y = in.readDouble();
        double z = in.readDouble();
        double velocityX = in.readDouble();
        double velocityY = in.readDouble();
        double velocityZ = in.readDouble();
        long pickupAvailableAtMillis = in.readLong();
        String ownerXuid = null;
        if (in.readBoolean()) {
            int ownerLength = in.readUnsignedByte();
            if (ownerLength == 0 || ownerLength > MAX_OWNER_BYTES) throw new IOException("Invalid item owner XUID length");
            byte[] ownerBytes = new byte[ownerLength];
            in.readFully(ownerBytes);
            ownerXuid = decodeUtf8(ownerBytes);
        }
        return new WorldItemEntity(uniqueId, runtimeId, stack, x, y, z,
                velocityX, velocityY, velocityZ, pickupAvailableAtMillis, ownerXuid);
    }

    private static String decodeUtf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("Invalid UTF-8 in dropped world item file", exception);
        }
    }

    private static void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Dropped world item storage path must not be a symbolic link");
    }
}
