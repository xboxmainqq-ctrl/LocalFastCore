package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class BedrockStartGamePrimitivesCodecTest {
    @Test
    void roundTripsPositionAndRotationVectorsInNativeOrder() {
        ByteBuffer position = ByteBuffer.allocate(12);
        BedrockStartGamePrimitivesCodec.Vec3 expectedPosition =
                new BedrockStartGamePrimitivesCodec.Vec3(12.5f, 64.0f, -3.25f);
        BedrockStartGamePrimitivesCodec.writeVec3(position, expectedPosition);
        position.flip();
        assertEquals(expectedPosition, BedrockStartGamePrimitivesCodec.readVec3(position));
        assertFalse(position.hasRemaining());

        ByteBuffer rotation = ByteBuffer.allocate(8);
        BedrockStartGamePrimitivesCodec.Vec2 expectedRotation =
                new BedrockStartGamePrimitivesCodec.Vec2(90.0f, -45.5f);
        BedrockStartGamePrimitivesCodec.writeVec2(rotation, expectedRotation);
        rotation.flip();
        assertEquals(expectedRotation, BedrockStartGamePrimitivesCodec.readVec2(rotation));
        assertFalse(rotation.hasRemaining());
    }

    @Test
    void roundTripsSignedBlockPositionCoordinates() {
        ByteBuffer buffer = ByteBuffer.allocate(15);
        BedrockStartGamePrimitivesCodec.BlockPosition expected =
                new BedrockStartGamePrimitivesCodec.BlockPosition(-30_000_000, -64, 30_000_000);
        BedrockStartGamePrimitivesCodec.writeBlockPosition(buffer, expected);
        buffer.flip();
        assertEquals(expected, BedrockStartGamePrimitivesCodec.readBlockPosition(buffer));
        assertFalse(buffer.hasRemaining());
    }

    @Test
    void roundTripsMovementSettingsAndAcceptsNonzeroBoolean() {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        BedrockStartGamePrimitivesCodec.SyncedPlayerMovementSettings expected =
                new BedrockStartGamePrimitivesCodec.SyncedPlayerMovementSettings(64, true);
        BedrockStartGamePrimitivesCodec.writeMovementSettings(buffer, expected);
        buffer.flip();
        assertEquals(expected, BedrockStartGamePrimitivesCodec.readMovementSettings(buffer));

        ByteBuffer nonCanonicalTrue = ByteBuffer.wrap(new byte[] {0, 2});
        assertEquals(new BedrockStartGamePrimitivesCodec.SyncedPlayerMovementSettings(0, true),
                BedrockStartGamePrimitivesCodec.readMovementSettings(nonCanonicalTrue));
    }

    @Test
    void rejectsNonFiniteVectorsNegativeRewindAndTruncatedSettings() {
        assertThrows(IllegalArgumentException.class,
                () -> new BedrockStartGamePrimitivesCodec.Vec3(Float.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new BedrockStartGamePrimitivesCodec.Vec2(0, Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> new BedrockStartGamePrimitivesCodec.SyncedPlayerMovementSettings(-1, false));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockStartGamePrimitivesCodec.readMovementSettings(ByteBuffer.wrap(new byte[] {0})));
    }
}
