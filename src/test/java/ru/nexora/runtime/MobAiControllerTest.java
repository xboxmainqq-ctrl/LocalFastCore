package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MobAiControllerTest {
    @Test
    void hostileMobPerceivesNearestPlayerAndChasesOnServerTicks() {
        InMemoryWorld world = groundedWorld();
        EntityRegistry entities = new EntityRegistry();
        UUID mobId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        entities.spawn(new WorldEntity(mobId, WorldEntity.Type.MOB, "Zombie", 0.5, 64, 0.5, 0, -1));
        entities.spawn(WorldEntity.player(playerId, "Player", 5.5, 64, 0.5, 180, 0));
        EntityTickEngine engine = new EntityTickEngine(entities, world);
        engine.mobAi().setBehavior(mobId, MobAiController.Behavior.HOSTILE);

        for (int i = 0; i < 20; i++) {
            engine.tick();
        }

        WorldEntity mob = entities.find(mobId).orElseThrow();
        assertTrue(mob.x() > 0.5);
        MobAiController.Decision decision = engine.mobAi().lastDecision(mobId).orElseThrow();
        assertEquals(MobAiController.State.CHASE, decision.state());
        assertEquals(playerId, decision.targetId());
    }

    @Test
    void explicitPassiveProfileKeepsExistingWanderGoal() {
        InMemoryWorld world = groundedWorld();
        EntityRegistry entities = new EntityRegistry();
        UUID mobId = UUID.randomUUID();
        entities.spawn(new WorldEntity(mobId, WorldEntity.Type.MOB, "Sheep", 0.5, 64, 0.5, 0, -1));
        EntityTickEngine engine = new EntityTickEngine(entities, world);
        engine.mobAi().setBehavior(mobId, MobAiController.Behavior.PASSIVE);

        for (int i = 0; i < 100; i++) {
            engine.tick();
        }

        WorldEntity mob = entities.find(mobId).orElseThrow();
        assertTrue(Math.abs(mob.x() - 0.5) > 0.001 || Math.abs(mob.z() - 0.5) > 0.001);
    }

    @Test
    void neutralMobDoesNotChaseUntilProvoked() {
        InMemoryWorld world = groundedWorld();
        EntityRegistry entities = new EntityRegistry();
        UUID mobId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        entities.spawn(new WorldEntity(mobId, WorldEntity.Type.MOB, "Wolf", 0.5, 64, 0.5, 0, -1));
        entities.spawn(WorldEntity.player(playerId, "Player", 4.5, 64, 0.5, 180, 0));
        EntityTickEngine engine = new EntityTickEngine(entities, world);
        engine.mobAi().setBehavior(mobId, MobAiController.Behavior.NEUTRAL);

        for (int i = 0; i < 5; i++) {
            engine.tick();
        }
        assertEquals(0.5, entities.find(mobId).orElseThrow().x(), 0.0001);

        engine.mobAi().provoke(mobId, playerId);
        engine.tick();

        assertTrue(entities.find(mobId).orElseThrow().x() > 0.5);
    }

    @Test
    void hostileMobDoesNotSeePlayerThroughSolidWall() {
        InMemoryWorld world = groundedWorld();
        for (int y = 64; y <= 65; y++) {
            world.setBlock(new BlockPosition(2, y, 0), new BlockState("minecraft:stone"));
        }
        EntityRegistry entities = new EntityRegistry();
        UUID mobId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        entities.spawn(new WorldEntity(mobId, WorldEntity.Type.MOB, "Zombie", 0.5, 64, 0.5, 0, -1));
        entities.spawn(WorldEntity.player(playerId, "Player", 4.5, 64, 0.5, 180, 0));
        EntityTickEngine engine = new EntityTickEngine(entities, world);
        engine.mobAi().setBehavior(mobId, MobAiController.Behavior.HOSTILE);

        engine.tick();

        assertEquals(0.5, entities.find(mobId).orElseThrow().x(), 0.0001);
        assertEquals(MobAiController.State.IDLE,
                engine.mobAi().lastDecision(mobId).orElseThrow().state());
    }

    @Test
    void hostileMobFindsLoadedGroundRouteAroundSingleBlockObstacle() {
        InMemoryWorld world = groundedWorld();
        world.setBlock(new BlockPosition(1, 64, 0), new BlockState("minecraft:stone"));
        EntityRegistry entities = new EntityRegistry();
        UUID mobId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        entities.spawn(new WorldEntity(mobId, WorldEntity.Type.MOB, "Zombie", 0.5, 64, 0.5, 0, -1));
        entities.spawn(WorldEntity.player(playerId, "Player", 4.5, 64, 0.5, 180, 0));
        EntityTickEngine engine = new EntityTickEngine(entities, world);
        engine.mobAi().setBehavior(mobId, MobAiController.Behavior.HOSTILE);

        for (int i = 0; i < 80; i++) {
            engine.tick();
        }

        WorldEntity mob = entities.find(mobId).orElseThrow();
        assertTrue(mob.x() > 1.0, "mob should make progress beyond the blocked direct route");
        assertTrue(Math.abs(mob.z() - 0.5) > 0.1,
                "mob should move sideways to follow a path around the obstacle");
        assertEquals(MobAiController.State.CHASE,
                engine.mobAi().lastDecision(mobId).orElseThrow().state());
    }

    @Test
    void hostileMobReportsStuckWhenEveryAdjacentGroundCellIsBlocked() {
        InMemoryWorld world = groundedWorld();
        world.setBlock(new BlockPosition(1, 64, 0), new BlockState("minecraft:stone"));
        world.setBlock(new BlockPosition(-1, 64, 0), new BlockState("minecraft:stone"));
        world.setBlock(new BlockPosition(0, 64, 1), new BlockState("minecraft:stone"));
        world.setBlock(new BlockPosition(0, 64, -1), new BlockState("minecraft:stone"));
        EntityRegistry entities = new EntityRegistry();
        UUID mobId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        entities.spawn(new WorldEntity(mobId, WorldEntity.Type.MOB, "Zombie", 0.5, 64, 0.5, 0, -1));
        entities.spawn(WorldEntity.player(playerId, "Player", 4.5, 64, 0.5, 180, 0));
        EntityTickEngine engine = new EntityTickEngine(entities, world);
        engine.mobAi().setBehavior(mobId, MobAiController.Behavior.HOSTILE);

        engine.tick();

        assertEquals(MobAiController.State.STUCK,
                engine.mobAi().lastDecision(mobId).orElseThrow().state());
        assertEquals(0.5, entities.find(mobId).orElseThrow().x(), 0.0001);
    }

    @Test
    void rejectsInvalidProfilesAndOnlyNeutralMobsCanBeProvoked() {
        MobAiController controller = new MobAiController();
        UUID mobId = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> controller.provoke(mobId, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> new MobAiController(0, 1, 0.1));
    }

    private static InMemoryWorld groundedWorld() {
        InMemoryWorld world = new InMemoryWorld("overworld", new WorldHeight(0, 128));
        for (int x = -4; x <= 12; x++) {
            for (int z = -4; z <= 4; z++) {
                world.setBlock(new BlockPosition(x, 63, z), new BlockState("minecraft:grass_block"));
            }
        }
        return world;
    }
}
