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
    private static final int VERSION = 1;
    private static final int BODY_BYTES = Integer.BYTES + 1 + 3 * Integer.BYTES + 2 * Integer.BYTES;
    private final Path directory;

    public FurnaceStateStorage(Path rootDirectory, String worldName) throws IOException {
        if (rootDirectory == null) throw new NullPointerException("rootDirectory");
        if (worldName == null || !worldName.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Unsafe world name");
        }
        Path root = rootDirectory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        rejectSymlink(root);
        directory = root.resolve(worldName).resolve("furnaces").normalize();
        if (!directory.startsWith(root)) throw new IOException("Furnace path escapes storage root");
        Files.createDirectories(directory);
        rejectSymlink(root.resolve(worldName));
        rejectSymlink(directory);
    }

    public synchronized void save(int x, int y, int z,
                                  FurnaceService.FurnaceStateSnapshot state) throws IOException {
        if (state == null || state.burnTicksRemaining() < 0 || state.burnTicksRemaining() > 2400
                || state.cookProgress() < 0 || state.cookProgress() >= FurnaceService.COOK_TIME_TICKS) {
            throw new IllegalArgumentException("Invalid furnace state");
        }
        ByteBuffer body = ByteBuffer.allocate(BODY_BYTES);
        body.putInt(MAGIC).put((byte) VERSION).putInt(x).putInt(y).putInt(z)
                .putShort((short) state.burnTicksRemaining()).putShort((short) state.cookProgress());
        CRC32C crc = new CRC32C();
        crc.update(body.array(), 0, BODY_BYTES);
        ByteBuffer file = ByteBuffer.allocate(BODY_BYTES + Integer.BYTES);
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
        if (bytes.length != BODY_BYTES + Integer.BYTES) throw new IOException("Invalid furnace state file size");
        ByteBuffer in = ByteBuffer.wrap(bytes);
        int storedCrc = ByteBuffer.wrap(bytes, BODY_BYTES, Integer.BYTES).getInt();
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, BODY_BYTES);
        if ((int) crc.getValue() != storedCrc) throw new IOException("Furnace state checksum mismatch");
        if (in.getInt() != MAGIC) throw new IOException("Invalid furnace state magic");
        int version = Byte.toUnsignedInt(in.get());
        if (version != VERSION) throw new IOException("Unsupported furnace state version: " + version);
        if (in.getInt() != x || in.getInt() != y || in.getInt() != z) {
            throw new IOException("Furnace state coordinates do not match file name");
        }
        int burn = Short.toUnsignedInt(in.getShort());
        int progress = Short.toUnsignedInt(in.getShort());
        if (burn > 2400 || progress >= FurnaceService.COOK_TIME_TICKS) {
            throw new IOException("Invalid furnace state values");
        }
        return Optional.of(new FurnaceService.FurnaceStateSnapshot(burn, progress));
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
