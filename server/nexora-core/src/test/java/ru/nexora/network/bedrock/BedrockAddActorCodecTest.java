package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import ru.nexora.world.WorldLivingEntity;
import ru.nexora.world.WorldLivingEntityManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class BedrockAddActorCodecTest {
    @Test
    void encodesProtocol2225AddActorWithHealthAttributeAndEmptyOptionalSections() {
        WorldLivingEntity entity = new WorldLivingEntityManager().spawn(
                "minecraft:zombie", WorldLivingEntity.Family.UNDEAD, 20.0f, 12.5, 65.0, -4.5);

        BedrockPacket packet = BedrockAddActorCodec.createAddActor(entity);
        assertEquals(BedrockAddActorCodec.ADD_ACTOR_PACKET_ID, packet.packetId());

        Reader in = new Reader(packet.payload());
        assertEquals(entity.uniqueId(), in.varInt64());
        assertEquals(entity.runtimeId(), in.varUInt64());
        assertEquals("minecraft:zombie", in.string());
        assertEquals(12.5f, in.float32(), 0.0001f);
        assertEquals(65.0f, in.float32(), 0.0001f);
        assertEquals(-4.5f, in.float32(), 0.0001f);
        for (int i = 0; i < 7; i++) assertEquals(0.0f, in.float32(), 0.0001f);

        assertEquals(1, in.varUInt32());
        assertEquals("minecraft:health", in.string());
        assertEquals(0.0f, in.float32(), 0.0001f);
        assertEquals(20.0f, in.float32(), 0.0001f);
        assertEquals(20.0f, in.float32(), 0.0001f);
        assertEquals(0, in.varUInt32()); // actor data
        assertEquals(0, in.varUInt32()); // integer properties
        assertEquals(0, in.varUInt32()); // float properties
        assertEquals(0, in.varUInt32()); // actor links
        assertEquals(0, in.byteValue()); // no passenger block data
        in.requireExhausted();
    }

    private static final class Reader {
        private final ByteBuffer buffer;

        Reader(byte[] bytes) {
            buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        }

        int byteValue() {
            if (!buffer.hasRemaining()) throw new IllegalArgumentException("Unexpected end of AddActor packet");
            return Byte.toUnsignedInt(buffer.get());
        }

        float float32() {
            if (buffer.remaining() < Float.BYTES) throw new IllegalArgumentException("Truncated AddActor float");
            return buffer.getFloat();
        }

        long varUInt64() {
            long value = 0;
            for (int i = 0; i < 10; i++) {
                int next = byteValue();
                if (i == 9 && (next & 0xfe) != 0) throw new IllegalArgumentException("VarUInt64 overflow");
                value |= (long) (next & 0x7f) << (i * 7);
                if ((next & 0x80) == 0) return value;
            }
            throw new IllegalArgumentException("VarUInt64 too long");
        }

        long varInt64() {
            long value = varUInt64();
            return (value >>> 1) ^ -(value & 1);
        }

        long varUInt32() {
            return varUInt64();
        }

        String string() {
            int length = Math.toIntExact(varUInt32());
            if (length < 0 || buffer.remaining() < length) throw new IllegalArgumentException("Truncated string");
            byte[] bytes = new byte[length];
            buffer.get(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }

        void requireExhausted() {
            assertEquals(0, buffer.remaining());
        }
    }
}
