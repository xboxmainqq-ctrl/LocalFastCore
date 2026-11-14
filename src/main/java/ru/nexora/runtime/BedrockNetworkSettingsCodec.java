package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Protocol 2225 NetworkSettingsPacket payload codec.
 *
 * <p>The packet ID (143) and enclosing packet-batch framing belong to the
 * outer packet codec. The five fields here use Bedrock's little-endian
 * fixed-width primitive representation.</p>
 */
public final class BedrockNetworkSettingsCodec {
    public static final int PACKET_ID = 143;
    public static final int PAYLOAD_BYTES = 10;

    private BedrockNetworkSettingsCodec() {
    }

    public static BedrockNetworkSettings decodePayload(ByteBuffer payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.remaining() != PAYLOAD_BYTES) {
            throw new IllegalArgumentException("NetworkSettingsPacket payload must be exactly 10 bytes");
        }

        ByteBuffer input = payload.slice().order(ByteOrder.LITTLE_ENDIAN);
        int compressionThreshold = Short.toUnsignedInt(input.getShort());
        int algorithmValue = Short.toUnsignedInt(input.getShort());
        boolean throttleEnabled = input.get() != 0;
        int throttleThreshold = Byte.toUnsignedInt(input.get());
        float throttleScalar = input.getFloat();

        return new BedrockNetworkSettings(
                compressionThreshold,
                BedrockNetworkSettings.CompressionAlgorithm.fromWireValue(algorithmValue),
                throttleEnabled,
                throttleThreshold,
                throttleScalar
        );
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
}
