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

        boolean movedSideways = false;
        for (int i = 0; i < 80; i++) {
            engine.tick();
            WorldEntity current = entities.find(mobId).orElseThrow();
            movedSideways |= Math.abs(current.z() - 0.5) > 0.1;
        }

        WorldEntity mob = entities.find(mobId).orElseThrow();
        assertTrue(mob.x() > 1.0, "mob should make progress beyond the blocked direct route");
        assertTrue(movedSideways, "mob should move sideways to follow a path around the obstacle");
        assertEquals(MobAiController.State.CHASE,
                engine.mobAi().lastDecision(mobId).orElseThrow().state());
    }

    @Test
    void hostileMobUsesLastKnownTargetToNavigateAfterLineOfSightBreaks() {
        InMemoryWorld world = groundedWorld();
        EntityRegistry entities = new EntityRegistry();
        UUID mobId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        entities.spawn(new WorldEntity(mobId, WorldEntity.Type.MOB, "Zombie", 0.5, 64, 0.5, 0, -1));
        entities.spawn(WorldEntity.player(playerId, "Player", 5.5, 64, 0.5, 180, 0));
        EntityTickEngine engine = new EntityTickEngine(entities, world);
        engine.mobAi().setBehavior(mobId, MobAiController.Behavior.HOSTILE);

        engine.tick();
        world.setBlock(new BlockPosition(2, 64, 0), new BlockState("minecraft:stone"));
        world.setBlock(new BlockPosition(2, 65, 0), new BlockState("minecraft:stone"));
        engine.tick();
        MobAiController.Decision rememberedDecision = engine.mobAi().lastDecision(mobId).orElseThrow();
        assertEquals(MobAiController.State.CHASE, rememberedDecision.state(),
                "mob should continue toward the last known position after losing sight");
        assertFalse(rememberedDecision.attackRequested(),
                "a remembered but unseen player must not be attacked");

        boolean movedSideways = false;
        for (int i = 0; i < 80; i++) {
            engine.tick();
            WorldEntity current = entities.find(mobId).orElseThrow();
            movedSideways |= Math.abs(current.z() - 0.5) > 0.1;
        }

        WorldEntity mob = entities.find(mobId).orElseThrow();
        assertTrue(movedSideways, "mob should route around the wall using its last known target");
        assertTrue(mob.x() > 1.0, "mob should continue toward the last known target position");
    }

    @Test
    void hostileMobForgetsLastKnownTargetAfterMemoryWindowExpires() {
        InMemoryWorld world = groundedWorld();
        EntityRegistry entities = new EntityRegistry();
        UUID mobId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        entities.spawn(new WorldEntity(mobId, WorldEntity.Type.MOB, "Zombie", 0.5, 64, 0.5, 0, -1));
        entities.spawn(WorldEntity.player(playerId, "Player", 5.5, 64, 0.5, 180, 0));
        EntityTickEngine engine = new EntityTickEngine(entities, world);
        engine.mobAi().setBehavior(mobId, MobAiController.Behavior.HOSTILE);

        engine.tick();
        for (int x : new int[]{-1, 1}) {
            world.setBlock(new BlockPosition(x, 64, 0), new BlockState("minecraft:stone"));
            world.setBlock(new BlockPosition(x, 65, 0), new BlockState("minecraft:stone"));
        }
        for (int z : new int[]{-1, 1}) {
            world.setBlock(new BlockPosition(0, 64, z), new BlockState("minecraft:stone"));
            world.setBlock(new BlockPosition(0, 65, z), new BlockState("minecraft:stone"));
        }
        for (int i = 0; i < 101; i++) {
            engine.tick();
        }

        assertEquals(MobAiController.State.IDLE,
                engine.mobAi().lastDecision(mobId).orElseThrow().state());
    }

    @Test
    void mobPathfindingUsesSharedPerTickBudgetAndDefersRemainingMobs() {
        InMemoryWorld world = groundedWorld();
        EntityRegistry entities = new EntityRegistry();
        UUID playerId = UUID.randomUUID();
        entities.spawn(WorldEntity.player(playerId, "Player", 5.5, 64, 0.5, 180, 0));
        EntityTickEngine engine = new EntityTickEngine(entities, world);
        UUID[] mobIds = new UUID[9];
        for (int i = 0; i < mobIds.length; i++) {
            UUID mobId = UUID.randomUUID();
            mobIds[i] = mobId;
            entities.spawn(new WorldEntity(mobId, WorldEntity.Type.MOB, "Zombie",
                    0.5, 64, -3.5 + i, 0, -1));
            engine.mobAi().setBehavior(mobId, MobAiController.Behavior.HOSTILE);
        }

        engine.tick();

        long waitingAfterFirstTick = java.util.Arrays.stream(mobIds)
                .map(id -> engine.mobAi().lastDecision(id).orElseThrow())
                .filter(decision -> decision.state() == MobAiController.State.WAITING)
                .count();
        assertEquals(1, waitingAfterFirstTick,
                "the shared path budget should defer a ninth new route in the same tick");

        engine.tick();

        long waitingAfterSecondTick = java.util.Arrays.stream(mobIds)
                .map(id -> engine.mobAi().lastDecision(id).orElseThrow())
                .filter(decision -> decision.state() == MobAiController.State.WAITING)
                .count();
        assertEquals(0, waitingAfterSecondTick,
                "cached routes should let the deferred mob receive the next tick's budget");
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

        engine.tick();

        assertEquals(MobAiController.State.WAITING,
                engine.mobAi().lastDecision(mobId).orElseThrow().state(),
                "a mob with an unreachable route should respect the retry backoff");
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
