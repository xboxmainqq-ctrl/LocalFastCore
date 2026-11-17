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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.CRC32C;

/** Versioned, checksummed and atomically replaced storage for player and crafting-UI inventories. */
public final class PlayerInventoryStorage {
    private static final int MAGIC = 0x4e584956; // NXIV
    private static final int FORMAT_VERSION = 1;
    private static final int CRAFTING_UI_FORMAT_VERSION = 2;
    private static final int MAX_FILE_BYTES = 32_768;
    private static final int MAX_ITEM_ID_BYTES = 128;

    private final Path inventoriesDirectory;

    public PlayerInventoryStorage(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Path root = directory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        rejectSymlink(root);
        inventoriesDirectory = root;
    }

    /** Legacy player-only save. Existing version-1 files remain readable. */
    public void save(String xuid, PlayerInventory inventory) throws IOException {
        Objects.requireNonNull(inventory, "inventory");
        List<Optional<ItemStack>> snapshot;
        int selectedHotbarSlot;
        synchronized (inventory) {
            snapshot = inventory.snapshot();
            selectedHotbarSlot = inventory.selectedHotbarSlot();
        }
        writeFile(xuid, FORMAT_VERSION, snapshot, selectedHotbarSlot, null);
    }

    /** Atomically saves player inventory and the non-persistent-in-world player crafting UI grid. */
    public void save(String xuid, PlayerInventory inventory, ContainerInventory craftingUiInventory)
            throws IOException {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(craftingUiInventory, "craftingUiInventory");
        if (craftingUiInventory.size() != BedrockCraftingUiSlots.SIZE) {
            throw new IllegalArgumentException("Invalid player crafting UI inventory size");
        }
        List<Optional<ItemStack>> playerSnapshot;
        List<Optional<ItemStack>> uiSnapshot;
        int selectedHotbarSlot;
        synchronized (inventory) {
            synchronized (craftingUiInventory) {
                playerSnapshot = inventory.snapshot();
                selectedHotbarSlot = inventory.selectedHotbarSlot();
                uiSnapshot = craftingUiInventory.snapshot();
            }
        }
        writeFile(xuid, CRAFTING_UI_FORMAT_VERSION, playerSnapshot, selectedHotbarSlot, uiSnapshot);
    }

