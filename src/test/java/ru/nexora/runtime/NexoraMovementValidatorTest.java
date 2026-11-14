package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NexoraMovementValidatorTest {
    private final NexoraMovementValidator validator = new NexoraMovementValidator();

    private InMemoryWorld createWorld() {
        InMemoryWorld world = new InMemoryWorld("world", new WorldHeight(0, 128));
        FlatChunkGenerator generator = new FlatChunkGenerator(64, 5,
                new BlockState("minecraft:grass_block"),
                new BlockState("minecraft:dirt"),
                new BlockState("minecraft:bedrock"));
        assertTrue(world.loadChunk(generator.generate(new ChunkPosition(0, 0), world.height(), 7L)));
        return world;
    }

    @Test
    void acceptsSmallMovementIntoLoadedAir() {
        InMemoryWorld world = createWorld();
        NexoraMovementValidator.Position requested =
                new NexoraMovementValidator.Position(3.0, 65.0, 2.0);

        NexoraMovementValidator.Result result = validator.validate(
                world, new NexoraMovementValidator.Position(2.0, 65.0, 2.0), requested);

        assertTrue(result.accepted());
        assertEquals(requested, result.position());
    }

    @Test
    void rejectsMovementThroughSolidBlock() {
        InMemoryWorld world = createWorld();
        world.setBlock(new BlockPosition(3, 66, 2), new BlockState("minecraft:stone"));

        NexoraMovementValidator.Result result = validator.validate(
                world, new NexoraMovementValidator.Position(2.0, 65.0, 2.0),
                new NexoraMovementValidator.Position(3.0, 65.0, 2.0));

        assertFalse(result.accepted());
        assertEquals(NexoraMovementValidator.Reason.BLOCKED_OR_UNLOADED, result.reason());
    }

    @Test
    void rejectsTeleportSizedMovement() {
        InMemoryWorld world = createWorld();

        NexoraMovementValidator.Result result = validator.validate(
                world, new NexoraMovementValidator.Position(2.0, 65.0, 2.0),
                new NexoraMovementValidator.Position(20.0, 65.0, 2.0));

        assertFalse(result.accepted());
        assertEquals(NexoraMovementValidator.Reason.MOVEMENT_TOO_FAST, result.reason());
    }

    @Test
    void rejectsNonFiniteCoordinates() {
        InMemoryWorld world = createWorld();

        NexoraMovementValidator.Result result = validator.validate(
                world, new NexoraMovementValidator.Position(2.0, 65.0, 2.0),
                new NexoraMovementValidator.Position(Double.NaN, 65.0, 2.0));

        assertFalse(result.accepted());
        assertEquals(NexoraMovementValidator.Reason.NON_FINITE, result.reason());
    }
}
