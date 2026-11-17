package ru.nexora.network.bedrock;

import ru.nexora.inventory.ItemStack;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Protocol-2225 ItemInstanceUserData adapter for NEXORA enchantments and anvil repair cost. */
public final class BedrockItemStackUserDataCodec {
    private static final Map<String, Integer> ENCHANTMENT_IDS = Map.ofEntries(
            Map.entry("minecraft:protection", 0), Map.entry("minecraft:fire_protection", 1),
            Map.entry("minecraft:feather_falling", 2), Map.entry("minecraft:blast_protection", 3),
            Map.entry("minecraft:projectile_protection", 4), Map.entry("minecraft:thorns", 5),
            Map.entry("minecraft:respiration", 6), Map.entry("minecraft:depth_strider", 7),
            Map.entry("minecraft:aqua_affinity", 8),
            Map.entry("minecraft:sharpness", 9), Map.entry("minecraft:smite", 10),
            Map.entry("minecraft:bane_of_arthropods", 11), Map.entry("minecraft:knockback", 12),
            Map.entry("minecraft:fire_aspect", 13), Map.entry("minecraft:looting", 14),
            Map.entry("minecraft:efficiency", 15),
            Map.entry("minecraft:silk_touch", 16), Map.entry("minecraft:unbreaking", 17),
            Map.entry("minecraft:fortune", 18), Map.entry("minecraft:power", 19),
            Map.entry("minecraft:punch", 20), Map.entry("minecraft:flame", 21),
            Map.entry("minecraft:infinity", 22), Map.entry("minecraft:luck_of_the_sea", 23),
            Map.entry("minecraft:lure", 24), Map.entry("minecraft:frost_walker", 25),
            Map.entry("minecraft:mending", 26), Map.entry("minecraft:impaling", 29),
            Map.entry("minecraft:riptide", 30),
            Map.entry("minecraft:loyalty", 31), Map.entry("minecraft:channeling", 32),
            Map.entry("minecraft:multishot", 33), Map.entry("minecraft:piercing", 34),
            Map.entry("minecraft:quick_charge", 35), Map.entry("minecraft:soul_speed", 36),
            Map.entry("minecraft:swift_sneak", 37)
    );
    private static final Map<Integer, String> ENCHANTMENTS_BY_ID;
    static {
        Map<Integer, String> reverse = new LinkedHashMap<>();
        ENCHANTMENT_IDS.forEach((name, id) -> reverse.put(id, name));
        ENCHANTMENTS_BY_ID = Map.copyOf(reverse);
    }

    private BedrockItemStackUserDataCodec() { }

    public static byte[] encode(ItemStack stack, byte[] registeredUserData) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(registeredUserData, "registeredUserData");
        if (stack.enchantments().isEmpty() && stack.repairCost() == 0 && stack.durabilityDamage() == 0) return registeredUserData.clone();
        if (!isEmptyNetworkNbt(registeredUserData)) {
            throw new IllegalArgumentException("Cannot combine NEXORA metadata with custom Bedrock item user data yet");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        writeShort(out, 0xffff);
        out.write(1); // ItemInstanceUserData network NBT format version
        out.write(10); // unnamed root compound
        writeShort(out, 0);

        if (!stack.enchantments().isEmpty()) {
            out.write(9); // ListTag
            writeString(out, "ench");
            out.write(10); // CompoundTag elements
            writeInt(out, stack.enchantments().size());
            for (var enchantment : stack.enchantments().entrySet()) {
                Integer id = ENCHANTMENT_IDS.get(enchantment.getKey());
                if (id == null) throw new IllegalArgumentException("Unsupported Bedrock enchantment: " + enchantment.getKey());
                out.write(2); // ShortTag id
                writeString(out, "id");
                writeShort(out, id);
                out.write(2); // ShortTag lvl
                writeString(out, "lvl");
                writeShort(out, enchantment.getValue());
                out.write(0); // end enchantment compound
            }
        }
        if (stack.repairCost() > 0) {
            out.write(3); // IntTag RepairCost
            writeString(out, "RepairCost");
            writeInt(out, stack.repairCost());
        }
        if (stack.durabilityDamage() > 0) {
            out.write(3); // IntTag Damage
            writeString(out, "Damage");
            writeInt(out, stack.durabilityDamage());
        }
        out.write(0); // end root compound
        writeInt(out, 0); // canPlaceOn
        writeInt(out, 0); // canDestroy
        return out.toByteArray();
    }