    private void writeFile(String xuid, int version, List<Optional<ItemStack>> playerSlots,
                           int selectedHotbarSlot, List<Optional<ItemStack>> uiSlots) throws IOException {
        Path target = inventoryPath(xuid);
        rejectSymlink(target);
        ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream(1024);
        try (DataOutputStream output = new DataOutputStream(bodyBytes)) {
            output.writeInt(MAGIC);
            output.writeByte(version);
            output.writeShort(playerSlots.size());
            output.writeByte(selectedHotbarSlot);
            writeSlots(output, playerSlots);
            if (version == CRAFTING_UI_FORMAT_VERSION) {
                output.writeShort(uiSlots.size());
                writeSlots(output, uiSlots);
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
        if (fileBytes.size() > MAX_FILE_BYTES) {
            throw new IOException("NEXORA player inventory exceeds storage limit");
        }

        Path temporary = Files.createTempFile(inventoriesDirectory, ".nexora-inventory-", ".tmp");
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

    private static void writeSlots(DataOutputStream output, List<Optional<ItemStack>> slots) throws IOException {
        for (var slot : slots) {
            output.writeBoolean(slot.isPresent());
            if (slot.isEmpty()) continue;
            ItemStack stack = slot.orElseThrow();
            byte[] itemId = stack.itemId().getBytes(StandardCharsets.UTF_8);
            if (itemId.length > MAX_ITEM_ID_BYTES) {
                throw new IOException("NEXORA item identifier exceeds storage limit");
            }
            output.writeByte(itemId.length);
            output.write(itemId);
            output.writeByte(stack.count());
            output.writeByte(stack.maxStackSize());
        }
    }

    public Optional<PlayerInventory> load(String xuid) throws IOException {
        return loadState(xuid).map(PlayerInventoryState::inventory);
    }

    public Optional<PlayerInventoryState> loadState(String xuid) throws IOException {
        Path file = inventoryPath(xuid);
        rejectSymlink(file);
        if (!Files.exists(file)) return Optional.empty();
        long size = Files.size(file);
        if (size < 12 || size > MAX_FILE_BYTES) {
            throw new IOException("Invalid NEXORA player inventory size");
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
            throw new IOException("NEXORA player inventory checksum mismatch: " + file);
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes, 0, bodyLength))) {
            if (input.readInt() != MAGIC) throw new IOException("Invalid NEXORA player inventory magic");
            int version = input.readUnsignedByte();
            if (version != FORMAT_VERSION && version != CRAFTING_UI_FORMAT_VERSION) {
                throw new IOException("Unsupported NEXORA player inventory version: " + version);
            }
            int playerSize = input.readUnsignedShort();
            if (playerSize < PlayerInventory.HOTBAR_SIZE || playerSize > 256) {
                throw new IOException("Invalid NEXORA player inventory slot count");
            }
            int selectedSlot = input.readUnsignedByte();
            if (selectedSlot >= PlayerInventory.HOTBAR_SIZE || selectedSlot >= playerSize) {
                throw new IOException("Invalid NEXORA selected hotbar slot");
            }
            PlayerInventory inventory = new PlayerInventory(playerSize);
            readSlots(input, playerSize, inventory::setSlot);
            inventory.selectHotbarSlot(selectedSlot);

            ContainerInventory uiInventory = new ContainerInventory(BedrockCraftingUiSlots.SIZE);
            if (version == CRAFTING_UI_FORMAT_VERSION) {
                int uiSize = input.readUnsignedShort();
                if (uiSize != BedrockCraftingUiSlots.SIZE) {
                    throw new IOException("Invalid NEXORA player crafting UI slot count");
                }
                readSlots(input, uiSize, uiInventory::setSlot);
            }
            if (input.available() != 0) throw new IOException("Trailing data in NEXORA player inventory");
            return Optional.of(new PlayerInventoryState(inventory, uiInventory));
        } catch (java.io.EOFException exception) {
            throw new IOException("Truncated NEXORA player inventory", exception);
        }
    }

    private static void readSlots(DataInputStream input, int size, SlotSetter setter) throws IOException {
        for (int slot = 0; slot < size; slot++) {
            if (!input.readBoolean()) continue;
            int idLength = input.readUnsignedByte();
            if (idLength < 3 || idLength > MAX_ITEM_ID_BYTES) {
                throw new IOException("Invalid NEXORA item identifier length");
            }
            byte[] idBytes = new byte[idLength];
            input.readFully(idBytes);
            String itemId;
            try {
                itemId = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(idBytes)).toString();
            } catch (CharacterCodingException exception) {
                throw new IOException("Invalid UTF-8 item identifier in NEXORA inventory", exception);
            }
            int count = input.readUnsignedByte();
            int maxStackSize = input.readUnsignedByte();
            try {
                setter.set(slot, new ItemStack(itemId, count, maxStackSize));
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid item stack in NEXORA inventory", exception);
            }
        }
    }

    @FunctionalInterface
    private interface SlotSetter {
        void set(int slot, ItemStack stack);
    }

    public record PlayerInventoryState(PlayerInventory inventory, ContainerInventory craftingUiInventory) {
        public PlayerInventoryState {
            Objects.requireNonNull(inventory, "inventory");
            Objects.requireNonNull(craftingUiInventory, "craftingUiInventory");
        }
    }

    private Path inventoryPath(String xuid) {
        if (xuid == null || !xuid.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid player XUID");
        }
        return inventoriesDirectory.resolve(xuid + ".nxi");
    }

    private static void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("NEXORA player inventory path must not be a symbolic link: " + path);
        }
    }

    private static final class BedrockCraftingUiSlots {
        private static final int SIZE = 51;
    }
}
