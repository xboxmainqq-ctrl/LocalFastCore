package ru.nexora.runtime;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.CRC32C;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;

/** Atomic, checksummed persistence for fixed-size player inventories. */
public final class FilePlayerInventoryStorage {
    private static final int MAGIC = 0x4E585049; // NXPI
    private static final short FORMAT_VERSION = 1;
    private static final int MAX_FILE_BYTES = 4096;
    private static final int SLOT_COUNT = PlayerInventory.DEFAULT_SLOTS;

    private final Path directory;

    public FilePlayerInventoryStorage(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory")
                .toAbsolutePath().normalize();
    }

    public PlayerInventory load(UUID playerId) throws IOException {
        Objects.requireNonNull(playerId, "playerId");
        Path file = fileFor(playerId);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return new PlayerInventory(playerId);
        }
        validateDirectory();
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return new PlayerInventory(playerId);
        }
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Player inventory is not a regular file: " + file);
        }
        long size = Files.size(file);
        if (size < 4 + 2 + 16 + 4 + 4 || size > MAX_FILE_BYTES) {
            throw new IOException("Player inventory file has an invalid size: " + size);
        }

        CRC32C checksum = new CRC32C();
        PlayerInventory inventory;
        try (var raw = Files.newInputStream(file);
             CheckedInputStream checked = new CheckedInputStream(raw, checksum);
             DataInputStream input = new DataInputStream(checked)) {
            if (input.readInt() != MAGIC) throw new IOException("Invalid player inventory signature");
            short version = input.readShort();
            if (version != FORMAT_VERSION) {
                throw new IOException("Unsupported player inventory format version: " + version);
            }
            UUID storedOwner = new UUID(input.readLong(), input.readLong());
            if (!playerId.equals(storedOwner)) {
                throw new IOException("Player inventory owner does not match its file name");
            }
            int slots = input.readInt();
            if (slots != SLOT_COUNT) throw new IOException("Unsupported player inventory slot count");
            inventory = new PlayerInventory(playerId, slots);
            for (int slot = 0; slot < slots; slot++) {
                boolean occupied = input.readBoolean();
                if (occupied) {
                    String itemId = input.readUTF();
                    int count = input.readUnsignedByte();
                    try {
                        inventory.restoreSlot(slot, new ItemStack(itemId, count));
                    } catch (IllegalArgumentException invalidStack) {
                        throw new IOException("Invalid item stack in player inventory", invalidStack);
                    }
                }
            }
            long actualChecksum = checksum.getValue();
            int storedChecksum = input.readInt();
            if (storedChecksum != (int) actualChecksum) {
                throw new IOException("Player inventory checksum mismatch");
            }
            if (input.read() != -1) throw new IOException("Trailing data in player inventory file");
        } catch (java.io.EOFException truncated) {
            throw new IOException("Truncated player inventory file", truncated);
        }
        return inventory;
    }

    public void save(PlayerInventory inventory) throws IOException {
        Objects.requireNonNull(inventory, "inventory");
        if (inventory.slotCount() != SLOT_COUNT) {
            throw new IOException("Only 36-slot player inventories can be persisted");
        }
        ensureDirectory();
        Path target = fileFor(inventory.ownerId());
        if (Files.isSymbolicLink(target)) {
            throw new IOException("Refusing to replace a symbolic-link player inventory: " + target);
        }

        Path temporary = Files.createTempFile(directory,
                "." + inventory.ownerId() + "-", ".tmp");
        boolean moved = false;
        try {
            CRC32C checksum = new CRC32C();
            try (var raw = Files.newOutputStream(temporary, StandardOpenOption.WRITE);
                 CheckedOutputStream checked = new CheckedOutputStream(raw, checksum);
                 DataOutputStream output = new DataOutputStream(checked)) {
                output.writeInt(MAGIC);
                output.writeShort(FORMAT_VERSION);
                output.writeLong(inventory.ownerId().getMostSignificantBits());
                output.writeLong(inventory.ownerId().getLeastSignificantBits());
                output.writeInt(SLOT_COUNT);
                for (int slot = 0; slot < SLOT_COUNT; slot++) {
                    ItemStack stack = inventory.stackAt(slot);
                    output.writeBoolean(stack != null);
                    if (stack != null) {
                        output.writeUTF(stack.itemId());
                        output.writeByte(stack.count());
                    }
                }
                output.flush();
            }
            try (DataOutputStream trailer = new DataOutputStream(Files.newOutputStream(
                    temporary, StandardOpenOption.WRITE, StandardOpenOption.APPEND))) {
                trailer.writeInt((int) checksum.getValue());
                trailer.flush();
            }
            if (Files.size(temporary) > MAX_FILE_BYTES) {
                throw new IOException("Player inventory exceeds the maximum file size");
            }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    private Path fileFor(UUID playerId) {
        return directory.resolve(playerId.toString() + ".nxi");
    }

    private void ensureDirectory() throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            validateDirectory();
        } else {
            Files.createDirectories(directory);
            validateDirectory();
        }
    }

    private void validateDirectory() throws IOException {
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Player inventory storage path is not a safe directory: " + directory);
        }
    }
}
