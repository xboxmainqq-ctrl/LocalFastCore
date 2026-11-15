package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Versioned, bounded first-party block interaction messages. */
public final class NexoraBlockActionCodec {
    private static final byte[] MAGIC = {'N','X','R','A'};
    private static final int REQUEST = 14;
    private static final int UPDATE = 15;
    private NexoraBlockActionCodec() { }

    public enum Action { BREAK(1), PLACE(2);
        final int wire;
        Action(int wire) { this.wire = wire; }
        static Action fromWire(int value) {
            for (Action action : values()) if (action.wire == value) return action;
            throw new IllegalArgumentException("Unknown block action");
        }
    }
    public record Request(Action action, int x, int y, int z, String blockIdentifier) { }
    public record Update(int x, int y, int z, String blockIdentifier) { }

    public static byte[] encodeRequest(Request request) {
        Objects.requireNonNull(request, "request");
        byte[] id = request.action() == Action.PLACE
                ? request.blockIdentifier().getBytes(StandardCharsets.UTF_8) : new byte[0];
        if (request.action() == Action.PLACE && (id.length < 1 || id.length > 128))
            throw new IllegalArgumentException("Invalid block identifier length");
        ByteBuffer out = header(REQUEST, 14 + id.length);
        out.put((byte) request.action().wire).putInt(request.x()).putInt(request.y())
                .putInt(request.z()).put((byte) id.length).put(id);
        return out.array();
    }

    public static Request decodeRequest(ByteBuffer source) {
        ByteBuffer in = checked(source, REQUEST);
        if (in.remaining() < 14) throw new IllegalArgumentException("Truncated block action");
        Action action = Action.fromWire(Byte.toUnsignedInt(in.get()));
        int x = in.getInt(), y = in.getInt(), z = in.getInt();
        int length = Byte.toUnsignedInt(in.get());
        if (length > 128 || in.remaining() != length
                || (action == Action.BREAK && length != 0)
                || (action == Action.PLACE && length == 0))
            throw new IllegalArgumentException("Malformed block action");
        byte[] id = new byte[length];
        in.get(id);
        String identifier = decodeUtf8(id);
        if (action == Action.PLACE) new BlockState(identifier);
        return new Request(action, x, y, z, identifier);
    }

    public static byte[] encodeUpdate(Update update) {
        Objects.requireNonNull(update, "update");
        byte[] id = update.blockIdentifier().getBytes(StandardCharsets.UTF_8);
        if (id.length > 128) throw new IllegalArgumentException("Block identifier is too long");
        ByteBuffer out = header(UPDATE, 13 + id.length);
        out.putInt(update.x()).putInt(update.y()).putInt(update.z()).put((byte) id.length).put(id);
        return out.array();
    }

    public static Update decodeUpdate(ByteBuffer source) {
        ByteBuffer in = checked(source, UPDATE);
        if (in.remaining() < 13) throw new IllegalArgumentException("Truncated block update");
        int x = in.getInt(), y = in.getInt(), z = in.getInt();
        int length = Byte.toUnsignedInt(in.get());
        if (length > 128 || in.remaining() != length)
            throw new IllegalArgumentException("Malformed block update");
        byte[] id = new byte[length];
        in.get(id);
        String identifier = decodeUtf8(id);
        if (!identifier.isEmpty()) new BlockState(identifier);
        return new Update(x, y, z, identifier);
    }

    private static ByteBuffer header(int type, int payload) {
        ByteBuffer out = ByteBuffer.allocate(7 + payload).order(ByteOrder.BIG_ENDIAN);
        out.put(MAGIC).put((byte) NexoraPrivateProtocolCodec.MAJOR_VERSION)
                .put((byte) NexoraPrivateProtocolCodec.MINOR_VERSION).put((byte) type);
        return out;
    }
    private static ByteBuffer checked(ByteBuffer source, int type) {
        ByteBuffer in = source.slice().order(ByteOrder.BIG_ENDIAN);
        if (in.remaining() < 7 || in.remaining() > 256)
            throw new IllegalArgumentException("Invalid block action message size");
        for (byte expected : MAGIC) if (in.get() != expected)
            throw new IllegalArgumentException("Invalid NEXORA magic");
        int major = Byte.toUnsignedInt(in.get()), minor = Byte.toUnsignedInt(in.get());
        if (major != NexoraPrivateProtocolCodec.MAJOR_VERSION
                || minor != NexoraPrivateProtocolCodec.MINOR_VERSION
                || Byte.toUnsignedInt(in.get()) != type)
            throw new IllegalArgumentException("Unexpected block action version or type");
        return in;
    }
    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("Invalid UTF-8 block identifier", invalid);
        }
    }
}
