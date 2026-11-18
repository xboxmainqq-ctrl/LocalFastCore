package ru.nexora.persistence;

import ru.nexora.inventory.ItemStack;
import ru.nexora.inventory.PlayerEquipment;
import ru.nexora.world.PlayerData;
import ru.nexora.world.PlayerVitals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Explicit, bounded binary codec for the canonical player-state bundle. */
public final class PlayerStateBundleCodec {
    private static final int MAGIC = 0x4e585042; // NXPB
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_PAYLOAD_BYTES = 4 * 1024 * 1024;
    private static final int MAX_TEXT_BYTES = 512;
    private static final int MAX_ITEM_ID_BYTES = 128;
    private static final int MAX_ENCHANTMENTS = 32;

    public byte[] encode(PlayerStateBundle bundle) throws IOException {
        if (bundle == null) throw new NullPointerException("bundle");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(2048);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeInt(SCHEMA_VERSION);
            writeProfile(out, bundle.profile());
            out.writeShort(bundle.inventorySlots().size());
            out.writeByte(bundle.selectedHotbarSlot());
            writeSlots(out, bundle.inventorySlots());
            out.writeShort(bundle.craftingUiSlots().size());
            writeSlots(out, bundle.craftingUiSlots());
            writeEquipment(out, bundle.equipment());
        }
        if (bytes.size() > MAX_PAYLOAD_BYTES) {
            throw new IOException("Player state bundle exceeds payload limit");
        }
        return bytes.toByteArray();
    }

    public PlayerStateBundle decode(byte[] payload) throws IOException {
        if (payload == null) throw new NullPointerException("payload");
        if (payload.length < 16 || payload.length > MAX_PAYLOAD_BYTES) {
            throw new IOException("Invalid player state bundle size");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (in.readInt() != MAGIC) throw new IOException("Invalid player state bundle magic");
            int version = in.readInt();
            if (version != SCHEMA_VERSION) throw new IOException("Unsupported player state bundle schema: " + version);
            PlayerData profile = readProfile(in);
            int inventorySize = in.readUnsignedShort();
            if (inventorySize < 9 || inventorySize > 256) throw new IOException("Invalid player inventory size");
            int selectedSlot = in.readUnsignedByte();
            if (selectedSlot >= 9 || selectedSlot >= inventorySize) throw new IOException("Invalid selected hotbar slot");
            List<Optional<ItemStack>> inventory = readSlots(in, inventorySize);
            int craftingSize = in.readUnsignedShort();
            if (craftingSize != PlayerStateBundle.CRAFTING_UI_SLOT_COUNT) {
                throw new IOException("Invalid player crafting UI slot count");
            }
            List<Optional<ItemStack>> crafting = readSlots(in, craftingSize);
            PlayerEquipment.Snapshot equipment = readEquipment(in);
            if (in.available() != 0) throw new IOException("Trailing data in player state bundle");
            return new PlayerStateBundle(profile, inventory, selectedSlot, crafting, equipment);
        } catch (EOFException exception) {
            throw new IOException("Truncated player state bundle", exception);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid player state bundle values", exception);
        }
    }

    private static void writeProfile(DataOutputStream out, PlayerData p) throws IOException {
        writeText(out, p.xuid(), 64);
        writeText(out, p.displayName(), 128);
        writeText(out, p.worldName(), 128);
        out.writeFloat(p.x()); out.writeFloat(p.y()); out.writeFloat(p.z());
        out.writeFloat(p.yaw()); out.writeFloat(p.pitch());
        out.writeLong(p.lastSeenEpochMillis());
        out.writeFloat(p.vitals().health());
        out.writeInt(p.vitals().hunger());
        out.writeFloat(p.vitals().saturation());
        out.writeFloat(p.vitals().exhaustion());
        out.writeInt(p.experiencePoints());
        out.writeShort(p.experienceRemainderMilli());
    }

    private static PlayerData readProfile(DataInputStream in) throws IOException {
        String xuid = readText(in, 64);
        String name = readText(in, 128);
        String world = readText(in, 128);
        float x = in.readFloat(), y = in.readFloat(), z = in.readFloat();
        float yaw = in.readFloat(), pitch = in.readFloat();
        long lastSeen = in.readLong();
        PlayerVitals vitals = new PlayerVitals(in.readFloat(), in.readInt(), in.readFloat(), in.readFloat());
        int experience = in.readInt();
        int remainder = in.readUnsignedShort();
        return new PlayerData(xuid, name, world, x, y, z, yaw, pitch,
                lastSeen, vitals, experience, remainder);
    }

    private static void writeSlots(DataOutputStream out, List<Optional<ItemStack>> slots) throws IOException {
        for (Optional<ItemStack> optional : slots) {
            out.writeBoolean(optional.isPresent());
            if (optional.isEmpty()) continue;
            writeStack(out, optional.orElseThrow());
        }
    }

    private static List<Optional<ItemStack>> readSlots(DataInputStream in, int count) throws IOException {
        List<Optional<ItemStack>> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(in.readBoolean() ? Optional.of(readStack(in)) : Optional.empty());
        }
        return List.copyOf(result);
    }

    private static void writeEquipment(DataOutputStream out, PlayerEquipment.Snapshot equipment)
            throws IOException {
        writeSlots(out, equipment.armor());
        writeOptionalStack(out, equipment.offhand());
        writeOptionalStack(out, equipment.cursor());
        writeSlots(out, equipment.magicSlots());
    }

    private static PlayerEquipment.Snapshot readEquipment(DataInputStream in) throws IOException {
        List<Optional<ItemStack>> armor = readSlots(in, PlayerEquipment.ARMOR_SLOTS);
        Optional<ItemStack> offhand = readOptionalStack(in);
        Optional<ItemStack> cursor = readOptionalStack(in);
        List<Optional<ItemStack>> magic = readSlots(in, PlayerEquipment.MAGIC_SLOTS);
        return new PlayerEquipment.Snapshot(armor, offhand, cursor, magic);
    }

    private static void writeOptionalStack(DataOutputStream out, Optional<ItemStack> stack)
            throws IOException {
        out.writeBoolean(stack.isPresent());
        if (stack.isPresent()) writeStack(out, stack.orElseThrow());
    }

    private static Optional<ItemStack> readOptionalStack(DataInputStream in) throws IOException {
        return in.readBoolean() ? Optional.of(readStack(in)) : Optional.empty();
    }

    private static void writeStack(DataOutputStream out, ItemStack stack) throws IOException {
        writeText(out, stack.itemId(), MAX_ITEM_ID_BYTES);
        out.writeByte(stack.count());
        out.writeByte(stack.maxStackSize());
        out.writeByte(stack.enchantments().size());
        for (Map.Entry<String, Integer> enchantment : stack.enchantments().entrySet()) {
            writeText(out, enchantment.getKey(), MAX_ITEM_ID_BYTES);
            out.writeByte(enchantment.getValue());
        }
        out.writeByte(stack.repairCost());
    }

    private static ItemStack readStack(DataInputStream in) throws IOException {
        String itemId = readText(in, MAX_ITEM_ID_BYTES);
        int count = in.readUnsignedByte();
        int maxStack = in.readUnsignedByte();
        int enchantmentCount = in.readUnsignedByte();
        if (enchantmentCount > MAX_ENCHANTMENTS) throw new IOException("Too many item enchantments");
        Map<String, Integer> enchantments = new LinkedHashMap<>();
        for (int i = 0; i < enchantmentCount; i++) {
            String id = readText(in, MAX_ITEM_ID_BYTES);
            int level = in.readUnsignedByte();
            if (enchantments.putIfAbsent(id, level) != null) {
                throw new IOException("Duplicate item enchantment");
            }
        }
        int repairCost = in.readUnsignedByte();
        try {
            return new ItemStack(itemId, count, maxStack, enchantments, repairCost);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid item stack in player state bundle", exception);
        }
    }

    private static void writeText(DataOutputStream out, String value, int maxBytes) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length == 0 || encoded.length > maxBytes || encoded.length > MAX_TEXT_BYTES) {
            throw new IOException("Player state text field exceeds size limit");
        }
        out.writeShort(encoded.length);
        out.write(encoded);
    }

    private static String readText(DataInputStream in, int maxBytes) throws IOException {
        int length = in.readUnsignedShort();
        if (length == 0 || length > maxBytes || length > MAX_TEXT_BYTES) {
            throw new IOException("Invalid player state text length");
        }
        byte[] encoded = new byte[length];
        in.readFully(encoded);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(encoded)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("Invalid UTF-8 in player state bundle", exception);
        }
    }
}
