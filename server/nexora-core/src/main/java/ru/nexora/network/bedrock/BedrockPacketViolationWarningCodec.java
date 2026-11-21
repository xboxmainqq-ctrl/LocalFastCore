package ru.nexora.network.bedrock;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Decoder for the client-to-server PacketViolationWarning packet (protocol 2225).
 * This packet reports a malformed packet observed by the client; it is diagnostic
 * and must not itself be treated as an ordinary gameplay packet.
 */
public final class BedrockPacketViolationWarningCodec {
    public static final int PACKET_ID = 0x9C;
    private static final int MAX_CONTEXT_BYTES = 4_096;

    private BedrockPacketViolationWarningCodec() {
    }

    public static Violation decode(BedrockPacket packet) {
        if (packet == null || packet.packetId() != PACKET_ID) {
            throw new IllegalArgumentException("Expected PacketViolationWarning packet 0x9C");
        }
        Reader in = new Reader(packet.payload());
        int type = in.varInt32("violation type");
        int severity = in.varInt32("violation severity");
        int packetId = in.varInt32("violating packet ID");
        int contextLength = in.varUInt32("violation context length");
        if (contextLength > MAX_CONTEXT_BYTES) {
            throw new IllegalArgumentException("PacketViolationWarning context exceeds "
                    + MAX_CONTEXT_BYTES + " bytes");
        }
        String context = in.utf8(contextLength);
        if (!in.isAtEnd()) {
            throw new IllegalArgumentException("PacketViolationWarning has trailing payload bytes");
        }
        return new Violation(type, severity, packetId, context);
    }

    public static String typeName(int type) {
        return switch (type) {
            case 0 -> "PacketMalformed";
            default -> "Unknown";
        };
    }

    public static String severityName(int severity) {
        return switch (severity) {
            case 0 -> "Warning";
            case 1 -> "FinalWarning";
            case 2 -> "TerminatingConnection";
            default -> "Unknown";
        };
    }

    public record Violation(int type, int severity, int packetId, String context) {
        public Violation {
            if (type < -1) throw new IllegalArgumentException("type must be -1 or non-negative");
            if (severity < -1) throw new IllegalArgumentException("severity must be -1 or non-negative");
            if (packetId < 0 || packetId > 0x3ff) {
                throw new IllegalArgumentException("packetId must be in 0..1023");
            }
            if (context == null) throw new IllegalArgumentException("context must not be null");
        }
    }

    private static final class Reader {
        private final byte[] bytes;
        private int offset;

        private Reader(byte[] bytes) {
            this.bytes = bytes;
        }

        int varInt32(String field) {
            long encoded = varUInt32(field);
            return (int) ((encoded >>> 1) ^ -(encoded & 1L));
        }

        int varUInt32(String field) {
            long value = 0;
            int shift = 0;
            for (int count = 0; count < 5; count++) {
                if (offset >= bytes.length) {
                    throw new IllegalArgumentException("PacketViolationWarning is truncated at " + field);
                }
                int next = Byte.toUnsignedInt(bytes[offset++]);
                value |= (long) (next & 0x7f) << shift;
                if ((next & 0x80) == 0) {
                    if (value > 0xffff_ffffL) {
                        throw new IllegalArgumentException("PacketViolationWarning " + field + " overflows VarUInt32");
                    }
                    return (int) value;
                }
                shift += 7;
            }
            throw new IllegalArgumentException("PacketViolationWarning " + field + " has malformed VarUInt32");
        }

        String utf8(int length) {
            if (length < 0 || length > bytes.length - offset) {
                throw new IllegalArgumentException("PacketViolationWarning context is truncated");
            }
            try {
                String result = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, offset, length)).toString();
                offset += length;
                return result;
            } catch (CharacterCodingException malformed) {
                throw new IllegalArgumentException("PacketViolationWarning context is not valid UTF-8", malformed);
            }
        }

        boolean isAtEnd() {
            return offset == bytes.length;
        }
    }
}
