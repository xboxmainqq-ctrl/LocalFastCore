package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import ru.nexora.inventory.ItemStack;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BedrockItemStackUserDataCodecTest {
    @Test
    void roundTripsEnchantmentListUsingBedrockNbtIds() {
        ItemStack stack = new ItemStack("minecraft:diamond_sword", 1, 1,
                Map.of("minecraft:sharpness", 5, "minecraft:unbreaking", 3), 0);

        byte[] encoded = BedrockItemStackUserDataCodec.encode(stack, new byte[0]);
        var decoded = BedrockItemStackUserDataCodec.decode(encoded, new byte[0]);

        assertEquals(stack.enchantments(), decoded.enchantments());
        assertEquals(0, decoded.repairCost());
        assertEquals(0xff, Byte.toUnsignedInt(encoded[0]));
        assertEquals(0xff, Byte.toUnsignedInt(encoded[1]));
        assertEquals(1, Byte.toUnsignedInt(encoded[2]));
    }

    @Test
    void roundTripsRepairCostWithoutEnchantments() {
        ItemStack stack = new ItemStack("minecraft:diamond_sword", 1, 1, Map.of(), 7);

        var decoded = BedrockItemStackUserDataCodec.decode(
                BedrockItemStackUserDataCodec.encode(stack, new byte[0]), new byte[0]);

        assertTrue(decoded.enchantments().isEmpty());
        assertEquals(7, decoded.repairCost());
    }

    @Test
    void roundTripsAdditionalVanillaEnchantmentIds() {
        ItemStack stack = new ItemStack("minecraft:diamond_helmet", 1, 1,
                Map.of("minecraft:thorns", 3, "minecraft:respiration", 3, "minecraft:aqua_affinity", 1), 0);
        var decoded = BedrockItemStackUserDataCodec.decode(
                BedrockItemStackUserDataCodec.encode(stack, new byte[0]), new byte[0]);
        assertEquals(stack.enchantments(), decoded.enchantments());
    }

    @Test
    void roundTripsQuickChargeSoulSpeedAndSwiftSneakIds() {
        ItemStack crossbow = new ItemStack("minecraft:crossbow", 1, 1,
                Map.of("minecraft:quick_charge", 3), 0);
        ItemStack boots = new ItemStack("minecraft:diamond_boots", 1, 1,
                Map.of("minecraft:soul_speed", 3), 0);
        ItemStack leggings = new ItemStack("minecraft:diamond_leggings", 1, 1,
                Map.of("minecraft:swift_sneak", 3), 0);

        for (ItemStack stack : java.util.List.of(crossbow, boots, leggings)) {
            var decoded = BedrockItemStackUserDataCodec.decode(
                    BedrockItemStackUserDataCodec.encode(stack, new byte[0]), new byte[0]);
            assertEquals(stack.enchantments(), decoded.enchantments());
        }
    }

    @Test
    void roundTripsCursesMaceAndSpearEnchantmentIds() {
        ItemStack helmet = new ItemStack("minecraft:diamond_helmet", 1, 1,
                Map.of("minecraft:binding", 1));
        ItemStack sword = new ItemStack("minecraft:diamond_sword", 1, 1,
                Map.of("minecraft:vanishing", 1));
        ItemStack mace = new ItemStack("minecraft:mace", 1, 1,
                Map.of("minecraft:wind_burst", 3));
        ItemStack densityMace = new ItemStack("minecraft:mace", 1, 1,
                Map.of("minecraft:density", 5));
        ItemStack breachMace = new ItemStack("minecraft:mace", 1, 1,
                Map.of("minecraft:breach", 4));
        ItemStack spear = new ItemStack("minecraft:diamond_spear", 1, 1,
                Map.of("minecraft:lunge", 3));

        for (ItemStack stack : java.util.List.of(helmet, sword, mace, densityMace, breachMace, spear)) {
            var decoded = BedrockItemStackUserDataCodec.decode(
                    BedrockItemStackUserDataCodec.encode(stack, new byte[0]), new byte[0]);
            assertEquals(stack.enchantments(), decoded.enchantments());
        }
    }

    @Test
    void leavesPlainItemUserDataUnchanged() {
        byte[] registered = {1, 2, 3};
        byte[] encoded = BedrockItemStackUserDataCodec.encode(
                new ItemStack("minecraft:stone", 1, 64), registered);
        assertArrayEquals(registered, encoded);
        assertTrue(BedrockItemStackUserDataCodec.decode(encoded, registered).enchantments().isEmpty());
    }

    @Test
    void rejectsUnknownEnchantmentsAndCustomDataCombination() {
        ItemStack unsupported = new ItemStack("minecraft:diamond_sword", 1, 1,
                Map.of("minecraft:unknown_enchantment", 1), 0);
        assertThrows(IllegalArgumentException.class,
                () -> BedrockItemStackUserDataCodec.encode(unsupported, new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockItemStackUserDataCodec.encode(
                        new ItemStack("minecraft:diamond_sword", 1, 1,
                                Map.of("minecraft:sharpness", 1), 0), new byte[] {1}));
    }

    @Test
    void rejectsTruncatedAndMalformedUserData() {
        assertThrows(IllegalArgumentException.class,
                () -> BedrockItemStackUserDataCodec.decode(new byte[] {(byte) 0xff}, new byte[0]));
        byte[] encoded = BedrockItemStackUserDataCodec.encode(
                new ItemStack("minecraft:diamond_sword", 1, 1,
                        Map.of("minecraft:sharpness", 2), 0), new byte[0]);
        encoded[encoded.length - 1] = 1;
        assertThrows(IllegalArgumentException.class,
                () -> BedrockItemStackUserDataCodec.decode(encoded, new byte[0]));
    }
}
