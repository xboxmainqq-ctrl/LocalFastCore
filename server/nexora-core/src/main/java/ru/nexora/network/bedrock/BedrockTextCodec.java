package ru.nexora.network.bedrock;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Creates Bedrock protocol-2225 system text messages for server-to-client chat output. */
public final class BedrockTextCodec {
    public static final int TEXT_PACKET_ID = 0x09;
    private BedrockTextCodec() {}

    public static BedrockPacket createSystemMessage(String message) {
        if (message == null || message.isBlank()) throw new IllegalArgumentException("Message must not be blank");
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 4096) throw new IllegalArgumentException("Message exceeds size limit");
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length + 16);
        out.write(6); // TextType.System
        out.write(0); // NeedsTranslation
        writeString(out, "");
        writeString(out, message);
        writeVarUInt32(out, 0); // Parameters
        writeString(out, ""); // Xbox user ID
        writeString(out, ""); // Platform chat ID
        writeString(out, ""); // Filtered message
        return new BedrockPacket(TEXT_PACKET_ID, 0, 0, out.toByteArray());
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarUInt32(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeVarUInt32(ByteArrayOutputStream out, int value) {
        do {
            int next = value & 0x7f;
            value >>>= 7;
            out.write(value == 0 ? next : next | 0x80);
        } while (value != 0);
    }
}
