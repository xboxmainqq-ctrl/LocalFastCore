package ru.nexora.network.bedrock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Protocol-2225 CompletedUsingItem response. */
public final class BedrockItemUseCompletionCodec {
    public static final int PACKET_ID = 0x8e;
    public static final int ITEM_USE_METHOD_USE = 0;

    private BedrockItemUseCompletionCodec() {
    }

    public static BedrockPacket create(int runtimeItemId) {
        if (runtimeItemId <= Short.MIN_VALUE || runtimeItemId > Short.MAX_VALUE) {
            throw new IllegalArgumentException("Item runtime ID does not fit the protocol int16 field");
        }
        byte[] payload = ByteBuffer.allocate(Short.BYTES + Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) runtimeItemId)
                .putInt(ITEM_USE_METHOD_USE)
                .array();
        return new BedrockPacket(PACKET_ID, 0, 0, payload);
    }
}
