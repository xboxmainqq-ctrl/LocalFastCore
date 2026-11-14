package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.UUID;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Encodes player presence and authoritative remote-player positions. */
public final class NexoraPlayerSyncCodec {
    private static final byte[] MAGIC = {'N', 'X', 'R', 'A'};
    private static final int PLAYER_STATE = 9;
    private static final int PLAYER_LEFT = 10;
    private static final int LEFT_BYTES = 23;

    private NexoraPlayerSyncCodec() {
    }

    public record PlayerState(UUID playerId, String playerName, double x, double y, double z, double yaw) {
        public PlayerState(UUID playerId, String playerName, double x, double y, double z) {
            this(playerId, playerName, x, y, z, 0.0);
        }
        public PlayerState(UUID playerId, double x, double y, double z) {
            this(playerId, "Игрок", x, y, z, 0.0);
        }
        public PlayerState {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(playerName, "playerName");
            byte[] name = playerName.getBytes(StandardCharsets.UTF_8);
            if (playerName.isBlank() || playerName.length() > 16 || name.length > 48
                    || !playerName.equals(playerName.trim())) throw new IllegalArgumentException("Invalid player name");
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || Math.abs(x) > 30_000_000d || Math.abs(y) > 30_000_000d
                    || Math.abs(z) > 30_000_000d) throw new IllegalArgumentException("Invalid player coordinates");
            if (!Double.isFinite(yaw) || yaw < -180.0 || yaw > 180.0) throw new IllegalArgumentException("Invalid player yaw");
        }
    }

    public static byte[] encodePlayerState(PlayerState state) {
        Objects.requireNonNull(state, "state");
        byte[] name = state.playerName().getBytes(StandardCharsets.UTF_8);
        ByteBuffer output = header(56 + name.length, PLAYER_STATE);
        output.putLong(state.playerId().getMostSignificantBits());
        output.putLong(state.playerId().getLeastSignificantBits());
        output.put((byte) name.length).put(name);
        output.putDouble(state.x()).putDouble(state.y()).putDouble(state.z()).putDouble(state.yaw());
        return output.array();
    }

    public static PlayerState decodePlayerState(ByteBuffer source) {
        Objects.requireNonNull(source, "source");
        ByteBuffer input = source.slice().order(ByteOrder.BIG_ENDIAN);
        if (input.remaining() < 57) throw new IllegalArgumentException("Invalid player state size");
        for (byte expected : MAGIC) if (input.get() != expected) throw new IllegalArgumentException("Invalid NEXORA packet magic");
        int major = Byte.toUnsignedInt(input.get());
        int minor = Byte.toUnsignedInt(input.get());
        int type = Byte.toUnsignedInt(input.get());
        if (major != 1 || minor != 0 || type != PLAYER_STATE) throw new IllegalArgumentException("Unexpected NEXORA player state");
        UUID id = new UUID(input.getLong(), input.getLong());
        int nameLength = Byte.toUnsignedInt(input.get());
        if (nameLength < 1 || nameLength > 48 || input.remaining() != nameLength + 32) throw new IllegalArgumentException("Invalid player name length");
        byte[] name = new byte[nameLength];
        input.get(name);
        final String playerName;
        try {
            playerName = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(name)).toString();
        } catch (CharacterCodingException invalidName) {
            throw new IllegalArgumentException("Player name is not valid UTF-8", invalidName);
        }
        return new PlayerState(id, playerName, input.getDouble(), input.getDouble(), input.getDouble(), input.getDouble());
    }

    public static byte[] encodePlayerLeft(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        ByteBuffer output = header(LEFT_BYTES, PLAYER_LEFT);
        output.putLong(playerId.getMostSignificantBits());
        output.putLong(playerId.getLeastSignificantBits());
        return output.array();
    }

    public static UUID decodePlayerLeft(ByteBuffer source) {
        ByteBuffer input = checked(source, LEFT_BYTES, PLAYER_LEFT);
        return new UUID(input.getLong(), input.getLong());
    }

    private static ByteBuffer header(int bytes, int type) {
        ByteBuffer output = ByteBuffer.allocate(bytes).order(ByteOrder.BIG_ENDIAN);
        output.put(MAGIC).put((byte) 1).put((byte) 0).put((byte) type);
        return output;
    }

    private static ByteBuffer checked(ByteBuffer source, int bytes, int type) {
        Objects.requireNonNull(source, "source");
        ByteBuffer input = source.slice().order(ByteOrder.BIG_ENDIAN);
        if (input.remaining() != bytes) {
            throw new IllegalArgumentException("Invalid NEXORA player sync packet size");
        }
        for (byte expected : MAGIC) {
            if (input.get() != expected) throw new IllegalArgumentException("Invalid NEXORA packet magic");
        }
        int major = Byte.toUnsignedInt(input.get());
        int minor = Byte.toUnsignedInt(input.get());
        int actualType = Byte.toUnsignedInt(input.get());
        if (major != 1 || minor != 0 || actualType != type) {
            throw new IllegalArgumentException("Unexpected NEXORA player sync packet");
        }
        return input;
    }
}
