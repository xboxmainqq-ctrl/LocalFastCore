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
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

/** Atomic, checksummed persistence for mob-spawner configuration and delay timers. */
public final class MobSpawnerStorage {
    private static final int MAGIC = 0x4e585350; // NXSP
    private static final int VERSION = 2;
    private static final int MAX_ENTRIES = MobSpawnerManager.MAX_TRACKED_SPAWNERS;
    private final Path file;

    public MobSpawnerStorage(Path worldDirectory) throws IOException {
        Files.createDirectories(worldDirectory);
        this.file = worldDirectory.resolve("mob-spawners.dat").toAbsolutePath().normalize();
        if (!this.file.startsWith(worldDirectory.toAbsolutePath().normalize())) {
            throw new IOException("Spawner storage path escapes world directory");
        }
        if (Files.isSymbolicLink(this.file)) throw new IOException("Spawner storage must not be a symlink");
    }

    public synchronized void save(List<MobSpawnerState> states) throws IOException {
        if (states.size() > MAX_ENTRIES) throw new IOException("Too many mob spawners to persist");
        ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bodyBytes)) {
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            out.writeInt(states.size());
            for (MobSpawnerState state : states) {
                out.writeInt(state.x());
                out.writeInt(state.y());
                out.writeInt(state.z());
                out.writeUTF(state.entityIdentifier());
                out.writeInt(state.delayTicks());
                out.writeLong(state.nextSpawnTick());
            }
        }
        byte[] body = bodyBytes.toByteArray();
        CRC32C checksum = new CRC32C();
        checksum.update(body, 0, body.length);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(body.length + Integer.BYTES);
        bytes.writeBytes(body);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt((int) checksum.getValue());
        }
        Path temporary = Files.createTempFile(file.getParent(), ".nexora-spawners-", ".tmp");
        try {
            Files.write(temporary, bytes.toByteArray());
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public synchronized List<MobSpawnerState> load() throws IOException {
        if (Files.isSymbolicLink(file)) throw new IOException("Spawner storage must not be a symlink");
        if (!Files.exists(file)) return List.of();
        long size = Files.size(file);
        if (size < 13 || size > 1_000_000) throw new IOException("Invalid spawner storage size");
        byte[] bytes = Files.readAllBytes(file);
        int bodyLength = bytes.length - Integer.BYTES;
        int expected = ((bytes[bodyLength] & 255) << 24) | ((bytes[bodyLength + 1] & 255) << 16)
                | ((bytes[bodyLength + 2] & 255) << 8) | (bytes[bodyLength + 3] & 255);
        CRC32C checksum = new CRC32C();
        checksum.update(bytes, 0, bodyLength);
        if ((int) checksum.getValue() != expected) throw new IOException("Spawner storage checksum mismatch");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, bodyLength))) {
            if (in.readInt() != MAGIC) throw new IOException("Invalid spawner storage magic");
            int version = in.readUnsignedByte();
            if (version != 1 && version != VERSION) {
                throw new IOException("Unsupported spawner storage format");
            }
            int count = in.readInt();
            if (count < 0 || count > MAX_ENTRIES) throw new IOException("Invalid spawner count");
            List<MobSpawnerState> result = new ArrayList<>(count);
            java.util.HashSet<String> positions = new java.util.HashSet<>();
            for (int i = 0; i < count; i++) {
                int x = in.readInt(), y = in.readInt(), z = in.readInt();
                MobSpawnerState state;
                try {
                    String identifier = in.readUTF();
                    int delayTicks = in.readInt();
                    long nextSpawnTick = in.readLong();
                    // Version 1 stored an absolute server tick. That clock is not stable across restarts,
                    // so migrate old entries to a safe full configured delay.
                    if (version == 1 && nextSpawnTick > delayTicks) nextSpawnTick = delayTicks;
                    state = new MobSpawnerState(x, y, z, identifier, delayTicks, nextSpawnTick);
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Invalid persisted mob spawner", exception);
                }
                if (!positions.add(x + ":" + y + ":" + z)) {
                    throw new IOException("Duplicate persisted mob spawner position");
                }
                result.add(state);
            }
            if (in.available() != 0) throw new IOException("Trailing spawner storage data");
            return List.copyOf(result);
        }
    }
}
