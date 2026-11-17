package ru.nexora.combat;

import org.junit.jupiter.api.Test;
import ru.nexora.combat.SpearChargeCombatService.Vector;
import ru.nexora.inventory.ItemStack;

import static org.junit.jupiter.api.Assertions.*;

class SpearChargeCombatServiceTest {

    private static final Vector FORWARD = new Vector(1, 0, 0);
    private static final Vector STILL = new Vector(0, 0, 0);

    @Test
    void chargeDamageUsesTierMultiplierAndServerMeasuredClosingSpeed() {
        ItemStack diamond = spear("diamond");
        var effect = SpearChargeCombatService.resolve(
                diamond, new Vector(6, 0, 0), STILL, FORWARD);

        assertTrue(effect.hit());
        assertEquals(6.45f, effect.damage(), 0.0001f);
        assertTrue(effect.knockback());
        assertEquals(6.0, effect.closingSpeed(), 0.0001);
    }

    @Test
    void allSevenSpearTiersHaveExplicitChargeDamageValues() {
        String[] tiers = {
                "wooden", "golden", "stone", "copper", "iron", "diamond", "netherite"
        };
        float[] expectedDamage = {
                4.20f, 4.20f, 4.92f, 4.92f, 5.70f, 6.45f, 7.20f
        };
        double[] expectedMultipliers = {
                0.70, 0.70, 0.82, 0.82, 0.95, 1.075, 1.20
        };
        float[] expectedJabDamage = {
                2.0f, 2.0f, 3.0f, 3.0f, 4.0f, 5.0f, 6.0f
        };
        long[] expectedCooldownNanos = {
                650_000_000L, 950_000_000L, 750_000_000L, 850_000_000L,
                950_000_000L, 1_050_000_000L, 1_150_000_000L
        };

        for (int i = 0; i < tiers.length; i++) {
            ItemStack weapon = spear(tiers[i]);
            var profile = SpearCombatStats.forItem(weapon).orElseThrow();
            assertEquals(expectedJabDamage[i], profile.jabDamage(), 0.0001f,
                    tiers[i] + " spear jab damage");
            assertEquals(expectedCooldownNanos[i], profile.jabCooldownNanos(),
                    tiers[i] + " spear cooldown");
            assertTrue(profile.isWithinReach(2.0 * 2.0),
                    tiers[i] + " spear should reach exactly 2 blocks");
            assertTrue(profile.isWithinReach(4.5 * 4.5),
                    tiers[i] + " spear should reach exactly 4.5 blocks");
            assertFalse(profile.isWithinReach(1.99 * 1.99),
                    tiers[i] + " spear should reject targets inside minimum reach");
            assertFalse(profile.isWithinReach(4.51 * 4.51),
                    tiers[i] + " spear should reject targets outside maximum reach");
            assertEquals(expectedMultipliers[i], SpearChargeCombatService.multiplier(weapon), 0.0001,
                    tiers[i] + " spear multiplier");
            var effect = SpearChargeCombatService.resolve(
                    weapon, new Vector(6, 0, 0), STILL, FORWARD);
            assertEquals(expectedDamage[i], effect.damage(), 0.0001f,
                    tiers[i] + " spear charge damage at 6 blocks/sec");
            assertTrue(effect.hit(), tiers[i] + " spear should cross the damage threshold");
            assertTrue(effect.knockback(), tiers[i] + " spear should cross the knockback threshold");
        }
    }

    @Test
    void targetMovingTowardAttackerContributesToClosingSpeed() {
        ItemStack iron = spear("iron");
        var effect = SpearChargeCombatService.resolve(
                iron, new Vector(2, 0, 0), new Vector(-3, 0, 0), FORWARD);

        assertEquals(4.75f, effect.damage(), 0.0001f);
        assertFalse(effect.knockback());
        assertEquals(5.0, effect.closingSpeed(), 0.0001);
    }

    @Test
    void targetRunningAwayReducesClosingSpeedAndCanCancelDamage() {
        var effect = SpearChargeCombatService.resolve(
                spear("netherite"), new Vector(6, 0, 0), new Vector(2, 0, 0), FORWARD);

        assertFalse(effect.hit());
        assertEquals(0.0f, effect.damage());
        assertEquals(4.0, effect.closingSpeed(), 0.0001);
    }

    @Test
    void damageAndKnockbackHaveSeparateSpeedThresholds() {
        var noDamage = SpearChargeCombatService.resolve(
                spear("wooden"), new Vector(4.5, 0, 0), STILL, FORWARD);
        assertFalse(noDamage.hit());

        var damageOnly = SpearChargeCombatService.resolve(
                spear("wooden"), new Vector(5.0, 0, 0), STILL, FORWARD);
        assertTrue(damageOnly.hit());
        assertFalse(damageOnly.knockback());

        var damageAndKnockback = SpearChargeCombatService.resolve(
                spear("wooden"), new Vector(5.1, 0, 0), STILL, FORWARD);
        assertTrue(damageAndKnockback.knockback());
    }

    @Test
    void onlySpearItemsCanProduceChargeDamage() {
        var sword = SpearChargeCombatService.resolve(
                new ItemStack("minecraft:diamond_sword", 1, 1),
                new Vector(8, 0, 0), STILL, FORWARD);
        assertFalse(sword.hit());
        assertEquals(0.0f, sword.damage());
    }

    @Test
    void viewDirectionIsNormalizedAndZeroDirectionIsRejected() {
        var effect = SpearChargeCombatService.resolve(
                spear("stone"), new Vector(6, 0, 0), STILL, new Vector(2, 0, 0));
        assertEquals(4.92f, effect.damage(), 0.0001f);

        assertThrows(IllegalArgumentException.class, () ->
                SpearChargeCombatService.resolve(spear("stone"), STILL, STILL, STILL));
    }

    private static ItemStack spear(String tier) {
        return new ItemStack("minecraft:" + tier + "_spear", 1, 1);
    }
}