    public static DecodedMetadata decode(byte[] userData, byte[] registeredUserData) {
        Objects.requireNonNull(userData, "userData");
        Objects.requireNonNull(registeredUserData, "registeredUserData");
        if (java.util.Arrays.equals(userData, registeredUserData)) return new DecodedMetadata(Map.of(), 0, 0);
        if (!isEmptyNetworkNbt(registeredUserData)) throw new IllegalArgumentException("Unexpected custom Bedrock item user data");

        Reader in = new Reader(userData);
        if (in.unsignedShort() != 0xffff || in.unsignedByte() != 1
                || in.unsignedByte() != 10 || in.unsignedShort() != 0) {
            throw new IllegalArgumentException("Unsupported Bedrock item user-data header");
        }
        Map<String, Integer> enchantments = new LinkedHashMap<>();
        int repairCost = 0;
        int durabilityDamage = 0;
        boolean sawRepairCost = false;
        boolean sawDamage = false;
        int tagType;
        while ((tagType = in.unsignedByte()) != 0) {
            String tagName = in.string();
            if (tagType == 9 && tagName.equals("ench")) {
                if (!enchantments.isEmpty()) throw new IllegalArgumentException("Duplicate enchantment list");
                if (in.unsignedByte() != 10) throw new IllegalArgumentException("Invalid enchantment list element type");
                int count = in.int32();
                if (count < 1 || count > 32) throw new IllegalArgumentException("Invalid enchantment list size");
                for (int i = 0; i < count; i++) {
                    if (in.unsignedByte() != 2 || !in.string().equals("id")) throw new IllegalArgumentException("Invalid enchantment id tag");
                    int id = in.unsignedShort();
                    if (in.unsignedByte() != 2 || !in.string().equals("lvl")) throw new IllegalArgumentException("Invalid enchantment level tag");
                    int level = in.unsignedShort();
                    if (in.unsignedByte() != 0) throw new IllegalArgumentException("Invalid enchantment compound terminator");
                    String name = ENCHANTMENTS_BY_ID.get(id);
                    if (name == null || level < 1 || level > 10 || enchantments.putIfAbsent(name, level) != null) {
                        throw new IllegalArgumentException("Unknown, invalid or duplicate Bedrock enchantment");
                    }
                }
            } else if (tagType == 3 && tagName.equals("RepairCost")) {
                if (sawRepairCost) throw new IllegalArgumentException("Duplicate RepairCost tag");
                repairCost = in.int32();
                if (repairCost < 0 || repairCost > 31) throw new IllegalArgumentException("Invalid Bedrock RepairCost");
                sawRepairCost = true;
            } else if (tagType == 3 && tagName.equals("Damage")) {
                if (sawDamage) throw new IllegalArgumentException("Duplicate Damage tag");
                durabilityDamage = in.int32();
                if (durabilityDamage < 0 || durabilityDamage > 65_535) {
                    throw new IllegalArgumentException("Invalid Bedrock item Damage");
                }
                sawDamage = true;
            } else {
                throw new IllegalArgumentException("Unsupported Bedrock item NBT tag: " + tagName);
            }
        }
        if (enchantments.isEmpty() && repairCost == 0 && durabilityDamage == 0) {
            throw new IllegalArgumentException("No supported item metadata");
        }
        if (in.int32() != 0 || in.int32() != 0 || !in.exhausted()) {
            throw new IllegalArgumentException("Unsupported item placement/destroy user data");
        }
        return new DecodedMetadata(enchantments, repairCost, durabilityDamage);
    }

    public record DecodedMetadata(Map<String, Integer> enchantments, int repairCost, int durabilityDamage) {
        public DecodedMetadata {
            enchantments = Map.copyOf(Objects.requireNonNull(enchantments, "enchantments"));
            if (repairCost < 0 || repairCost > 31) throw new IllegalArgumentException("Invalid repair cost");
            if (durabilityDamage < 0 || durabilityDamage > 65_535) throw new IllegalArgumentException("Invalid durability damage");
        }
    }

    private static boolean isEmptyNetworkNbt(byte[] userData) {
        return userData.length == 0
                || java.util.Arrays.equals(userData, new byte[] {10, 0, 0, 0});
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }
    private static void writeInt(ByteArrayOutputStream out, int value) {
        for (int i = 0; i < 4; i++) out.write((value >>> (i * 8)) & 0xff);
    }
    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeShort(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static final class Reader {
        private final ByteBuffer buffer;
        Reader(byte[] bytes) {
            if (bytes.length > 65_536) throw new IllegalArgumentException("Item user data exceeds size limit");
            buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        }
        int unsignedByte() { require(1); return Byte.toUnsignedInt(buffer.get()); }
        int unsignedShort() { require(2); return Short.toUnsignedInt(buffer.getShort()); }
        int int32() { require(4); return buffer.getInt(); }
        String string() {
            int length = unsignedShort();
            require(length);
            byte[] bytes = new byte[length];
            buffer.get(bytes);
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
            } catch (java.nio.charset.CharacterCodingException exception) {
                throw new IllegalArgumentException("Invalid UTF-8 in item user data", exception);
            }
        }
        boolean exhausted() { return !buffer.hasRemaining(); }
        private void require(int length) {
            if (length < 0 || buffer.remaining() < length) throw new IllegalArgumentException("Truncated item user data");
        }
    }
}
