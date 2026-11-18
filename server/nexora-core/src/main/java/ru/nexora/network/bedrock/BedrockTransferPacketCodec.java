package ru.nexora.network.bedrock;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Protocol-2225 TransferPacket (0x55) encoder with no optional gatherings metadata. */
public final class BedrockTransferPacketCodec {
    public static final int TRANSFER_PACKET_ID = 0x55;
    private static final int MAX_ADDRESS_BYTES = 255;

    private BedrockTransferPacketCodec() {
    }

    public static BedrockPacket create(String address, int port, boolean reloadWorld) {
        if (address == null || address.isBlank() || address.length() > 255
                || address.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid Bedrock transfer address");
        }
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("Transfer port must be 1..65535");
        byte[] host = address.getBytes(StandardCharsets.UTF_8);
        if (host.length < 1 || host.length > MAX_ADDRESS_BYTES) {
            throw new IllegalArgumentException("Transfer address exceeds protocol limit");
        }

        ByteArrayOutputStream payload = new ByteArrayOutputStream(host.length + 8);
        writeVarUInt(payload, host.length);
        payload.writeBytes(host);
        payload.write(port & 0xff);
        payload.write((port >>> 8) & 0xff);
        payload.write(reloadWorld ? 1 : 0);
        payload.write(0); // No optional gatherings configuration.
        return new BedrockPacket(TRANSFER_PACKET_ID, 0, 0, payload.toByteArray());
    }

    private static void writeVarUInt(ByteArrayOutputStream output, int value) {
        int remaining = value;
        while ((remaining & ~0x7f) != 0) {
            output.write((remaining & 0x7f) | 0x80);
            remaining >>>= 7;
        }
        output.write(remaining);
    }
}
