package ru.nexora.network.bedrock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Strict protocol-2225 decoder for client slash-command requests. */
public final class BedrockCommandRequestCodec {
    public static final int COMMAND_REQUEST_PACKET_ID = 0x4d;
    private static final int MAX_PACKET_BYTES = 4096;

    private BedrockCommandRequestCodec() {
    }

    public static DecodedCommand decode(BedrockPacket packet) {
        if (packet == null || packet.packetId() != COMMAND_REQUEST_PACKET_ID) {
            throw new IllegalArgumentException("Expected CommandRequest packet 0x4d");
        }
        Reader in = new Reader(packet.payload());
        String command = in.string(1024);
        String originType = in.string(32);
        in.skip(16); // CommandOriginData UUID (two uint64 values)
        String requestId = in.string(128);
        long playerId = in.int64();
        boolean internal = in.bool();
        String version = in.string(128);
        in.requireExhausted();

        if (command.isBlank()) throw new IllegalArgumentException("Command cannot be blank");
        // Bedrock-compatible clients may serialize the known origin names in lower
        // camel case (for example, "player"). Normalize before the authenticated
        // player-origin check in BedrockSessionManager.
        String normalizedOrigin = switch (originType.toLowerCase(java.util.Locale.ROOT)) {
            case "player" -> "Player";
            case "devconsole" -> "DevConsole";
            case "test" -> "Test";
            case "automationplayer" -> "AutomationPlayer";
            default -> throw new IllegalArgumentException("Unknown command origin type");
        };
        return new DecodedCommand(command, normalizedOrigin, requestId, playerId, internal, version);
    }

    public record DecodedCommand(String command, String originType, String requestId,
                                 long playerId, boolean internal, String version) {
    }

    private static final class Reader {
        private final ByteBuffer buffer;

        private Reader(byte[] payload) {
            if (payload == null || payload.length > MAX_PACKET_BYTES) {
                throw new IllegalArgumentException("CommandRequest payload exceeds size limit");
            }
            buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        }

        String string(int maximumBytes) {
            long length = varUInt32();
            if (length > maximumBytes) throw new IllegalArgumentException("CommandRequest string exceeds size limit");
            require((int) length);
            ByteBuffer slice = buffer.slice();
            slice.limit((int) length);
            buffer.position(buffer.position() + (int) length);
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(slice).toString();
            } catch (CharacterCodingException exception) {
                throw new IllegalArgumentException("Invalid UTF-8 in CommandRequest", exception);
            }
        }

        long varUInt32() {
            long value = 0;
            for (int i = 0; i < 5; i++) {
                require(1);
                int current = Byte.toUnsignedInt(buffer.get());
                if (i == 4 && (current & 0xf0) != 0) {
                    throw new IllegalArgumentException("CommandRequest VarUInt32 overflow");
                }
                value |= (long) (current & 0x7f) << (7 * i);
                if ((current & 0x80) == 0) return value;
            }
            throw new IllegalArgumentException("CommandRequest VarUInt32 is too long");
        }

        long int64() {
            require(Long.BYTES);
            return buffer.getLong();
        }

        boolean bool() {
            require(1);
            int value = Byte.toUnsignedInt(buffer.get());
            if (value > 1) throw new IllegalArgumentException("Invalid CommandRequest boolean");
            return value != 0;
        }

        void skip(int count) {
            require(count);
            buffer.position(buffer.position() + count);
        }

        void requireExhausted() {
            if (buffer.hasRemaining()) throw new IllegalArgumentException("Trailing CommandRequest bytes");
        }

        private void require(int count) {
            if (count < 0 || buffer.remaining() < count) {
                throw new IllegalArgumentException("Truncated CommandRequest packet");
            }
        }
    }
}
