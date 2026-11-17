package ru.nexora.network.bedrock;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Minimal protocol-2225 AvailableCommands packet advertising NEXORA commands. */
public final class BedrockAvailableCommandsCodec {
    public static final int AVAILABLE_COMMANDS_PACKET_ID = 0x4c;

    private BedrockAvailableCommandsCodec() {
    }

    public static BedrockPacket createEquipmentCommands() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeArrayCount(out, 0); // Enum Values
        writeArrayCount(out, 0); // Chained Subcommand Values
        writeArrayCount(out, 0); // Post Fixes
        writeArrayCount(out, 0); // Enum Data
        writeArrayCount(out, 0); // Chained Subcommand Data

        writeArrayCount(out, 2); // Commands
        writeCommand(out, "equipment", "Open the NEXORA magical equipment menu");
        writeCommand(out, "ai", "Open the NEXORA AI assistant");

        writeArrayCount(out, 0); // Soft Enums
        writeArrayCount(out, 0); // Constraints
        return new BedrockPacket(AVAILABLE_COMMANDS_PACKET_ID, 0, 0, out.toByteArray());
    }

    private static void writeCommand(ByteArrayOutputStream out, String name, String description) {
        writeString(out, name);
        writeString(out, description);
        writeUInt16(out, 0); // Flags
        writeString(out, "Any"); // Permission Level
        writeInt32(out, -1); // No alias enum
        writeArrayCount(out, 0); // Chained subcommand indexes
        writeArrayCount(out, 1); // Overloads
        out.write(0); // IsChaining
        writeArrayCount(out, 0); // Parameter Data
    }

    private static void writeArrayCount(ByteArrayOutputStream out, int count) {
        writeVarUInt32(out, count);
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarUInt32(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeUInt16(ByteArrayOutputStream out, int value) {
        if (value < 0 || value > 0xffff) throw new IllegalArgumentException("uint16 out of range");
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static void writeInt32(ByteArrayOutputStream out, int value) {
        out.writeBytes(ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(value).array());
    }

    private static void writeVarUInt32(ByteArrayOutputStream out, int value) {
        if (value < 0) throw new IllegalArgumentException("VarUInt32 cannot be negative");
        do {
            int next = value & 0x7f;
            value >>>= 7;
            out.write(value == 0 ? next : next | 0x80);
        } while (value != 0);
    }
}
