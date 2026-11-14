package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.UUID;

/** Encodes player presence and authoritative remote-player positions. */
public final class NexoraPlayerSyncCodec {
    private static final byte[] MAGIC = {'N', 'X', 'R', 'A'};
    private static final int PLAYER_STATE = 9;
    private static final int PLAYER_LEFT = 10;
    private static final int STATE_BYTES = 47;
    private static final int LEFT_BYTES = 23;

    private NexoraPlayerSyncCodec() {
    }

    public record PlayerState(UUID playerId, double x, double y, double z) {
        public PlayerState {
            Objects.requireNonNull(playerId, "playerId");
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || Math.abs(x) > 30_000_000d || Math.abs(y) > 30_000_000d
                    || Math.abs(z) > 30_000_000d) {
                throw new IllegalArgumentException("Invalid player coordinates");
            }
        }
    }

    public static byte[] encodePlayerState(PlayerState state) {
        Objects.requireNonNull(state, "state");
        ByteBuffer output = header(STATE_BYTES, PLAYER_STATE);
        output.putLong(state.playerId().getMostSignificantBits());
        output.putLong(state.playerId().getLeastSignificantBits());
        output.putDouble(state.x()).putDouble(state.y()).putDouble(state.z());
        return output.array();
    }

    public static PlayerState decodePlayerState(ByteBuffer source) {
        ByteBuffer input = checked(source, STATE_BYTES, PLAYER_STATE);
        UUID id = new UUID(input.getLong(), input.getLong());
        return new PlayerState(id, input.getDouble(), input.getDouble(), input.getDouble());
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
