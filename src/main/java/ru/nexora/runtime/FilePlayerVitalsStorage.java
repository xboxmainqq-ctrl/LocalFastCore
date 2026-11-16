package ru.nexora.runtime;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.CRC32C;

/** Atomic, checksummed persistence for player health and hunger progression. */
public final class FilePlayerVitalsStorage {
    private static final int MAGIC = 0x4E585654; // NXVT
    private static final short VERSION = 1;
    private final Path directory;

    public FilePlayerVitalsStorage(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    }

    public PlayerVitals load(UUID playerId) throws IOException {
        Objects.requireNonNull(playerId, "playerId");
        Path file = fileFor(playerId);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                || !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new PlayerVitals(playerId);
        validateDirectory();
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) != 4 + 2 + 16 + 4 + 4 + 4 + 4) {
            throw new IOException("Invalid player vitals file");
        }
        CRC32C crc = new CRC32C();
        try (var raw = Files.newInputStream(file);
             var checked = new java.util.zip.CheckedInputStream(raw, crc);
             var input = new DataInputStream(checked)) {
            if (input.readInt() != MAGIC || input.readShort() != VERSION) {
                throw new IOException("Unsupported player vitals file");
            }
            UUID owner = new UUID(input.readLong(), input.readLong());
            if (!owner.equals(playerId)) throw new IOException("Player vitals owner mismatch");
            int maxHealth = input.readInt();
            int health = input.readInt();
            int hunger = input.readInt();
            int checksum = input.readInt();
            if (checksum != (int) crc.getValue() || input.read() != -1) {
                throw new IOException("Player vitals checksum mismatch");
            }
            try {
                return new PlayerVitals(playerId, maxHealth, health, hunger);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid values in player vitals file", invalid);
            }
        }
    }

    public void save(PlayerVitals vitals) throws IOException {
        Objects.requireNonNull(vitals, "vitals");
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) validateDirectory();
        else Files.createDirectories(directory);
        validateDirectory();
        Path target = fileFor(vitals.playerId());
        if (Files.isSymbolicLink(target)) throw new IOException("Refusing symbolic-link vitals file");
        Path temp = Files.createTempFile(directory, "." + vitals.playerId() + "-", ".tmp");
        boolean moved = false;
        try {
            CRC32C crc = new CRC32C();
            try (var raw = Files.newOutputStream(temp, StandardOpenOption.WRITE);
                 var checked = new java.util.zip.CheckedOutputStream(raw, crc);
                 var output = new DataOutputStream(checked)) {
                output.writeInt(MAGIC);
                output.writeShort(VERSION);
                output.writeLong(vitals.playerId().getMostSignificantBits());
                output.writeLong(vitals.playerId().getLeastSignificantBits());
                output.writeInt(vitals.maxHealth());
                output.writeInt(vitals.health());
                output.writeInt(vitals.hunger());
                output.flush();
            }
            try (var output = new DataOutputStream(Files.newOutputStream(temp,
                    StandardOpenOption.APPEND))) {
                output.writeInt((int) crc.getValue());
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temp);
        }
    }

    private Path fileFor(UUID id) { return directory.resolve(id + ".nxv"); }

    private void validateDirectory() throws IOException {
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Player vitals path is not a safe directory");
        }
    }
}
