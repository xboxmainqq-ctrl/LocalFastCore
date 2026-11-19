package ru.nexora.network.bedrock;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Protocol-2225 PlayerListPacket codec (packet ID 63). */
public final class BedrockPlayerListCodec {
    public static final int PLAYER_LIST_PACKET_ID = 0x3f;
    public static final int ACTION_ADD = 0;
    public static final int ACTION_REMOVE = 1;
    private static final int MAX_STRING_BYTES = 1024;
    private static final int MAX_SKIN_BYTES = 2 * 1024 * 1024;
    private static final Set<Integer> BUILD_PLATFORMS =
            Set.of(1, 2, 3, 4, 8, 9, 11, 12, 13, 15, -1);

    private BedrockPlayerListCodec() {
    }

    public static BedrockPacket createAdd(List<AddEntry> entries) {
        requireEntries(entries);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarUInt32(out, entries.size());
        for (AddEntry entry : entries) {
            out.write(ACTION_ADD);
            writeUuid(out, entry.uuid());
            writeVarInt64(out, entry.actorUniqueId());
            writeString(out, entry.playerName());
            writeString(out, entry.xuid());
            writeString(out, entry.playFabId());
            writeString(out, entry.platformOnlineId());
            writeInt32(out, entry.buildPlatform());
            out.writeBytes(entry.serializedSkin());
            out.write(entry.teacher() ? 1 : 0);
            out.write(entry.host() ? 1 : 0);
            out.write(entry.subClient() ? 1 : 0);
            writeInt32(out, entry.playerColor());
        }
        return new BedrockPacket(PLAYER_LIST_PACKET_ID, 0, 0, out.toByteArray());
    }

    public static BedrockPacket createRemove(List<UUID> playerIds) {
        requireEntries(playerIds);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarUInt32(out, playerIds.size());
        for (UUID playerId : playerIds) {
            out.write(ACTION_REMOVE);
            writeUuid(out, Objects.requireNonNull(playerId, "playerId"));
        }
        return new BedrockPacket(PLAYER_LIST_PACKET_ID, 0, 0, out.toByteArray());
    }

    private static void requireEntries(List<?> entries) {
        Objects.requireNonNull(entries, "entries");
        if (entries.isEmpty() || entries.size() > 1024) {
            throw new IllegalArgumentException("PlayerList entries must contain 1..1024 elements");
        }
    }

    private static void writeUuid(ByteArrayOutputStream out, UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        writeInt64(out, uuid.getMostSignificantBits());
        writeInt64(out, uuid.getLeastSignificantBits());
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        String text = value == null ? "" : value;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("PlayerList string exceeds " + MAX_STRING_BYTES + " UTF-8 bytes");
        }
        writeVarUInt32(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeInt32(ByteArrayOutputStream out, int value) {
        out.write(value);
        out.write(value >>> 8);
        out.write(value >>> 16);
        out.write(value >>> 24);
    }

    private static void writeInt64(ByteArrayOutputStream out, long value) {
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            out.write((int) (value >>> shift));
        }
    }

    private static void writeVarUInt32(ByteArrayOutputStream out, int value) {
        if (value < 0) throw new IllegalArgumentException("VarUInt32 value must not be negative");
        do {
            int next = value & 0x7f;
            value >>>= 7;
            out.write(value == 0 ? next : next | 0x80);
        } while (value != 0);
    }

    /** ActorUniqueID is a signed ZigZag VarInt64 in protocol 2225. */
    private static void writeVarInt64(ByteArrayOutputStream out, long value) {
        long encoded = (value << 1) ^ (value >> 63);
        while ((encoded & ~0x7fL) != 0) {
            out.write((int) (encoded & 0x7f) | 0x80);
            encoded >>>= 7;
        }
        out.write((int) encoded);
    }

    public record AddEntry(
            UUID uuid,
            long actorUniqueId,
            String playerName,
            String xuid,
            String playFabId,
            String platformOnlineId,
            int buildPlatform,
            byte[] serializedSkin,
            boolean teacher,
            boolean host,
            boolean subClient,
            int playerColor
    ) {
        public AddEntry {
            Objects.requireNonNull(uuid, "uuid");
            if (playerName == null || playerName.isBlank()) {
                throw new IllegalArgumentException("playerName must not be blank");
            }
            if (playerName.getBytes(StandardCharsets.UTF_8).length > MAX_STRING_BYTES) {
                throw new IllegalArgumentException("playerName is too long");
            }
            if (!BUILD_PLATFORMS.contains(buildPlatform)) {
                throw new IllegalArgumentException("Unsupported Bedrock build platform: " + buildPlatform);
            }
            Objects.requireNonNull(serializedSkin, "serializedSkin");
            if (serializedSkin.length == 0 || serializedSkin.length > MAX_SKIN_BYTES) {
                throw new IllegalArgumentException("serializedSkin must contain 1..2097152 bytes");
            }
            serializedSkin = serializedSkin.clone();
        }

        @Override
        public byte[] serializedSkin() {
            return serializedSkin.clone();
        }
    }
}
