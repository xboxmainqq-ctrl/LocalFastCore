package ru.nexora.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerPhysicsTest {
    private World world() {
        World world = new World("NEXORA", 1L, new FlatWorldGenerator());
        world.getChunk(0, 0);
        return world;
    }

    @Test
    void playerSpawnsGroundedAndRemainsOnGroundWithoutInput() {
        World world = world();
        PlayerPhysics.State state = PlayerPhysics.spawn(world, 0.5, 65.0, 0.5);

        assertTrue(state.onGround());
        for (int i = 0; i < 20; i++) {
            state = PlayerPhysics.tick(world, state, new PlayerPhysics.Input(0, 0, 0, false, false), 0.05);
        }
        assertEquals(65.0, state.y(), 1.0e-6);
        assertTrue(state.onGround());
    }

    @Test
    void jumpRisesThenGravityReturnsPlayerToGround() {
        World world = world();
        PlayerPhysics.State state = PlayerPhysics.spawn(world, 0.5, 65.0, 0.5);
        state = PlayerPhysics.tick(world, state, new PlayerPhysics.Input(0, 0, 0, false, true), 0.05);
        assertTrue(state.y() > 65.0);
        assertFalse(state.onGround());

        for (int i = 0; i < 80; i++) {
            state = PlayerPhysics.tick(world, state, new PlayerPhysics.Input(0, 0, 0, false, false), 0.05);
        }
        assertEquals(65.0, state.y(), 1.0e-5);
        assertTrue(state.onGround());
        assertEquals(0.0, state.velocityY(), 1.0e-9);
    }

    @Test
    void tracksFallDistanceAndEmitsOneTickLandingDistance() {
        World world = world();
        PlayerPhysics.State state = PlayerPhysics.spawn(world, 0.5, 70.0, 0.5);
        double landed = 0;
        for (int i = 0; i < 100 && !state.onGround(); i++) {
            state = PlayerPhysics.tick(world, state,
                    new PlayerPhysics.Input(0, 0, 0, false, false), 0.05);
            if (state.landedDistance() > 0) landed = state.landedDistance();
        }
        assertTrue(state.onGround());
        assertEquals(5.0, landed, 0.02);
        assertEquals(0.0, state.fallDistance(), 1.0e-9);
        PlayerPhysics.State next = PlayerPhysics.tick(world, state,
                new PlayerPhysics.Input(0, 0, 0, false, false), 0.05);
        assertEquals(0.0, next.landedDistance(), 1.0e-9);
    }

    @Test
    void sprintMovesFasterThanWalking() {
        World world = world();
        PlayerPhysics.State walking = PlayerPhysics.spawn(world, 0.5, 65.0, 0.5);
        PlayerPhysics.State sprinting = walking;
        PlayerPhysics.Input walk = new PlayerPhysics.Input(0, 1, 0, false, false);
        PlayerPhysics.Input sprint = new PlayerPhysics.Input(0, 1, 0, true, false);

        for (int i = 0; i < 10; i++) {
            walking = PlayerPhysics.tick(world, walking, walk, 0.05);
            sprinting = PlayerPhysics.tick(world, sprinting, sprint, 0.05);
        }
        assertTrue(sprinting.z() > walking.z());
    }

    @Test
    void horizontalCollisionStopsAtWallWithoutEmbeddingPlayer() {
        World world = world();
        world.setBlock(1, 65, 0, BlockType.STONE);
        world.setBlock(1, 66, 0, BlockType.STONE);
        PlayerPhysics.State state = PlayerPhysics.spawn(world, 0.5, 65.0, 0.5);

        for (int i = 0; i < 30; i++) {
            state = PlayerPhysics.tick(world, state,
                    new PlayerPhysics.Input(0, 1, -90, false, false), 0.05);
        }
        assertTrue(state.x() < 0.71);
        assertTrue(WorldCollision.canOccupy(world, state.x(), state.y(), state.z()));
    }

    @Test
    void stepsOntoSingleBlockButDoesNotClimbTwoBlockWall() {
        World world = world();
        world.setBlock(1, 65, 0, BlockType.STONE);
        PlayerPhysics.State state = PlayerPhysics.spawn(world, 0.5, 65.0, 0.5);
        PlayerPhysics.Input forward = new PlayerPhysics.Input(0, 1, -90, false, false);

        // Stop shortly after climbing; walking for 24 ticks would carry the
        // player off the far edge of this isolated one-block platform.
        for (int i = 0; i < 4; i++) {
            state = PlayerPhysics.tick(world, state, forward, 0.05);
        }

        assertTrue(state.x() > 1.2, "player should walk up a one-block step");
        assertEquals(66.0, state.y(), 0.02);
        assertTrue(state.onGround());
        assertTrue(WorldCollision.canOccupy(world, state.x(), state.y(), state.z()));

        world.setBlock(2, 66, 0, BlockType.STONE);
        world.setBlock(2, 67, 0, BlockType.STONE);
        for (int i = 0; i < 24; i++) {
            state = PlayerPhysics.tick(world, state, forward, 0.05);
        }
        assertTrue(state.x() < 1.71, "player must not climb a two-block wall");
        assertTrue(WorldCollision.canOccupy(world, state.x(), state.y(), state.z()));
    }

    @Test
    void rejectsInvalidDeltaAndSpawnInsideBlock() {
        World world = world();
        assertThrows(IllegalArgumentException.class,
                () -> PlayerPhysics.spawn(world, 0.5, 64.0, 0.5));
        PlayerPhysics.State state = PlayerPhysics.spawn(world, 0.5, 65.0, 0.5);
        assertThrows(IllegalArgumentException.class,
                () -> PlayerPhysics.tick(world, state, new PlayerPhysics.Input(0, 0, 0, false, false), 0.2));
        assertThrows(IllegalArgumentException.class,
                () -> PlayerPhysics.tick(world, state, new PlayerPhysics.Input(2, 0, 0, false, false), 0.05));
    }
}
