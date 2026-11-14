package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Protocol 2225 NetworkSettingsPacket payload codec (packet ID 143). */
public final class BedrockNetworkSettingsPacketCodec {
    public static final int PACKET_ID = 143;
    public static final int PAYLOAD_BYTES = 10;

    private BedrockNetworkSettingsPacketCodec() {
    }

    public static ByteBuffer encodePayload(BedrockNetworkSettings settings) {
        Objects.requireNonNull(settings, "settings");
        ByteBuffer output = ByteBuffer.allocate(PAYLOAD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        output.putShort((short) settings.compressionThreshold());
        output.putShort((short) settings.compressionAlgorithm().wireValue());
        output.put((byte) (settings.clientThrottleEnabled() ? 1 : 0));
        output.put((byte) settings.clientThrottleThreshold());
        output.putFloat(settings.clientThrottleScalar());
        output.flip();
        return output.asReadOnlyBuffer();
    }

    public static BedrockNetworkSettings decodePayload(ByteBuffer payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.remaining() != PAYLOAD_BYTES) {
            throw new IllegalArgumentException("NetworkSettingsPacket payload must be exactly 10 bytes");
        }
        ByteBuffer input = payload.slice().order(ByteOrder.LITTLE_ENDIAN);
        int compressionThreshold = Short.toUnsignedInt(input.getShort());
        int algorithmId = Short.toUnsignedInt(input.getShort());
        boolean throttleEnabled = input.get() != 0;
        int throttleThreshold = Byte.toUnsignedInt(input.get());
        float throttleScalar = input.getFloat();
        BedrockNetworkSettings.CompressionAlgorithm algorithm =
                BedrockNetworkSettings.CompressionAlgorithm.fromWireValue(algorithmId);
        payload.position(payload.position() + PAYLOAD_BYTES);
        return new BedrockNetworkSettings(
                compressionThreshold, algorithm, throttleEnabled, throttleThreshold, throttleScalar
        );
    }
}
