package ru.nexora.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Client inventory actions and server acknowledgements (message types 12 and 13). */
public final class NexoraInventoryActionCodec {
    private static final byte[] MAGIC = {'N', 'X', 'R', 'A'};
    private static final int ACTION_TYPE = 12;
    private static final int RESULT_TYPE = 13;

    private NexoraInventoryActionCodec() {
    }

    public enum Action {
        MOVE_OR_SWAP(1), DROP_ONE(2), DROP_STACK(3), USE(4);

        private final int wireId;
        Action(int wireId) { this.wireId = wireId; }

        static Action fromWire(int value) {
            for (Action action : values()) if (action.wireId == value) return action;
            throw new IllegalArgumentException("Unknown inventory action");
        }
    }

    public enum Result {
        OK(0), INVALID_SLOT(1), EMPTY_SLOT(2), NOT_USABLE(3);

        private final int wireId;
        Result(int wireId) { this.wireId = wireId; }

        static Result fromWire(int value) {
            for (Result result : values()) if (result.wireId == value) return result;
            throw new IllegalArgumentException("Unknown inventory action result");
        }
    }

    public record Request(Action action, int sourceSlot, int targetSlot) {
        public Request {
            Objects.requireNonNull(action, "action");
            if (sourceSlot < 0 || sourceSlot >= PlayerInventory.DEFAULT_SLOTS
                    || targetSlot < 0 || targetSlot >= PlayerInventory.DEFAULT_SLOTS) {
                throw new IllegalArgumentException("Inventory slot is outside bounds");
            }
        }
    }

    public static Request decodeRequest(ByteBuffer source) {
        Objects.requireNonNull(source, "source");
        ByteBuffer input = source.slice().order(ByteOrder.BIG_ENDIAN);
        checkHeader(input, ACTION_TYPE);
        if (input.remaining() != 3) throw new IllegalArgumentException("Malformed inventory action");
        return new Request(Action.fromWire(Byte.toUnsignedInt(input.get())),
                Byte.toUnsignedInt(input.get()), Byte.toUnsignedInt(input.get()));
    }

    public static byte[] encodeResult(Action action, Result result) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(result, "result");
        return ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN)
                .put(MAGIC).put((byte) NexoraPrivateProtocolCodec.MAJOR_VERSION).put((byte) NexoraPrivateProtocolCodec.MINOR_VERSION).put((byte) RESULT_TYPE)
                .put((byte) action.wireId).put((byte) result.wireId).array();
    }

    public static byte[] encodeRequest(Action action, int sourceSlot, int targetSlot) {
        Request request = new Request(action, sourceSlot, targetSlot);
        return ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
                .put(MAGIC).put((byte) NexoraPrivateProtocolCodec.MAJOR_VERSION).put((byte) NexoraPrivateProtocolCodec.MINOR_VERSION).put((byte) ACTION_TYPE)
                .put((byte) request.action().wireId)
                .put((byte) request.sourceSlot()).put((byte) request.targetSlot()).array();
    }

    private static void checkHeader(ByteBuffer input, int type) {
        if (input.remaining() < 7) throw new IllegalArgumentException("Truncated inventory action packet");
        for (byte expected : MAGIC) {
            if (input.get() != expected) throw new IllegalArgumentException("Invalid NEXORA packet magic");
        }
        if (Byte.toUnsignedInt(input.get()) != NexoraPrivateProtocolCodec.MAJOR_VERSION
                || Byte.toUnsignedInt(input.get()) != NexoraPrivateProtocolCodec.MINOR_VERSION
                || Byte.toUnsignedInt(input.get()) != type) {
            throw new IllegalArgumentException("Unexpected inventory action packet");
        }
    }
}
