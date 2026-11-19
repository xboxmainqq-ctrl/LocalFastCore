package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class BedrockAvailableCommandsCodecTest {
    @Test
    void advertisesEquipmentAndAiCommandsWithoutArguments() {
        BedrockPacket packet = BedrockAvailableCommandsCodec.createEquipmentCommands();
        assertEquals(0x4c, packet.packetId());
        Reader in = new Reader(packet.payload());
        assertEquals(0, in.varUInt32()); // enum values
        assertEquals(0, in.varUInt32()); // chained subcommand values
        assertEquals(0, in.varUInt32()); // postfixes
        assertEquals(0, in.varUInt32()); // enum data
        assertEquals(0, in.varUInt32()); // chained subcommand data

        assertEquals(2, in.varUInt32()); // commands
        assertCommand(in, "equipment", "Open the NEXORA magical equipment menu");
        assertCommand(in, "ai", "Open the NEXORA AI assistant");
        assertEquals(0, in.varUInt32()); // soft enums
        assertEquals(0, in.varUInt32()); // constraints
        in.requireExhausted();
    }

    private static void assertCommand(Reader in, String name, String description) {
        assertEquals(name, in.string());
        assertEquals(description, in.string());
        assertEquals(0, in.uint16());
        assertEquals("Any", in.string());
        assertEquals(-1, in.int32());
        assertEquals(0, in.varUInt32());
        assertEquals(1, in.varUInt32());
        assertFalse(in.bool());
        assertEquals(0, in.varUInt32());
    }

    private static final class Reader {
        private final ByteBuffer buffer;

        Reader(byte[] payload) {
            buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        }

        int varUInt32() {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int current = uint8();
                value |= (current & 0x7f) << shift;
                if ((current & 0x80) == 0) return value;
            }
            throw new AssertionError("Invalid VarUInt32");
        }

        String string() {
            int length = varUInt32();
            byte[] bytes = new byte[length];
            buffer.get(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }

        int uint8() {
            return Byte.toUnsignedInt(buffer.get());
        }

        int uint16() {
            return Short.toUnsignedInt(buffer.getShort());
        }

        int int32() {
            return buffer.getInt();
        }

        boolean bool() {
            return uint8() != 0;
        }

        void requireExhausted() {
            assertFalse(buffer.hasRemaining());
        }
    }
}
