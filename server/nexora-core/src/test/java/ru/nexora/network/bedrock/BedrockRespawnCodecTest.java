package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class BedrockRespawnCodecTest {
    @Test
    void encodesDeathInfoAndSearchingForSpawnHandshake() {
        BedrockPacket death = BedrockRespawnCodec.createDeathInfo("death.attack.fall");
        assertEquals(BedrockRespawnCodec.DEATH_INFO_PACKET_ID, death.packetId());
        Reader deathReader = new Reader(death.payload());
        assertEquals("death.attack.fall", deathReader.string());
        assertEquals(0, deathReader.varUInt32());
        assertTrue(deathReader.exhausted());

        BedrockPacket respawn = BedrockRespawnCodec.createSearchingForSpawn(0.5, 65, -0.5, 1234);
        assertEquals(BedrockRespawnCodec.RESPAWN_PACKET_ID, respawn.packetId());
        Reader respawnReader = new Reader(respawn.payload());
        assertEquals(0.5f, respawnReader.float32());
        assertEquals(65.0f, respawnReader.float32());
        assertEquals(-0.5f, respawnReader.float32());
        assertEquals(0, respawnReader.byteValue());
        assertEquals(1234, respawnReader.varUInt64());
        assertTrue(respawnReader.exhausted());
    }

    @Test
    void encodesClientPositionResetForRespawnMode() {
        BedrockPacket move = BedrockRespawnCodec.createRespawnMove(
                1234, 0.5, 65, -0.5, 0, 90, true, 300);
        assertEquals(BedrockRespawnCodec.MOVE_PLAYER_PACKET_ID, move.packetId());
        Reader in = new Reader(move.payload());
        assertEquals(1234, in.varUInt64());
        assertEquals(0.5f, in.float32());
        assertEquals(65.0f, in.float32());
        assertEquals(-0.5f, in.float32());
        assertEquals(0.0f, in.float32());
        assertEquals(90.0f, in.float32());
        assertEquals(90.0f, in.float32());
        assertEquals(BedrockRespawnCodec.POSITION_MODE_RESPAWN, in.byteValue());
        assertEquals(1, in.byteValue());
        assertEquals(0, in.varUInt64());
        assertEquals(300, in.varUInt64());
        assertTrue(in.exhausted());
    }

    @Test
    void rejectsInvalidDeathAndRespawnValues() {
        assertThrows(IllegalArgumentException.class, () -> BedrockRespawnCodec.createDeathInfo("bad key"));
        assertThrows(IllegalArgumentException.class, () -> BedrockRespawnCodec.createSearchingForSpawn(
                Double.NaN, 65, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> BedrockRespawnCodec.createRespawnMove(
                0, 0, 65, 0, 0, 0, true, 0));
    }

    private static final class Reader {
        private final ByteBuffer buffer;
        Reader(byte[] bytes) { buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }
        float float32() { return buffer.getFloat(); }
        int byteValue() { return Byte.toUnsignedInt(buffer.get()); }
        long varUInt64() {
            long value = 0;
            for (int i = 0; i < 10; i++) {
                int next = Byte.toUnsignedInt(buffer.get());
                value |= (long) (next & 0x7f) << (7 * i);
                if ((next & 0x80) == 0) return value;
            }
            throw new AssertionError("invalid varuint64");
        }
        int varUInt32() { return (int) varUInt64(); }
        String string() {
            int length = varUInt32();
            byte[] bytes = new byte[length];
            buffer.get(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
        boolean exhausted() { return !buffer.hasRemaining(); }
    }
}
