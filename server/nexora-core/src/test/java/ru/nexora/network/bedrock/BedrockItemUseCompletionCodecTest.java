package ru.nexora.network.bedrock;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class BedrockItemUseCompletionCodecTest {
    @Test
    void encodesCompletedUsingItemWithRuntimeIdAndUseMethod() {
        BedrockPacket packet = BedrockItemUseCompletionCodec.create(261);
        assertEquals(0x8e, packet.packetId());
        ByteBuffer payload = ByteBuffer.wrap(packet.payload()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(261, payload.getShort());
        assertEquals(BedrockItemUseCompletionCodec.ITEM_USE_METHOD_USE, payload.getInt());
        assertFalse(payload.hasRemaining());
    }

    @Test
    void rejectsItemIdsOutsideSignedShortRange() {
        assertThrows(IllegalArgumentException.class, () -> BedrockItemUseCompletionCodec.create(40_000));
    }
}
