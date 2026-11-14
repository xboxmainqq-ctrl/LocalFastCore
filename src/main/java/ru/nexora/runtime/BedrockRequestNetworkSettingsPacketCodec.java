package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Protocol 2225 RequestNetworkSettingsPacket payload codec.
 *
 * <p>Packet ID 193 and outer packet-batch framing are handled by the
 * packet-envelope layer. The payload is exactly one big-endian int32.</p>
 */
public final class BedrockRequestNetworkSettingsPacketCodec {
    public static final int PACKET_ID = 193;
    public static final int PAYLOAD_BYTES = Integer.BYTES;

    private BedrockRequestNetworkSettingsPacketCodec() {
    }

    public static BedrockRequestNetworkSettingsPacket decodePayload(ByteBuffer payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.remaining() != PAYLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "RequestNetworkSettingsPacket payload must be exactly 4 bytes"
            );
        }
        int version = BedrockBinary.readBigEndianInt32(payload);
        return new BedrockRequestNetworkSettingsPacket(version);
    }

    public static ByteBuffer encodePayload(BedrockRequestNetworkSettingsPacket packet) {
        Objects.requireNonNull(packet, "packet");
        ByteBuffer output = ByteBuffer.allocate(PAYLOAD_BYTES);
        BedrockBinary.writeBigEndianInt32(output, packet.clientNetworkVersion());
        output.flip();
        return output.asReadOnlyBuffer();
    }
}
