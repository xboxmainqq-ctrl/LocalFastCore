package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Encoder for an empty protocol-2225 ResourcePacksInfo packet. */
public final class BedrockResourcePacksInfoPacketCodec {
    public static final int PACKET_ID = 6;

    private BedrockResourcePacksInfoPacketCodec() {
    }

    /**
     * Emits no behavior/resource packs, no world template, and no forced
     * acceptance. The field order follows the protocol-2168+ packet layout.
     */
    public static ByteBuffer encodeEmptyPayload() {
        ByteBuffer payload = ByteBuffer.allocate(4 + 16 + 1 + 1);
        payload.put((byte) 0); // forcedToAccept
        payload.put((byte) 0); // hasAddonPacks
        payload.put((byte) 0); // scriptingEnabled
        payload.put((byte) 0); // vibrantVisualsForceDisabled
        payload.putLong(0L).putLong(0L); // world template UUID
        BedrockBinary.writeString(payload, "");
        BedrockBinary.writeVarUInt32(payload, 0); // resource pack count
        payload.flip();
        return payload.asReadOnlyBuffer();
    }
}
