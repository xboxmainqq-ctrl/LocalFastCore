package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.util.Objects;

/** One decoded Bedrock game packet, excluding its batch length prefix. */
public final class BedrockGamePacket {
    private final BedrockPacketHeader header;
    private final byte[] payload;

    public BedrockGamePacket(BedrockPacketHeader header, byte[] payload) {
        this.header = Objects.requireNonNull(header, "header");
        this.payload = Objects.requireNonNull(payload, "payload").clone();
    }

    public BedrockPacketHeader header() {
        return header;
    }

    public byte[] payload() {
        return payload.clone();
    }

    public ByteBuffer payloadBuffer() {
        return ByteBuffer.wrap(payload).asReadOnlyBuffer();
    }

    public int encodedSize() {
        return varUInt32Size(header.toWireValue()) + payload.length;
    }

    static int varUInt32Size(int value) {
        int size = 1;
        while ((value & 0xFFFFFF80) != 0) {
            value >>>= 7;
            size++;
        }
        return size;
    }
}
