package ru.nexora.inventory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.zip.CRC32C;

/** Atomic, checksummed persistence for furnace burn and cook progress. */
public final class FurnaceStateStorage {
    private static final int MAGIC = 0x4e584652; // NXFR
    private static final int VERSION = 3;
    private static final int BODY_V1_BYTES = Integer.BYTES + 1 + 3 * Integer.BYTES + 2 * Short.BYTES;
    private static final int BODY_V2_BYTES = BODY_V1_BYTES + Short.BYTES;
    private static final int BODY_V3_BYTES = BODY_V2_BYTES + Integer.BYTES;
    private final Path directory;

    public FurnaceStateStorage(Path rootDirectory, String worldName) throws IOException {
        if (rootDirectory == null) throw new NullPointerException("rootDirectory");
        if (worldName == null || !worldName.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Unsafe world name");
        }
        Path root = rootDirectory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        rejectSymlink(root);
        Path worldDirectory = root.resolve(worldName).normalize();
        if (!worldDirectory.startsWith(root)) throw new IOException("Furnace path escapes storage root");
        Files.createDirectories(worldDirectory);
        rejectSymlink(worldDirectory);
        directory = worldDirectory.resolve("furnaces");
        Files.createDirectories(directory);
        rejectSymlink(directory);
    }

    public synchronized void save(int x, int y, int z,
                                  FurnaceService.FurnaceStateSnapshot state) throws IOException {
        if (state == null || state.burnTicksRemaining() < 0 || state.burnTicksRemaining() > FurnaceService.MAX_BURN_TICKS
                || state.cookProgress() < 0 || state.cookProgress() >= FurnaceService.COOK_TIME_TICKS) {
            throw new IllegalArgumentException("Invalid furnace state");
        }
        if (state.burnTicksTotal() < state.burnTicksRemaining() || state.burnTicksTotal() > FurnaceService.MAX_BURN_TICKS) {
            throw new IllegalArgumentException("Invalid total furnace burn duration");
        }
        if (state.pendingExperienceMilli() < 0
                || state.pendingExperienceMilli() > FurnaceService.MAX_PENDING_EXPERIENCE_MILLI) {
            throw new IllegalArgumentException("Invalid pending furnace experience");
        }
        ByteBuffer body = ByteBuffer.allocate(BODY_V3_BYTES);
        body.putInt(MAGIC).put((byte) VERSION).putInt(x).putInt(y).putInt(z)
                .putShort((short) state.burnTicksRemaining()).putShort((short) state.cookProgress())
                .putShort((short) state.burnTicksTotal()).putInt(state.pendingExperienceMilli());
        CRC32C crc = new CRC32C();
        crc.update(body.array(), 0, BODY_V3_BYTES);
        ByteBuffer file = ByteBuffer.allocate(BODY_V3_BYTES + Integer.BYTES);
        file.put(body.array()).putInt((int) crc.getValue());
        Path target = path(x, y, z);
        rejectSymlink(target);
        Path temporary = Files.createTempFile(directory, ".nexora-furnace-", ".tmp");
        try {
            Files.write(temporary, file.array());
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public synchronized Optional<FurnaceService.FurnaceStateSnapshot> load(int x, int y, int z)
            throws IOException {
        Path file = path(x, y, z);
        rejectSymlink(file);
        if (!Files.exists(file)) return Optional.empty();
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length != BODY_V1_BYTES + Integer.BYTES
                && bytes.length != BODY_V2_BYTES + Integer.BYTES
                && bytes.length != BODY_V3_BYTES + Integer.BYTES) {
            throw new IOException("Invalid furnace state file size");
        }
        ByteBuffer in = ByteBuffer.wrap(bytes);
        if (in.getInt() != MAGIC) throw new IOException("Invalid furnace state magic");
        int version = Byte.toUnsignedInt(in.get());
        int bodyBytes;
        if (version == 1 && bytes.length == BODY_V1_BYTES + Integer.BYTES) bodyBytes = BODY_V1_BYTES;
        else if (version == 2 && bytes.length == BODY_V2_BYTES + Integer.BYTES) bodyBytes = BODY_V2_BYTES;
        else if (version == VERSION && bytes.length == BODY_V3_BYTES + Integer.BYTES) bodyBytes = BODY_V3_BYTES;
        else throw new IOException("Unsupported or malformed furnace state version: " + version);
        int storedCrc = ByteBuffer.wrap(bytes, bodyBytes, Integer.BYTES).getInt();
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bodyBytes);
        if ((int) crc.getValue() != storedCrc) throw new IOException("Furnace state checksum mismatch");
        if (in.getInt() != x || in.getInt() != y || in.getInt() != z) {
            throw new IOException("Furnace state coordinates do not match file name");
        }
        int burn = Short.toUnsignedInt(in.getShort());
        int progress = Short.toUnsignedInt(in.getShort());
        int total = version == 1 ? burn : Short.toUnsignedInt(in.getShort());
        int pendingExperience = version >= 3 ? in.getInt() : 0;
        if (burn > FurnaceService.MAX_BURN_TICKS || progress >= FurnaceService.COOK_TIME_TICKS || total < burn || total > FurnaceService.MAX_BURN_TICKS
                || pendingExperience < 0 || pendingExperience > FurnaceService.MAX_PENDING_EXPERIENCE_MILLI) {
            throw new IOException("Invalid furnace state values");
        }
        return Optional.of(new FurnaceService.FurnaceStateSnapshot(burn, progress, total, pendingExperience));
    }

    public synchronized void delete(int x, int y, int z) throws IOException {
        Path file = path(x, y, z);
        rejectSymlink(file);
        Files.deleteIfExists(file);
    }

    private Path path(int x, int y, int z) {
        return directory.resolve(x + "_" + y + "_" + z + ".nxf");
    }

    private static void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Furnace storage path must not be a symbolic link: " + path);
    }
}
