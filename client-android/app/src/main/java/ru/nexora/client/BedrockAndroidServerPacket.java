package ru.nexora.client;

/** One decoded Bedrock packet from a server packet batch. */
public record BedrockAndroidServerPacket(
        int packetId,
        int senderSubClientId,
        int targetSubClientId,
        byte[] payload
) {
    public BedrockAndroidServerPacket {
        if (packetId < 0 || packetId > 0x3ff
                || senderSubClientId < 0 || senderSubClientId > 3
                || targetSubClientId < 0 || targetSubClientId > 3) {
            throw new IllegalArgumentException("Invalid Bedrock packet header");
        }
        if (payload == null) {
            throw new NullPointerException("payload");
        }
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
