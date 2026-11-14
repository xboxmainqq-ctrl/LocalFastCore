package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class BedrockResourcePackPacketCodecTest {
    @Test
    void encodesEmptyResourcePacksInfoAndStack() {
        ByteBuffer info = BedrockResourcePacksInfoPacketCodec.encodeEmptyPayload();
        assertEquals(22, info.remaining());
        assertEquals(0, info.get() & 0xff);
        assertEquals(0, info.get() & 0xff);
        assertEquals(0, info.get() & 0xff);
        assertEquals(0, info.get() & 0xff);
        for (int i = 0; i < 16; i++) assertEquals(0, info.get() & 0xff);
        assertEquals(0, BedrockBinary.readVarUInt32(info));
        assertEquals(0, BedrockBinary.readVarUInt32(info));
        assertFalse(info.hasRemaining());

        ByteBuffer stack = BedrockResourcePackStackPacketCodec.encodeEmptyPayload("1.26.60");
        assertEquals(0, stack.get() & 0xff);
        assertEquals(0, BedrockBinary.readVarUInt32(stack));
        assertEquals("1.26.60", BedrockBinary.readString(stack, 32));
        assertEquals(0, BedrockBinary.readVarUInt32(stack));
        assertEquals(0, stack.get() & 0xff);
        assertEquals(0, stack.get() & 0xff);
        assertFalse(stack.hasRemaining());
    }

    @Test
    void decodesHaveAllPacksAndCompletedResponses() {
        BedrockResourcePackClientResponse haveAll = decodeResponse(2, "downloadingfinished");
        assertEquals(BedrockResourcePackClientResponse.Status.HAVE_ALL_PACKS, haveAll.status());
        assertTrue(haveAll.packIds().isEmpty());

        BedrockResourcePackClientResponse completed =
                decodeResponse(3, "resourcepackstackfinished");
        assertEquals(BedrockResourcePackClientResponse.Status.COMPLETED, completed.status());
        assertTrue(completed.packIds().isEmpty());
    }

    @Test
    void decodesRequestedPackIdsAndRejectsMismatchedStatusName() {
        ByteBuffer payload = ByteBuffer.allocate(64);
        BedrockBinary.writeVarUInt32(payload, 1);
        BedrockBinary.writeString(payload, "downloading");
        BedrockBinary.writeVarUInt32(payload, 2);
        BedrockBinary.writeString(payload, "pack-one");
        BedrockBinary.writeString(payload, "pack-two");
        payload.flip();

        BedrockResourcePackClientResponse response =
                BedrockResourcePackClientResponsePacketCodec.decodePayload(payload);
        assertEquals(BedrockResourcePackClientResponse.Status.SEND_PACKS, response.status());
        assertEquals(java.util.List.of("pack-one", "pack-two"), response.packIds());

        ByteBuffer invalid = ByteBuffer.allocate(32);
        BedrockBinary.writeVarUInt32(invalid, 2);
        BedrockBinary.writeString(invalid, "downloading");
        invalid.flip();
        assertThrows(IllegalArgumentException.class,
                () -> BedrockResourcePackClientResponsePacketCodec.decodePayload(invalid));
    }

    private static BedrockResourcePackClientResponse decodeResponse(int status, String name) {
        ByteBuffer payload = ByteBuffer.allocate(64);
        BedrockBinary.writeVarUInt32(payload, status);
        BedrockBinary.writeString(payload, name);
        payload.flip();
        return BedrockResourcePackClientResponsePacketCodec.decodePayload(payload);
    }
}
