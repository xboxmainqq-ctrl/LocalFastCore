package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BedrockPacketViolationWarningCodecTest {
    @Test
    void decodesViolationTypeSeverityPacketIdAndUtf8Context() {
        byte[] payload = new byte[] {
                0,       // PacketMalformed (zig-zag VarInt32)
                4,       // TerminatingConnection (zig-zag VarInt32(2))
                (byte) 0xC8, 0x01, // packet ID 100 (zig-zag VarInt32)
                8, 'b', 'a', 'd', ' ', 'p', 'a', 'c', 'k'
        };
        var violation = BedrockPacketViolationWarningCodec.decode(
                new BedrockPacket(0x9C, 0, 0, payload));

        assertEquals(0, violation.type());
        assertEquals(2, violation.severity());
        assertEquals(100, violation.packetId());
        assertEquals("bad pack", violation.context());
        assertEquals("PacketMalformed", BedrockPacketViolationWarningCodec.typeName(violation.type()));
        assertEquals("TerminatingConnection",
                BedrockPacketViolationWarningCodec.severityName(violation.severity()));
    }

    @Test
    void decodesNegativeSignedVarIntValuesWithoutLosingBits() {
        byte[] payload = new byte[] {
                1, 0, 0, 0
        };
        var violation = BedrockPacketViolationWarningCodec.decode(
                new BedrockPacket(0x9C, 0, 0, payload));
        assertEquals(-1, violation.type());
    }

    @Test
    void rejectsWrongPacketIdTruncationInvalidUtf8AndTrailingBytes() {
        assertThrows(IllegalArgumentException.class, () ->
                BedrockPacketViolationWarningCodec.decode(new BedrockPacket(0x90, 0, 0, new byte[0])));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockPacketViolationWarningCodec.decode(new BedrockPacket(0x9C, 0, 0, new byte[] {0, 0})));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockPacketViolationWarningCodec.decode(new BedrockPacket(
                        0x9C, 0, 0, new byte[] {0, 0, 0, 1, (byte) 0xFF})));
        assertThrows(IllegalArgumentException.class, () ->
                BedrockPacketViolationWarningCodec.decode(new BedrockPacket(
                        0x9C, 0, 0, new byte[] {0, 0, 0, 0, 1})));
    }

    @Test
    void rejectsOversizedContext() {
        assertThrows(IllegalArgumentException.class, () ->
                BedrockPacketViolationWarningCodec.decode(new BedrockPacket(
                        0x9C, 0, 0, new byte[] {0, 0, 0, (byte) 0x81, 0x20})));
    }
}
