package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetFragmentAssemblerTest {
    @Test
    void returnsUnfragmentedPayloadWithoutHeader() {
        NetherNetFragmentAssembler assembler = new NetherNetFragmentAssembler(32, 8, 1_000_000_000L);

        Optional<ByteBuffer> result = assembler.accept(frame(0, 10, 20));

        assertTrue(result.isPresent());
        assertArrayEquals(new byte[] {10, 20}, bytes(result.orElseThrow()));
        assertTrue(result.orElseThrow().isReadOnly());
    }

    @Test
    void reassemblesCountdownFragmentsInOrder() {
        NetherNetFragmentAssembler assembler = new NetherNetFragmentAssembler(32, 8, 1_000_000_000L);

        assertTrue(assembler.accept(frame(2, 1, 2)).isEmpty());
        assertTrue(assembler.accept(frame(1, 3)).isEmpty());
        Optional<ByteBuffer> result = assembler.accept(frame(0, 4, 5));

        assertTrue(result.isPresent());
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, bytes(result.orElseThrow()));
        assertFalse(assembler.hasIncompleteMessage());
    }

    @Test
    void completedPayloadRemainsStableWhenAssemblerIsReused() {
        NetherNetFragmentAssembler assembler = new NetherNetFragmentAssembler(64, 8, 1_000_000_000L);
        assembler.accept(frame(1, 1, 2));
        ByteBuffer completed = assembler.accept(frame(0, 3)).orElseThrow();

        assembler.accept(frame(1, 8, 9));
        assembler.accept(frame(0, 10));

        assertTrue(completed.isReadOnly());
        assertArrayEquals(new byte[] {1, 2, 3}, bytes(completed));
    }

    @Test
    void rejectsOutOfOrderFragmentAndClearsAssembly() {
        NetherNetFragmentAssembler assembler = new NetherNetFragmentAssembler(32, 8, 1_000_000_000L);
        assembler.accept(frame(2, 1));

        assertThrows(IllegalArgumentException.class, () -> assembler.accept(frame(0, 2)));
        assertFalse(assembler.hasIncompleteMessage());
    }

    @Test
    void rejectsOversizedMessageAndClearsAssembly() {
        NetherNetFragmentAssembler assembler = new NetherNetFragmentAssembler(3, 8, 1000);
        assembler.accept(frame(1, 1, 2));

        assertThrows(IllegalArgumentException.class, () -> assembler.accept(frame(0, 3, 4)));
        assertFalse(assembler.hasIncompleteMessage());
    }

    @Test
    void expiresIncompleteMessage() {
        AtomicLong now = new AtomicLong(10);
        NetherNetFragmentAssembler assembler =
                new NetherNetFragmentAssembler(32, 8, 50, now::get);
        assembler.accept(frame(1, 1));
        now.set(60);

        assertThrows(IllegalArgumentException.class, () -> assembler.accept(frame(0, 2)));
        assertFalse(assembler.hasIncompleteMessage());
    }

    @Test
    void rejectsMissingHeaderAndExcessiveFragmentCount() {
        NetherNetFragmentAssembler assembler = new NetherNetFragmentAssembler(32, 2, 1_000_000_000L);

        assertThrows(IllegalArgumentException.class,
                () -> assembler.accept(ByteBuffer.allocate(0)));
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(frame(2, 1)));
    }

    @Test
    void validatesLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> new NetherNetFragmentAssembler(0, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new NetherNetFragmentAssembler(1, 256, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new NetherNetFragmentAssembler(1, 1, 0));
    }

    private static ByteBuffer frame(int header, int... payload) {
        ByteBuffer buffer = ByteBuffer.allocate(payload.length + 1);
        buffer.put((byte) header);
        for (int value : payload) {
            buffer.put((byte) value);
        }
        return buffer.flip();
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }
}
