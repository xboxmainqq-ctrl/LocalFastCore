package ru.nexora.network.bedrock;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Protocol-2225 ModalFormRequest/ModalFormResponse packet helpers. */
public final class BedrockModalFormCodec {
    public static final int MODAL_FORM_REQUEST_PACKET_ID = 0x64;
    public static final int MODAL_FORM_RESPONSE_PACKET_ID = 0x65;
    private static final int MAX_FORM_JSON_BYTES = 65_536;
    private static final int MAX_RESPONSE_BYTES = 16_384;

    private BedrockModalFormCodec() {
    }

    public static BedrockPacket createRequest(int formId, String json) {
        if (formId < 0) throw new IllegalArgumentException("Form ID cannot be negative");
        byte[] jsonBytes = utf8(json, MAX_FORM_JSON_BYTES);
        ByteArrayOutputStream out = new ByteArrayOutputStream(jsonBytes.length + 8);
        writeVarUInt32(out, formId);
        writeString(out, jsonBytes);
        return new BedrockPacket(MODAL_FORM_REQUEST_PACKET_ID, out.toByteArray());
    }

    public static DecodedResponse decodeResponse(BedrockPacket packet) {
        if (packet == null || packet.packetId() != MODAL_FORM_RESPONSE_PACKET_ID) {
            throw new IllegalArgumentException("Expected ModalFormResponse packet 0x65");
        }
        Reader in = new Reader(packet.payload());
        int formId = in.varUInt32Int();
        String response = in.bool() ? in.string(MAX_RESPONSE_BYTES) : null;
        Integer cancelReason = in.bool() ? in.uint8() : null;
        in.requireExhausted();
        if (cancelReason != null && cancelReason > 1) {
            throw new IllegalArgumentException("Unknown ModalFormResponse cancel reason");
        }
        return new DecodedResponse(formId, response, cancelReason);
    }

    public record DecodedResponse(int formId, String responseJson, Integer cancelReason) {
        public boolean cancelled() {
            return responseJson == null || cancelReason != null;
        }
    }

    private static byte[] utf8(String value, int maxBytes) {
        if (value == null) throw new NullPointerException("value");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) throw new IllegalArgumentException("Form JSON exceeds size limit");
        return bytes;
    }

    private static void writeString(ByteArrayOutputStream out, byte[] bytes) {
        writeVarUInt32(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeVarUInt32(ByteArrayOutputStream out, int value) {
        if (value < 0) throw new IllegalArgumentException("VarUInt32 cannot be negative");
        do {
            int next = value & 0x7f;
            value >>>= 7;
            out.write(value == 0 ? next : next | 0x80);
        } while (value != 0);
    }

    private static final class Reader {
        private final ByteBuffer buffer;

        Reader(byte[] payload) {
            if (payload == null || payload.length > MAX_RESPONSE_BYTES + 16) {
                throw new IllegalArgumentException("ModalFormResponse payload exceeds size limit");
            }
            buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        }

        int varUInt32Int() {
            long value = 0;
            for (int i = 0; i < 5; i++) {
                require(1);
                int current = Byte.toUnsignedInt(buffer.get());
                if (i == 4 && (current & 0xf0) != 0) {
                    throw new IllegalArgumentException("ModalFormResponse VarUInt32 overflow");
                }
                value |= (long) (current & 0x7f) << (7 * i);
                if ((current & 0x80) == 0) {
                    if (value > Integer.MAX_VALUE) throw new IllegalArgumentException("Form ID exceeds supported range");
                    return (int) value;
                }
            }
            throw new IllegalArgumentException("ModalFormResponse VarUInt32 is too long");
        }

        String string(int maximumBytes) {
            int length = varUInt32Int();
            if (length > maximumBytes) throw new IllegalArgumentException("ModalFormResponse string exceeds size limit");
            require(length);
            ByteBuffer slice = buffer.slice();
            slice.limit(length);
            buffer.position(buffer.position() + length);
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(slice).toString();
            } catch (CharacterCodingException exception) {
                throw new IllegalArgumentException("Invalid UTF-8 in ModalFormResponse", exception);
            }
        }

        boolean bool() {
            int value = uint8();
            if (value > 1) throw new IllegalArgumentException("Invalid ModalFormResponse presence flag");
            return value != 0;
        }

        int uint8() {
            require(1);
            return Byte.toUnsignedInt(buffer.get());
        }

        void requireExhausted() {
            if (buffer.hasRemaining()) throw new IllegalArgumentException("Trailing ModalFormResponse bytes");
        }

        private void require(int length) {
            if (length < 0 || buffer.remaining() < length) {
                throw new IllegalArgumentException("Truncated ModalFormResponse packet");
            }
        }
    }
}
