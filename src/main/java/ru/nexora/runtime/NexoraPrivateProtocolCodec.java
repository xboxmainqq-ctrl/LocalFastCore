package ru.nexora.runtime;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded, versioned wire codec for the first-party NEXORA client/server protocol.
 * This is a private protocol and is not a Bedrock packet format.
 */
public final class NexoraPrivateProtocolCodec {
    public static final int MAJOR_VERSION = 1;
    public static final int MINOR_VERSION = 0;
    public static final int MAX_MESSAGE_BYTES = 1024;
    private static final byte[] MAGIC = {'N', 'X', 'R', 'A'};
    private static final int TYPE_CLIENT_HELLO = 1;
    private static final int TYPE_SERVER_HELLO = 2;
    private static final int TYPE_REJECT = 3;

    private NexoraPrivateProtocolCodec() {
    }

    public static byte[] encodeClientHello(String clientBuild) {
        byte[] build = encodeText(clientBuild, 64, "clientBuild");
        ByteBuffer output = header(TYPE_CLIENT_HELLO, 1 + build.length);
        output.put((byte) build.length).put(build);
        return output.array();
    }

    public static ClientHello decodeClientHello(ByteBuffer source) {
        ByteBuffer input = checkedHeader(source, TYPE_CLIENT_HELLO);
        int length = Byte.toUnsignedInt(readByte(input, "client build length"));
        if (length < 1 || length > 64 || input.remaining() != length) {
            throw new IllegalArgumentException("Invalid NEXORA client build length");
        }
        return new ClientHello(Byte.toUnsignedInt(sourceVersionMajor(source)),
                Byte.toUnsignedInt(sourceVersionMinor(source)), decodeText(input, length));
    }

    public static byte[] encodeServerHello(UUID serverId, int maxMessageBytes) {
        Objects.requireNonNull(serverId, "serverId");
        if (maxMessageBytes < 1 || maxMessageBytes > 8 * 1024 * 1024) {
            throw new IllegalArgumentException("maxMessageBytes is outside the supported range");
        }
        ByteBuffer output = header(TYPE_SERVER_HELLO, 20);
        output.putLong(serverId.getMostSignificantBits());
        output.putLong(serverId.getLeastSignificantBits());
        output.putInt(maxMessageBytes);
        return output.array();
    }

    public static byte[] encodeReject(int code, String reason) {
        if (code < 1 || code > 255) {
            throw new IllegalArgumentException("rejection code must be between 1 and 255");
        }
        byte[] text = encodeText(reason, 160, "reason");
        ByteBuffer output = header(TYPE_REJECT, 2 + text.length);
        output.put((byte) code).put((byte) text.length).put(text);
        return output.array();
    }

    public static ServerResponse decodeServerResponse(ByteBuffer source) {
        Objects.requireNonNull(source, "source");
        ByteBuffer original = source.slice();
        if (original.remaining() < 7 || original.remaining() > MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("Invalid NEXORA message size");
        }
        ByteBuffer input = original.duplicate().order(ByteOrder.BIG_ENDIAN);
        checkMagic(input);
        int major = Byte.toUnsignedInt(input.get());
        int minor = Byte.toUnsignedInt(input.get());
        int type = Byte.toUnsignedInt(input.get());
        if (major != MAJOR_VERSION || minor > MINOR_VERSION) {
            throw new IllegalArgumentException("Unsupported NEXORA protocol version "
                    + major + "." + minor);
        }
        if (type == TYPE_SERVER_HELLO) {
            if (input.remaining() != 20) {
                throw new IllegalArgumentException("Malformed NEXORA ServerHello");
            }
            UUID serverId = new UUID(input.getLong(), input.getLong());
            int maxMessageBytes = input.getInt();
            if (maxMessageBytes < 1 || maxMessageBytes > 8 * 1024 * 1024) {
                throw new IllegalArgumentException("Invalid NEXORA server message limit");
            }
            return new ServerResponse(true, serverId, maxMessageBytes, 0, "");
        }
        if (type == TYPE_REJECT) {
            if (input.remaining() < 2) {
                throw new IllegalArgumentException("Malformed NEXORA rejection");
            }
            int code = Byte.toUnsignedInt(input.get());
            int length = Byte.toUnsignedInt(input.get());
            if (code == 0 || length < 1 || length > 160 || input.remaining() != length) {
                throw new IllegalArgumentException("Malformed NEXORA rejection details");
            }
            return new ServerResponse(false, null, 0, code, decodeText(input, length));
        }
        throw new IllegalArgumentException("Unexpected NEXORA server message type");
    }

    private static ByteBuffer header(int type, int payloadLength) {
        int total = 7 + payloadLength;
        if (total > MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("NEXORA message exceeds size limit");
        }
        ByteBuffer output = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN);
        output.put(MAGIC).put((byte) MAJOR_VERSION).put((byte) MINOR_VERSION).put((byte) type);
        return output;
    }

    private static ByteBuffer checkedHeader(ByteBuffer source, int expectedType) {
        Objects.requireNonNull(source, "source");
        ByteBuffer input = source.slice().order(ByteOrder.BIG_ENDIAN);
        if (input.remaining() < 8 || input.remaining() > MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("Invalid NEXORA message size");
        }
        checkMagic(input);
        int major = Byte.toUnsignedInt(input.get());
        int minor = Byte.toUnsignedInt(input.get());
        int type = Byte.toUnsignedInt(input.get());
        if (type != expectedType) {
            throw new IllegalArgumentException("Unexpected NEXORA message type");
        }
        // Version is returned to the caller for deterministic negotiation/rejection.
        input.position(0);
        input.position(7);
        return input;
    }

    private static void checkMagic(ByteBuffer input) {
        for (byte expected : MAGIC) {
            if (!input.hasRemaining() || input.get() != expected) {
                throw new IllegalArgumentException("Invalid NEXORA protocol magic");
            }
        }
    }

    private static byte readByte(ByteBuffer input, String field) {
        if (!input.hasRemaining()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        return input.get();
    }

    private static byte sourceVersionMajor(ByteBuffer source) {
        return source.get(source.position() + 4);
    }

    private static byte sourceVersionMinor(ByteBuffer source) {
        return source.get(source.position() + 5);
    }

    private static byte[] encodeText(String value, int maxBytes, String field) {
        Objects.requireNonNull(value, field);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 1 || bytes.length > maxBytes) {
            throw new IllegalArgumentException(field + " must be 1.." + maxBytes + " UTF-8 bytes");
        }
        return bytes;
    }

    private static String decodeText(ByteBuffer input, int length) {
        byte[] bytes = new byte[length];
        input.get(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("NEXORA text is not valid UTF-8", invalid);
        }
    }

    public record ClientHello(int majorVersion, int minorVersion, String clientBuild) {
        public ClientHello {
            Objects.requireNonNull(clientBuild, "clientBuild");
        }
    }

    public record ServerResponse(boolean accepted, UUID serverId, int maxMessageBytes,
                                 int rejectionCode, String rejectionReason) {
        public ServerResponse {
            Objects.requireNonNull(rejectionReason, "rejectionReason");
            if (accepted && serverId == null) {
                throw new IllegalArgumentException("Accepted response requires a server id");
            }
            if (!accepted && rejectionCode == 0) {
                throw new IllegalArgumentException("Rejected response requires a rejection code");
            }
        }
    }
}
