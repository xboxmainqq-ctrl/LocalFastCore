package ru.nexora.runtime;

/**
 * Bedrock game-packet header: 10-bit packet ID, 2-bit sender sub-client ID,
 * and 2-bit target sub-client ID, encoded as a varuint32.
 */
public record BedrockPacketHeader(int packetId, int senderSubClientId, int targetSubClientId) {
    public static final int MAX_PACKET_ID = 0x3FF;
    public static final int MAX_SUB_CLIENT_ID = 0x03;

    public BedrockPacketHeader {
        if (packetId < 0 || packetId > MAX_PACKET_ID) {
            throw new IllegalArgumentException("packetId must fit 10 bits");
        }
        if (senderSubClientId < 0 || senderSubClientId > MAX_SUB_CLIENT_ID) {
            throw new IllegalArgumentException("senderSubClientId must fit 2 bits");
        }
        if (targetSubClientId < 0 || targetSubClientId > MAX_SUB_CLIENT_ID) {
            throw new IllegalArgumentException("targetSubClientId must fit 2 bits");
        }
    }

    public int toWireValue() {
        return packetId | (senderSubClientId << 10) | (targetSubClientId << 12);
    }

    public static BedrockPacketHeader fromWireValue(int value) {
        if (value < 0 || (value & ~0x3FFF) != 0) {
            throw new IllegalArgumentException("Bedrock packet header contains reserved bits");
        }
        return new BedrockPacketHeader(
                value & 0x3FF,
                (value >>> 10) & 0x03,
                (value >>> 12) & 0x03
        );
    }
}
