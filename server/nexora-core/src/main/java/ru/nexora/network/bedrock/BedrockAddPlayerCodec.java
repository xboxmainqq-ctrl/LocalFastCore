package ru.nexora.network.bedrock;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Protocol-2225 AddPlayer packet encoder. */
public final class BedrockAddPlayerCodec {
    public static final int ADD_PLAYER_PACKET_ID = 0x0c;
    private static final Set<Integer> BUILD_PLATFORMS =
            Set.of(1, 2, 3, 4, 8, 9, 11, 12, 13, 15, -1);
    private static final int MAX_STRING_BYTES = 1024;

    private BedrockAddPlayerCodec() {
    }

    public static BedrockPacket createSpawn(SpawnData player) {
        Objects.requireNonNull(player, "player");
        Writer out = new Writer();
        out.uuid(player.uuid());
        out.string(player.playerName());
        out.varUInt64(player.runtimeId());
        out.string(player.platformChatId());
        out.vec3(player.x(), player.y(), player.z());
        out.vec3(player.velocityX(), player.velocityY(), player.velocityZ());
        out.vec2(player.pitch(), player.yaw());
        out.float32(player.headYaw());

        // Empty NetworkItemStackDescriptorSerializedData.
        out.int16(0);       // item ID
        out.uint16(0);      // stack size
        out.varUInt32(0);   // aux value
        out.bool(false);    // optional stack NetID
        out.varUInt32(0);   // block runtime ID
        out.byteArray(new byte[0]);

        out.varInt32(0);    // Survival game type in protocol 2225.
        out.varUInt32(0);   // Synched actor data.
        out.varUInt32(0);   // Synched properties: integer entries.
        out.varUInt32(0);   // Synched properties: float entries.

        // Member permissions and a single base ability layer.
        out.int64(0L);      // Target player raw ID.
        out.byteValue(1);   // PlayerPermissionLevel.Member.
        out.byteValue(0);   // CommandPermissionLevel.Any.
        out.varUInt32(1);   // One base ability layer.
        out.uint16(1);      // AbilityLayerTypeBase.
        out.uint32(0x0000_ffff); // Basic abilities are known to the client.
        out.uint32(0x0000_003f); // Build, mine, interact and attack permissions.
        out.float32(0.05f);
        out.float32(1.0f);
        out.float32(0.1f);

        out.varUInt32(0);   // Actor links.
        out.bool(false);    // PassengerOfBlockArguments is absent.
        out.string(player.deviceId());
        out.int32(player.buildPlatform());
        return new BedrockPacket(ADD_PLAYER_PACKET_ID, 0, 0, out.bytes());
    }

    public record SpawnData(
            UUID uuid,
            String playerName,
            long runtimeId,
            String platformChatId,
            double x,
            double y,
            double z,
            float velocityX,
            float velocityY,
            float velocityZ,
            float pitch,
            float yaw,
            float headYaw,
            String deviceId,
            int buildPlatform
    ) {
        public SpawnData {
            Objects.requireNonNull(uuid, "uuid");
            if (playerName == null || playerName.isBlank()
                    || playerName.getBytes(StandardCharsets.UTF_8).length > MAX_STRING_BYTES) {
                throw new IllegalArgumentException("Invalid player name");
            }
            if (runtimeId < 1 || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000
                    || y < -2048 || y > 2048
                    || !Float.isFinite(velocityX) || !Float.isFinite(velocityY) || !Float.isFinite(velocityZ)
                    || !Float.isFinite(pitch) || !Float.isFinite(yaw) || !Float.isFinite(headYaw)) {
                throw new IllegalArgumentException("Invalid player spawn position or motion");
            }
            if (!BUILD_PLATFORMS.contains(buildPlatform)) {
                throw new IllegalArgumentException("Unsupported Bedrock build platform");
            }
            platformChatId = platformChatId == null ? "" : platformChatId;
            deviceId = deviceId == null ? "" : deviceId;
        }
    }

    private static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(128);

        void byteValue(int value) { out.write(value & 0xff); }
        void bool(boolean value) { byteValue(value ? 1 : 0); }
        void int16(int value) { byteValue(value); byteValue(value >>> 8); }
        void uint16(int value) { byteValue(value); byteValue(value >>> 8); }
        void int32(int value) {
            byteValue(value); byteValue(value >>> 8); byteValue(value >>> 16); byteValue(value >>> 24);
        }
        void uint32(long value) { int32((int) value); }
        void int64(long value) {
            for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) byteValue((int) (value >>> shift));
        }
        void float32(float value) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Player float must be finite");
            out.writeBytes(ByteBuffer.allocate(Float.BYTES).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array());
        }
        void vec3(double x, double y, double z) {
            float32((float) x); float32((float) y); float32((float) z);
        }
        void vec2(float x, float y) { float32(x); float32(y); }
        void uuid(UUID uuid) { int64(uuid.getMostSignificantBits()); int64(uuid.getLeastSignificantBits()); }
        void string(String value) {
            byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_STRING_BYTES) throw new IllegalArgumentException("Player string exceeds limit");
            varUInt32(bytes.length); out.writeBytes(bytes);
        }
        void byteArray(byte[] bytes) { varUInt32(bytes.length); out.writeBytes(bytes); }
        void varUInt32(long value) {
            if (value < 0 || value > 0xffff_ffffL) throw new IllegalArgumentException("VarUInt32 out of range");
            do {
                int next = (int) (value & 0x7f);
                value >>>= 7;
                if (value != 0) next |= 0x80;
                byteValue(next);
            } while (value != 0);
        }
        void varUInt64(long value) {
            if (value < 0) throw new IllegalArgumentException("VarUInt64 out of range");
            do {
                int next = (int) (value & 0x7f);
                value >>>= 7;
                if (value != 0) next |= 0x80;
                byteValue(next);
            } while (value != 0);
        }
        void varInt32(int value) {
            int encoded = (value << 1) ^ (value >> 31);
            varUInt32(Integer.toUnsignedLong(encoded));
        }
        byte[] bytes() { return out.toByteArray(); }
    }
}
