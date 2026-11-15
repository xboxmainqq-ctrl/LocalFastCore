package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Encoder for an empty protocol-2225 ResourcePackStack packet. */
public final class BedrockResourcePackStackPacketCodec {
    public static final int PACKET_ID = 7;

    private BedrockResourcePackStackPacketCodec() {
    }

    public static ByteBuffer encodeEmptyPayload(String gameVersion) {
        Objects.requireNonNull(gameVersion, "gameVersion");
        if (gameVersion.isBlank() || gameVersion.length() > 32
                || gameVersion.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Game version string is invalid");
        }
        byte[] version = gameVersion.getBytes(StandardCharsets.UTF_8);
        ByteBuffer payload = ByteBuffer.allocate(1 + 1 + 1 + version.length + 1 + 2);
        payload.put((byte) 0); // forcedToAccept
        BedrockBinary.writeVarUInt32(payload, 0); // resource packs
        BedrockBinary.writeString(payload, gameVersion, 32);
        BedrockBinary.writeVarUInt32(payload, 0); // experiments
        payload.put((byte) 0); // experimentsPreviouslyToggled
        payload.put((byte) 0); // hasEditorPacks
        payload.flip();
        return payload.asReadOnlyBuffer();
    }
}
