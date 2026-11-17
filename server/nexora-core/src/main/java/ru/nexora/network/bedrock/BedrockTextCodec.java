package ru.nexora.network.bedrock;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Protocol-2225 TextPacket codec for system output and player chat. */
public final class BedrockTextCodec {
    public static final int TEXT_PACKET_ID = 0x09;
    public static final int CHAT_TYPE = 1;
    public static final int SYSTEM_TYPE = 6;
    private static final int BODY_MESSAGE_ONLY = 0;
    private static final int BODY_AUTHORED_MESSAGE = 1;
    private static final int MAX_MESSAGE_BYTES = 4096;
    private static final int MAX_CHAT_BYTES = 512;

    private BedrockTextCodec() {}

    public static BedrockPacket createSystemMessage(String message) {
        requireText(message, MAX_MESSAGE_BYTES, "System message");
        ByteArrayOutputStream out = new ByteArrayOutputStream(message.length() + 16);
        out.write(0); // Localize = false
        out.write(BODY_MESSAGE_ONLY);
        out.write(SYSTEM_TYPE);
        writeString(out, message);
        writeString(out, ""); // Sender XUID
        writeString(out, ""); // Platform ID
        out.write(0); // Filtered message is absent
        return new BedrockPacket(TEXT_PACKET_ID, 0, 0, out.toByteArray());
    }

    public static BedrockPacket createChatMessage(
            String sourceName, String message, String xuid, String platformChatId
    ) {
        requireText(sourceName, 256, "Chat source name");
        requireText(message, MAX_CHAT_BYTES, "Chat message");
        requireOptionalText(xuid, 128, "Chat XUID");
        requireOptionalText(platformChatId, 128, "Platform chat ID");
        ByteArrayOutputStream out = new ByteArrayOutputStream(message.length() + sourceName.length() + 24);
        out.write(0); // Localize = false
        out.write(BODY_AUTHORED_MESSAGE);
        out.write(CHAT_TYPE);
        writeString(out, sourceName);
        writeString(out, message);
        writeString(out, xuid == null ? "" : xuid);
        writeString(out, platformChatId == null ? "" : platformChatId);
        out.write(0); // Filtered message is absent
        return new BedrockPacket(TEXT_PACKET_ID, 0, 0, out.toByteArray());
    }

    /** Decodes only authenticated-player chat; all other TextPacket variants are rejected. */
    public static DecodedChat decodeChat(BedrockPacket packet) {
        if (packet == null || packet.packetId() != TEXT_PACKET_ID) {
            throw new IllegalArgumentException("Expected Bedrock TextPacket");
        }
        ByteBuffer in = ByteBuffer.wrap(packet.payload());
        if (readBoolean(in, "Localize")) {
            throw new IllegalArgumentException("Localized client chat is not supported");
        }
        int body = readUnsignedByte(in, "Text body");
        if (body != BODY_AUTHORED_MESSAGE) {
            throw new IllegalArgumentException("Client TextPacket is not an authored message");
        }
        int type = readUnsignedByte(in, "Text type");
        if (type != CHAT_TYPE) {
            throw new IllegalArgumentException("Only client chat TextPackets are accepted");
        }
        String sourceName = readString(in, 256, "Chat source name");
        String message = readString(in, MAX_CHAT_BYTES, "Chat message");
        String xuid = readString(in, 128, "Chat XUID");
        String platformChatId = readString(in, 128, "Platform chat ID");
        String filteredMessage = readBoolean(in, "Filtered-message presence")
                ? readString(in, MAX_CHAT_BYTES, "Filtered message") : "";
        if (in.hasRemaining()) throw new IllegalArgumentException("Trailing bytes in TextPacket");
        requireText(message, MAX_CHAT_BYTES, "Chat message");
        return new DecodedChat(sourceName, message, xuid, platformChatId, filteredMessage);
    }

    private static void requireText(String value, int maxBytes, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " must not be blank");
        if (value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(label + " exceeds size limit");
        }
    }

    private static void requireOptionalText(String value, int maxBytes, String label) {
        if (value != null && value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(label + " exceeds size limit");
        }
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarUInt32(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static String readString(ByteBuffer in, int maxBytes, String label) {
        int length = readVarUInt32(in);
        if (length > maxBytes || length > in.remaining()) {
            throw new IllegalArgumentException("Invalid " + label + " length");
        }
        byte[] bytes = new byte[length];
        in.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static boolean readBoolean(ByteBuffer in, String label) {
        int value = readUnsignedByte(in, label);
        if (value != 0 && value != 1) throw new IllegalArgumentException("Invalid " + label);
        return value == 1;
    }

    private static int readUnsignedByte(ByteBuffer in, String label) {
        if (!in.hasRemaining()) throw new IllegalArgumentException("Truncated " + label);
        return Byte.toUnsignedInt(in.get());
    }

    private static int readVarUInt32(ByteBuffer in) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int next = readUnsignedByte(in, "string length");
            if (shift == 28 && (next & 0xf0) != 0) {
                throw new IllegalArgumentException("VarUInt32 overflow");
            }
            value |= (next & 0x7f) << shift;
            if ((next & 0x80) == 0) {
                if (value < 0) throw new IllegalArgumentException("Negative string length");
                return value;
            }
        }
        throw new IllegalArgumentException("Malformed VarUInt32");
    }

    private static void writeVarUInt32(ByteArrayOutputStream out, int value) {
        do {
            int next = value & 0x7f;
            value >>>= 7;
            out.write(value == 0 ? next : next | 0x80);
        } while (value != 0);
    }

    public record DecodedChat(
            String sourceName, String message, String xuid,
            String platformChatId, String filteredMessage
    ) {}
}
