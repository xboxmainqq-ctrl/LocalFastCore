package ru.nexora.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldLivingEntityManagerTest {
    @Test
    void spawnsTracksDamagesAndRemovesLivingActors() {
        WorldLivingEntityManager manager = new WorldLivingEntityManager();
        WorldLivingEntity zombie = manager.spawn(
                "minecraft:zombie", WorldLivingEntity.Family.UNDEAD, 20.0f, 4.5, 65, -2.5);

        assertTrue(zombie.uniqueId() < 0);
        assertTrue(zombie.runtimeId() > 0);
        assertEquals(1, manager.size());
        assertEquals(zombie, manager.find(zombie.runtimeId()).orElseThrow());

        var hit = manager.damage(zombie.runtimeId(), 5.0f).orElseThrow();
        assertEquals(5.0f, hit.appliedDamage(), 0.0001f);
        assertFalse(hit.killed());
        assertEquals(15.0f, manager.find(zombie.runtimeId()).orElseThrow().health(), 0.0001f);

        var fatal = manager.damage(zombie.runtimeId(), 100.0f).orElseThrow();
        assertTrue(fatal.killed());
        assertEquals(15.0f, fatal.appliedDamage(), 0.0001f);
        assertTrue(manager.find(zombie.runtimeId()).isEmpty());
        assertEquals(0, manager.size());
        assertTrue(manager.damage(zombie.runtimeId(), 1.0f).isEmpty());
    }

    @Test
    void infersUndeadAndArthropodFamiliesForCombatEnchantments() {
        assertEquals(WorldLivingEntity.Family.UNDEAD,
                WorldLivingEntity.familyForIdentifier("minecraft:zombie"));
        assertEquals(WorldLivingEntity.Family.UNDEAD,
                WorldLivingEntity.familyForIdentifier("minecraft:wither_skeleton"));
        assertEquals(WorldLivingEntity.Family.ARTHROPOD,
                WorldLivingEntity.familyForIdentifier("minecraft:cave_spider"));
        assertEquals(WorldLivingEntity.Family.ARTHROPOD,
                WorldLivingEntity.familyForIdentifier("minecraft:bee"));
        assertEquals(WorldLivingEntity.Family.NORMAL,
                WorldLivingEntity.familyForIdentifier("minecraft:creeper"));
    }

    @Test
    void fireTicksDamageLivingActorAndClearAtExpiry() {
        WorldLivingEntityManager manager = new WorldLivingEntityManager();
        WorldLivingEntity spider = manager.spawn(
                "minecraft:spider", WorldLivingEntity.Family.ARTHROPOD, 16.0f, 0, 65, 0);
        manager.ignite(spider.runtimeId(), 4, 0).orElseThrow();

        assertEquals(1, manager.tickFire(1_000).size());
        assertEquals(15.0f, manager.find(spider.runtimeId()).orElseThrow().health(), 0.0001f);
        assertEquals(1, manager.tickFire(2_000).size());
        assertEquals(14.0f, manager.find(spider.runtimeId()).orElseThrow().health(), 0.0001f);
        assertEquals(1, manager.tickFire(3_000).size());
        assertEquals(13.0f, manager.find(spider.runtimeId()).orElseThrow().health(), 0.0001f);
        assertTrue(manager.tickFire(4_000).isEmpty());
        assertEquals(0, manager.find(spider.runtimeId()).orElseThrow().fireUntilMillis());
    }

    @Test
    void movesLivingActorWithoutChangingHealthOrCombatState() {
        WorldLivingEntityManager manager = new WorldLivingEntityManager();
        WorldLivingEntity zombie = manager.spawn("minecraft:zombie", 20, 1, 65, 2);
        manager.ignite(zombie.runtimeId(), 5, 100).orElseThrow();

        WorldLivingEntity moved = manager.move(zombie.runtimeId(), 1.25, 65, 2.5).orElseThrow();
        assertEquals(1.25, moved.x(), 0.0001);
        assertEquals(65.0, moved.y(), 0.0001);
        assertEquals(2.5, moved.z(), 0.0001);
        assertEquals(20.0f, moved.health(), 0.0001f);
        assertEquals(5_100L, moved.fireUntilMillis());
        assertTrue(manager.move(999_999, 0, 65, 0).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> manager.move(zombie.runtimeId(), Double.NaN, 65, 0));
    }

    @Test
    void validatesSpawnAndDamageInputs() {
        WorldLivingEntityManager manager = new WorldLivingEntityManager();
        assertThrows(IllegalArgumentException.class, () -> manager.spawn(
                "zombie", WorldLivingEntity.Family.UNDEAD, 20, 0, 65, 0));
        assertThrows(IllegalArgumentException.class, () -> manager.spawn(
                "minecraft:zombie", WorldLivingEntity.Family.UNDEAD, Float.NaN, 0, 65, 0));
        assertThrows(IllegalArgumentException.class, () -> manager.damage(1, Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> manager.setVelocity(1, 101, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> manager.tickFire(-1));
    }
}
