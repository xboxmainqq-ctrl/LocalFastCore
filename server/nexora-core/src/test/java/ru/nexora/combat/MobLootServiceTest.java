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
        assertTrue(spider.drops().isEmpty());

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
    void unknownMobsHaveNoLootOrExperience() {
        assertEquals(MobLootService.Loot.EMPTY, MobLootService.dropsFor("minecraft:cow"));
    }
}
