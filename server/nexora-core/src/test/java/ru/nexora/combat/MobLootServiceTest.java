package ru.nexora.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MobLootServiceTest {
    @Test
    void returnsExpectedDropsAndExperienceForSupportedHostiles() {
        var zombie = MobLootService.dropsFor("minecraft:zombie");
        assertEquals(5_000, zombie.experienceMilliPoints());
        assertEquals("minecraft:rotten_flesh", zombie.drops().getFirst().itemId());

        var spider = MobLootService.dropsFor("minecraft:spider");
        assertEquals(5_000, spider.experienceMilliPoints());
        assertEquals("minecraft:string", spider.drops().getFirst().itemId());

        var skeleton = MobLootService.dropsFor("minecraft:skeleton");
        assertEquals(1, skeleton.drops().size());
        assertEquals("minecraft:bone", skeleton.drops().getFirst().itemId());
        assertEquals(5_000, skeleton.experienceMilliPoints());

        var endermite = MobLootService.dropsFor("minecraft:endermite");
        assertTrue(endermite.drops().isEmpty());
        assertEquals(3_000, endermite.experienceMilliPoints());
    }

    @Test
    void lootingBonusIncreasesEligibleDropStackWithinVanillaLevelCap() {
        var base = MobLootService.dropsFor("minecraft:zombie");
        var looting = MobLootService.dropsFor("minecraft:zombie", 3, 2);
        assertEquals(1, base.drops().getFirst().count());
        assertEquals(3, looting.drops().getFirst().count());
        assertEquals(base.experienceMilliPoints(), looting.experienceMilliPoints());
        assertThrows(IllegalArgumentException.class,
                () -> MobLootService.dropsFor("minecraft:zombie", 4, 0));
        assertThrows(IllegalArgumentException.class,
                () -> MobLootService.dropsFor("minecraft:zombie", 2, 3));
    }

    @Test
    void supportedMobFamiliesHaveTheirExpectedLoot() {
        assertEquals("minecraft:gunpowder",
                MobLootService.dropsFor("minecraft:creeper").drops().getFirst().itemId());
        assertEquals("minecraft:ender_pearl",
                MobLootService.dropsFor("minecraft:enderman").drops().getFirst().itemId());
        assertEquals("minecraft:beef",
                MobLootService.dropsFor("minecraft:cow").drops().getFirst().itemId());
        assertEquals("minecraft:totem_of_undying",
                MobLootService.dropsFor("minecraft:evoker").drops().getFirst().itemId());
    }

    @Test
    void bossMobsHaveTheirSignatureRewards() {
        var wither = MobLootService.dropsFor("minecraft:wither");
        assertEquals("minecraft:nether_star", wither.drops().getFirst().itemId());
        assertEquals(50_000, wither.experienceMilliPoints());

        var dragon = MobLootService.dropsFor("minecraft:ender_dragon");
        assertTrue(dragon.drops().isEmpty());
        assertEquals(100_000, dragon.experienceMilliPoints());
    }

    @Test
    void unknownMobsHaveNoLootOrExperience() {
        assertEquals(MobLootService.Loot.EMPTY, MobLootService.dropsFor("minecraft:unknown_mob"));
    }
}
