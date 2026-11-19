package ru.nexora.inventory;

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
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.CRC32C;

/** Versioned, checksummed, atomic persistence for block containers addressed by world coordinates. */
public final class ContainerInventoryStorage {
    private static final int MAGIC = 0x4e584354; // NXCT
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_FILE_BYTES = 16_384;
    private static final int MAX_ITEM_ID_BYTES = 128;
    private static final Pattern SAFE_WORLD_NAME = Pattern.compile("[A-Za-z0-9 _-]{1,64}");

    private final Path containersDirectory;

    public ContainerInventoryStorage(Path rootDirectory, String worldName) throws IOException {
        Objects.requireNonNull(rootDirectory, "rootDirectory");
        if (worldName == null || !SAFE_WORLD_NAME.matcher(worldName).matches()) {
            throw new IllegalArgumentException("World name must contain only letters, digits, spaces, '_' or '-'");
        }
        Path root = rootDirectory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        rejectSymlink(root);
        Path worldDirectory = root.resolve(worldName).normalize();
        if (!worldDirectory.startsWith(root)) throw new IOException("Container path escapes storage root");
        Files.createDirectories(worldDirectory);
        rejectSymlink(worldDirectory);
        containersDirectory = worldDirectory.resolve("containers");
        Files.createDirectories(containersDirectory);
        rejectSymlink(containersDirectory);
    }

    public synchronized void save(int x, int y, int z, ContainerInventory inventory) throws IOException {
        Objects.requireNonNull(inventory, "inventory");
        Path target = containerPath(x, y, z);
        rejectSymlink(target);
        var snapshot = inventory.snapshot();
        ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream(512);
        try (DataOutputStream out = new DataOutputStream(bodyBytes)) {
            out.writeInt(MAGIC);
            out.writeByte(FORMAT_VERSION);
            out.writeInt(x);
            out.writeInt(y);
            out.writeInt(z);
            out.writeShort(snapshot.size());
            for (var slot : snapshot) {
                out.writeBoolean(slot.isPresent());
                if (slot.isEmpty()) continue;
                ItemStack stack = slot.orElseThrow();
                byte[] id = stack.itemId().getBytes(StandardCharsets.UTF_8);
                if (id.length > MAX_ITEM_ID_BYTES) throw new IOException("Container item ID exceeds storage limit");
                out.writeByte(id.length);
                out.write(id);
                out.writeByte(stack.count());
                out.writeByte(stack.maxStackSize());
            }
        }
        byte[] body = bodyBytes.toByteArray();
        CRC32C checksum = new CRC32C();
        checksum.update(body, 0, body.length);
        ByteArrayOutputStream fileBytes = new ByteArrayOutputStream(body.length + Integer.BYTES);
        fileBytes.writeBytes(body);
        try (DataOutputStream out = new DataOutputStream(fileBytes)) {
            out.writeInt((int) checksum.getValue());
        }
        if (fileBytes.size() > MAX_FILE_BYTES) throw new IOException("Container inventory exceeds storage limit");
        ru.nexora.persistence.BackupFile.write(target, fileBytes.toByteArray(), candidate -> readContainerFile(candidate, x, y, z));
    }

    public synchronized Optional<ContainerInventory> load(int x, int y, int z) throws IOException {
        Path file = containerPath(x, y, z);
        return ru.nexora.persistence.BackupFile.load(file, candidate -> readContainerFile(candidate, x, y, z));
    }

    private ContainerInventory readContainerFile(Path file, int x, int y, int z) throws IOException {
        rejectSymlink(file);
        if (!Files.exists(file)) throw new IOException("Container file disappeared during recovery");
        long size = Files.size(file);
        if (size < 19 || size > MAX_FILE_BYTES) throw new IOException("Invalid NEXORA container file size");
        byte[] bytes = Files.readAllBytes(file);
        int bodyLength = bytes.length - Integer.BYTES;
        int storedChecksum = ByteBuffer.wrap(bytes, bodyLength, Integer.BYTES).getInt();
        CRC32C checksum = new CRC32C();
        checksum.update(bytes, 0, bodyLength);
        if ((int) checksum.getValue() != storedChecksum) {
            throw new IOException("NEXORA container checksum mismatch: " + file);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, bodyLength))) {
            if (in.readInt() != MAGIC) throw new IOException("Invalid NEXORA container magic");
            int version = in.readUnsignedByte();
            if (version != FORMAT_VERSION) throw new IOException("Unsupported NEXORA container version: " + version);
            if (in.readInt() != x || in.readInt() != y || in.readInt() != z) {
                throw new IOException("NEXORA container coordinates do not match its file name");
            }
            int slots = in.readUnsignedShort();
            if (slots < 1 || slots > 256) throw new IOException("Invalid NEXORA container slot count");
            ContainerInventory inventory = new ContainerInventory(slots);
            for (int slot = 0; slot < slots; slot++) {
                if (!in.readBoolean()) continue;
                int length = in.readUnsignedByte();
                if (length < 3 || length > MAX_ITEM_ID_BYTES) throw new IOException("Invalid container item ID length");
                byte[] idBytes = new byte[length];
                in.readFully(idBytes);
                String itemId;
                try {
                    itemId = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(idBytes)).toString();
                } catch (CharacterCodingException exception) {
                    throw new IOException("Invalid UTF-8 item ID in container", exception);
                }
                try {
                    inventory.setSlot(slot, new ItemStack(itemId, in.readUnsignedByte(), in.readUnsignedByte()));
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Invalid item stack in container", exception);
                }
            }
            if (in.available() != 0) throw new IOException("Trailing data in NEXORA container file");
            return inventory;
        } catch (java.io.EOFException exception) {
            throw new IOException("Truncated NEXORA container file", exception);
        }
    }


    public synchronized void delete(int x, int y, int z) throws IOException {
        Path file = containerPath(x, y, z);
        rejectSymlink(file);
        ru.nexora.persistence.BackupFile.delete(file);
    }

    private Path containerPath(int x, int y, int z) {
        return containersDirectory.resolve(x + "_" + y + "_" + z + ".nxc");
    }

    private static void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("NEXORA container storage path must not be a symbolic link: " + path);
    }
}
