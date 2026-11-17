package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class BedrockTextCodecTest {
    @Test void createsSystemTextPacket() {
        String message = "§b[NEXORA AI] §fПривет";
        BedrockPacket packet = BedrockTextCodec.createSystemMessage(message);
        assertEquals(BedrockTextCodec.TEXT_PACKET_ID, packet.packetId());
        ByteBuffer in = ByteBuffer.wrap(packet.payload());
        assertEquals(6, Byte.toUnsignedInt(in.get()));
        assertEquals(0, Byte.toUnsignedInt(in.get()));
        assertEquals(0, Byte.toUnsignedInt(in.get()));
        int length = Byte.toUnsignedInt(in.get());
        byte[] bytes = new byte[length];
        in.get(bytes);
        assertEquals(message, new String(bytes, StandardCharsets.UTF_8));
    }
    @Test void rejectsBlankAndOversizedMessages() {
        assertThrows(IllegalArgumentException.class, () -> BedrockTextCodec.createSystemMessage(" "));
        assertThrows(IllegalArgumentException.class, () -> BedrockTextCodec.createSystemMessage("x".repeat(4097)));
    }
}
