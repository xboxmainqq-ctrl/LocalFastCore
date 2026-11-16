package ru.nexora.network.raknet;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * Codec for the RakNet offline Unconnected Ping (0x01) and Unconnected Pong (0x1c).
 * This is discovery only; it does not establish a RakNet session.
 */
public final class RakNetDiscoveryCodec {
    public static final int UNCONNECTED_PING = 0x01;
    public static final int UNCONNECTED_PONG = 0x1c;
    public static final int MIN_PING_LENGTH = 33;

    private static final byte[] MAGIC = new byte[] {
            0x00, (byte) 0xff, (byte) 0xff, 0x00,
            (byte) 0xfe, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe,
            (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd,
            0x12, 0x34, 0x56, 0x78
    };

    private RakNetDiscoveryCodec() {
    }

    public static Optional<byte[]> encodePong(
            byte[] request,
            long serverGuid,
            BedrockServerStatus status
    ) {
        if (!isValidUnconnectedPing(request)) return Optional.empty();

        ByteBuffer input = ByteBuffer.wrap(request).order(ByteOrder.BIG_ENDIAN);
        input.position(1);
        long clientTimestamp = input.getLong();

        byte[] advertisement = status.toBedrockAdvertisement(serverGuid)
                .getBytes(StandardCharsets.UTF_8);
        if (advertisement.length > 0xffff) {
            throw new IllegalArgumentException("Bedrock advertisement exceeds unsigned-short length");
        }

        ByteBuffer output = ByteBuffer.allocate(1 + Long.BYTES + Long.BYTES
                + MAGIC.length + Short.BYTES + advertisement.length)
                .order(ByteOrder.BIG_ENDIAN);
        output.put((byte) UNCONNECTED_PONG);
        output.putLong(clientTimestamp);
        output.putLong(serverGuid);
        output.put(MAGIC);
        output.putShort((short) advertisement.length);
        output.put(advertisement);
        return Optional.of(output.array());
    }

    public static boolean isValidUnconnectedPing(byte[] packet) {
        if (packet == null || packet.length < MIN_PING_LENGTH) return false;
        if ((packet[0] & 0xff) != UNCONNECTED_PING) return false;
        return Arrays.equals(MAGIC, Arrays.copyOfRange(packet, 9, 25));
    }

    public static byte[] magic() {
        return MAGIC.clone();
    }
}
