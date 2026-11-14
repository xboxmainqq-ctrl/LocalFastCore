package ru.nexora.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Wire codec matching the server's private NEXORA protocol v1. */
final class NexoraAndroidProtocolCodec {
    static final int MAJOR_VERSION = 1;
    static final int MINOR_VERSION = 0;
    static final int MAX_MESSAGE_BYTES = 1024;
    private static final byte[] MAGIC = {'N', 'X', 'R', 'A'};

    private NexoraAndroidProtocolCodec() {
    }

    static byte[] encodeClientHello(String build) {
        if (build == null) {
            throw new IllegalArgumentException("Client build is required");
        }
        byte[] text = build.getBytes(StandardCharsets.UTF_8);
        if (text.length < 1 || text.length > 64) {
            throw new IllegalArgumentException("Client build must be 1..64 UTF-8 bytes");
        }
        ByteBuffer output = ByteBuffer.allocate(8 + text.length).order(ByteOrder.BIG_ENDIAN);
        output.put(MAGIC).put((byte) MAJOR_VERSION).put((byte) MINOR_VERSION)
                .put((byte) 1).put((byte) text.length).put(text);
        return output.array();
    }

    static ServerResponse decodeServerResponse(byte[] bytes) {
        if (bytes == null || bytes.length < 7 || bytes.length > MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("Invalid NEXORA message size");
        }
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        for (byte expected : MAGIC) {
            if (input.get() != expected) {
                throw new IllegalArgumentException("Invalid NEXORA protocol magic");
            }
        }
        int major = Byte.toUnsignedInt(input.get());
        int minor = Byte.toUnsignedInt(input.get());
        int type = Byte.toUnsignedInt(input.get());
        if (major != MAJOR_VERSION || minor > MINOR_VERSION) {
            throw new IllegalArgumentException("Unsupported NEXORA protocol version "
                    + major + "." + minor);
        }
        if (type == 2) {
            if (input.remaining() != 20) {
                throw new IllegalArgumentException("Malformed NEXORA ServerHello");
            }
            UUID serverId = new UUID(input.getLong(), input.getLong());
            int maxMessageBytes = input.getInt();
            if (maxMessageBytes < 1 || maxMessageBytes > 8 * 1024 * 1024) {
                throw new IllegalArgumentException("Invalid NEXORA server message limit");
            }
            return new ServerResponse(true, serverId.toString(), maxMessageBytes, 0, "");
        }
        if (type == 3) {
            if (input.remaining() < 2) {
                throw new IllegalArgumentException("Malformed NEXORA rejection");
            }
            int code = Byte.toUnsignedInt(input.get());
            int length = Byte.toUnsignedInt(input.get());
            if (code == 0 || length < 1 || length > 160 || input.remaining() != length) {
                throw new IllegalArgumentException("Malformed NEXORA rejection details");
            }
            byte[] text = new byte[length];
            input.get(text);
            return new ServerResponse(false, "", 0, code, decodeUtf8(text));
        }
        throw new IllegalArgumentException("Unexpected NEXORA server message type");
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("NEXORA text is not valid UTF-8", invalid);
        }
    }

    record ServerResponse(boolean accepted, String serverId, int maxMessageBytes,
                          int rejectionCode, String rejectionReason) {
    }
}
