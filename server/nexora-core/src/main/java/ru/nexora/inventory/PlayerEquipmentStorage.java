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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.CRC32C;

/** Checksummed atomic persistence for armor, offhand, cursor and magical equipment slots. */
public final class PlayerEquipmentStorage {
    private static final int MAGIC = 0x4e584551; // NXEQ
    private static final int VERSION = 3;
    private static final int LEGACY_VERSION = 1;
    private static final int MAX_FILE_BYTES = 4096;
    private static final int MAX_ITEM_ID_BYTES = 128;
    private final Path directory;

    public PlayerEquipmentStorage(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Path root = directory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        rejectSymlink(root);
        this.directory = root;
    }

    public void save(String xuid, PlayerEquipment equipment) throws IOException {
        Objects.requireNonNull(equipment, "equipment");
        PlayerEquipment.Snapshot snapshot = equipment.snapshot();
        ByteArrayOutputStream body = new ByteArrayOutputStream(512);
        try (DataOutputStream out = new DataOutputStream(body)) {
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            for (Optional<ItemStack> item : snapshot.armor()) writeStack(out, item, VERSION);
            writeStack(out, snapshot.offhand(), VERSION);
            writeStack(out, snapshot.cursor(), VERSION);
            for (Optional<ItemStack> item : snapshot.magicSlots()) writeStack(out, item, VERSION);
        }
        byte[] data = body.toByteArray();
        CRC32C crc = new CRC32C();
        crc.update(data, 0, data.length);
        ByteArrayOutputStream complete = new ByteArrayOutputStream(data.length + 4);
        complete.writeBytes(data);
        try (DataOutputStream out = new DataOutputStream(complete)) {
            out.writeInt((int) crc.getValue());
        }
        if (complete.size() > MAX_FILE_BYTES) throw new IOException("Player equipment file exceeds size limit");

        Path target = path(xuid);
        rejectSymlink(target);
        Path temporary = Files.createTempFile(directory, ".nexora-equipment-", ".tmp");
        try {
            Files.write(temporary, complete.toByteArray());
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public Optional<PlayerEquipment> load(String xuid) throws IOException {
        Path file = path(xuid);
        rejectSymlink(file);
        if (!Files.exists(file)) return Optional.empty();
        long size = Files.size(file);
        if (size < 9 || size > MAX_FILE_BYTES) throw new IOException("Invalid player equipment file size");
        byte[] bytes = Files.readAllBytes(file);
        int bodyLength = bytes.length - 4;
        int stored = ByteBuffer.wrap(bytes, bodyLength, 4).getInt();
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bodyLength);
        if ((int) crc.getValue() != stored) throw new IOException("Player equipment checksum mismatch");

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, bodyLength))) {
            if (in.readInt() != MAGIC) throw new IOException("Invalid player equipment magic");
            int version = in.readUnsignedByte();
            if (version != VERSION && version != 2 && version != LEGACY_VERSION) {
                throw new IOException("Unsupported player equipment version: " + version);
            }
            List<Optional<ItemStack>> armor = new ArrayList<>(PlayerEquipment.ARMOR_SLOTS);
            for (int i = 0; i < PlayerEquipment.ARMOR_SLOTS; i++) armor.add(readStack(in, version));
            Optional<ItemStack> offhand = readStack(in, version);
            Optional<ItemStack> cursor = readStack(in, version);
            List<Optional<ItemStack>> magic = new ArrayList<>(PlayerEquipment.MAGIC_SLOTS);
            if (version == VERSION) {
                for (int i = 0; i < PlayerEquipment.MAGIC_SLOTS; i++) magic.add(readStack(in, version));
            } else {
                for (int i = 0; i < PlayerEquipment.MAGIC_SLOTS; i++) magic.add(Optional.empty());
            }
            if (in.available() != 0) throw new IOException("Trailing player equipment data");
            PlayerEquipment result = new PlayerEquipment();
            result.restore(new PlayerEquipment.Snapshot(armor, offhand, cursor, magic));
            return Optional.of(result);
        } catch (java.io.EOFException exception) {
            throw new IOException("Truncated player equipment file", exception);
        }
    }

    private static void writeStack(DataOutputStream out, Optional<ItemStack> optional, int version) throws IOException {
        out.writeBoolean(optional.isPresent());
        if (optional.isEmpty()) return;
        ItemStack stack = optional.orElseThrow();
        byte[] id = stack.itemId().getBytes(StandardCharsets.UTF_8);
        if (id.length > MAX_ITEM_ID_BYTES) throw new IOException("Item identifier exceeds equipment storage limit");
        out.writeByte(id.length);
        out.write(id);
        out.writeByte(stack.count());
        out.writeByte(stack.maxStackSize());
        if (version >= 3) {
            out.writeByte(stack.enchantments().size());
            for (var enchantment : stack.enchantments().entrySet()) {
                byte[] enchantmentId = enchantment.getKey().getBytes(StandardCharsets.UTF_8);
                if (enchantmentId.length > 128) throw new IOException("Enchantment identifier exceeds equipment storage limit");
                out.writeByte(enchantmentId.length);
                out.write(enchantmentId);
                out.writeByte(enchantment.getValue());
            }
            out.writeByte(stack.repairCost());
            out.writeShort(stack.durabilityDamage());
        }
    }

    private static Optional<ItemStack> readStack(DataInputStream in, int version) throws IOException {
        if (!in.readBoolean()) return Optional.empty();
        int length = in.readUnsignedByte();
        if (length < 3 || length > MAX_ITEM_ID_BYTES) throw new IOException("Invalid equipment item identifier length");
        byte[] id = new byte[length];
        in.readFully(id);
        String itemId;
        try {
            itemId = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(id)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("Invalid UTF-8 equipment item identifier", exception);
        }
        try {
            int count = in.readUnsignedByte();
            int maxStackSize = in.readUnsignedByte();
            java.util.Map<String, Integer> enchantments = new java.util.LinkedHashMap<>();
            int repairCost = 0;
            int durabilityDamage = 0;
            if (version >= 3) {
                int enchantmentCount = in.readUnsignedByte();
                if (enchantmentCount > 32) throw new IOException("Too many equipment enchantments");
                for (int i = 0; i < enchantmentCount; i++) {
                    int enchantmentLength = in.readUnsignedByte();
                    if (enchantmentLength < 3 || enchantmentLength > 128) {
                        throw new IOException("Invalid equipment enchantment identifier length");
                    }
                    byte[] enchantmentBytes = new byte[enchantmentLength];
                    in.readFully(enchantmentBytes);
                    String enchantmentId;
                    try {
                        enchantmentId = StandardCharsets.UTF_8.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(enchantmentBytes)).toString();
                    } catch (CharacterCodingException exception) {
                        throw new IOException("Invalid UTF-8 equipment enchantment identifier", exception);
                    }
                    int level = in.readUnsignedByte();
                    if (enchantments.putIfAbsent(enchantmentId, level) != null) {
                        throw new IOException("Duplicate equipment enchantment");
                    }
                }
                repairCost = in.readUnsignedByte();
                durabilityDamage = in.readUnsignedShort();
            }
            return Optional.of(new ItemStack(itemId, count, maxStackSize,
                    enchantments, repairCost, durabilityDamage));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid stored equipment item", exception);
        }
    }

    private Path path(String xuid) {
        if (xuid == null || !xuid.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid player XUID");
        }
        return directory.resolve(xuid + ".nxe");
    }

    private static void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Player equipment path must not be a symbolic link");
    }
}
