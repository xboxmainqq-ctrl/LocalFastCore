package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class BedrockTextCodecTest {
    @Test
    void createsProtocol2225SystemTextPacket() {
        String message = "§b[NEXORA AI] §fПривет";
        BedrockPacket packet = BedrockTextCodec.createSystemMessage(message);
        assertEquals(BedrockTextCodec.TEXT_PACKET_ID, packet.packetId());

        ByteBuffer in = ByteBuffer.wrap(packet.payload());
        assertEquals(0, Byte.toUnsignedInt(in.get())); // Localize
        assertEquals(0, Byte.toUnsignedInt(in.get())); // MessageOnly body
        assertEquals(6, Byte.toUnsignedInt(in.get())); // System type
        assertEquals(message, readString(in));
        assertEquals("", readString(in)); // XUID
        assertEquals("", readString(in)); // Platform ID
        assertEquals(0, Byte.toUnsignedInt(in.get())); // No filtered message
        assertFalse(in.hasRemaining());
    }

    @Test
    void createsAndDecodesProtocol2225ChatPacket() {
        BedrockPacket packet = BedrockTextCodec.createChatMessage(
                "PlayerOne", "Привет мир", "", "");
        var decoded = BedrockTextCodec.decodeChat(packet);
        assertEquals("PlayerOne", decoded.sourceName());
        assertEquals("Привет мир", decoded.message());
        assertEquals("", decoded.xuid());
        assertEquals("", decoded.platformChatId());
        assertEquals("", decoded.filteredMessage());
    }

    @Test
    void rejectsNonChatAndMalformedTextPackets() {
        BedrockPacket system = BedrockTextCodec.createSystemMessage("system");
        assertThrows(IllegalArgumentException.class, () -> BedrockTextCodec.decodeChat(system));
        BedrockPacket truncated = new BedrockPacket(
                BedrockTextCodec.TEXT_PACKET_ID, 0, 0, new byte[] {0, 1, 1});
        assertThrows(IllegalArgumentException.class, () -> BedrockTextCodec.decodeChat(truncated));
    }

    @Test
    void rejectsBlankAndOversizedMessages() {
        assertThrows(IllegalArgumentException.class, () -> BedrockTextCodec.createSystemMessage(" "));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockTextCodec.createSystemMessage("x".repeat(4097)));
        assertThrows(IllegalArgumentException.class,
                () -> BedrockTextCodec.createChatMessage("Player", "x".repeat(513), "", ""));
    }

    private static String readString(ByteBuffer in) {
        int length = Byte.toUnsignedInt(in.get());
        byte[] bytes = new byte[length];
        in.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
