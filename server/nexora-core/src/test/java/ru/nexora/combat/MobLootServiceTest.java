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
        assertEquals(2, skeleton.drops().size());
        assertEquals(5_000, skeleton.experienceMilliPoints());

        var endermite = MobLootService.dropsFor("minecraft:endermite");
        assertTrue(endermite.drops().isEmpty());
        assertEquals(3_000, endermite.experienceMilliPoints());
    }

    @Test
    void unknownMobsHaveNoLootOrExperience() {
        assertEquals(MobLootService.Loot.EMPTY, MobLootService.dropsFor("minecraft:cow"));
    }
}
