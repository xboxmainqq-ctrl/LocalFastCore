package ru.nexora.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetMessageFramingTest {
    @Test
    void encodesSingleReliableMessageWithZeroHeader() {
        List<ByteBuffer> frames = NetherNetMessageFraming.fragmentReliable(
                ByteBuffer.wrap(new byte[] {1, 2, 3}), 8, 8);

        assertEquals(1, frames.size());
        assertArrayEquals(new byte[] {0, 1, 2, 3}, bytes(frames.get(0)));
    }

    @Test
    void fragmentsReliablePayloadWithDescendingCountdownHeaders() {
        List<ByteBuffer> frames = NetherNetMessageFraming.fragmentReliable(
                ByteBuffer.wrap(new byte[] {1, 2, 3, 4, 5}), 3, 8);

        assertEquals(3, frames.size());
        assertArrayEquals(new byte[] {2, 1, 2}, bytes(frames.get(0)));
        assertArrayEquals(new byte[] {1, 3, 4}, bytes(frames.get(1)));
        assertArrayEquals(new byte[] {0, 5}, bytes(frames.get(2)));

        NetherNetFragmentAssembler assembler = new NetherNetFragmentAssembler(16, 8, 1_000_000_000L);
        ByteBuffer result = null;
        for (ByteBuffer frame : frames) {
            var completed = assembler.accept(frame);
            if (completed.isPresent()) result = completed.orElseThrow();
        }
        assertNotNull(result);
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, bytes(result));
    }

    @Test
    void doesNotChangeCallerBufferPosition() {
        ByteBuffer payload = ByteBuffer.wrap(new byte[] {9, 1, 2, 8});
        payload.position(1);
        payload.limit(3);

        NetherNetMessageFraming.fragmentReliable(payload, 2, 8);

        assertEquals(1, payload.position());
        assertEquals(3, payload.limit());
    }

    @Test
    void rejectsFragmentLimitAndInvalidFrameSize() {
        assertThrows(IllegalArgumentException.class,
                () -> NetherNetMessageFraming.fragmentReliable(ByteBuffer.allocate(8), 2, 2));
        assertThrows(IllegalArgumentException.class,
                () -> NetherNetMessageFraming.fragmentReliable(ByteBuffer.allocate(1), 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> NetherNetMessageFraming.fragmentReliable(ByteBuffer.allocate(1), 2, 256));
    }

    @Test
    void decodesOnlyUnfragmentedUnreliableMessages() {
        ByteBuffer decoded = NetherNetMessageFraming.decodeUnreliable(
                ByteBuffer.wrap(new byte[] {0, 7, 8}), 8);

        assertTrue(decoded.isReadOnly());
        assertArrayEquals(new byte[] {7, 8}, bytes(decoded));
        assertThrows(IllegalArgumentException.class,
                () -> NetherNetMessageFraming.decodeUnreliable(ByteBuffer.wrap(new byte[] {1, 7}), 8));
        assertThrows(IllegalArgumentException.class,
                () -> NetherNetMessageFraming.decodeUnreliable(ByteBuffer.allocate(0), 8));
        assertThrows(IllegalArgumentException.class,
                () -> NetherNetMessageFraming.decodeUnreliable(ByteBuffer.wrap(new byte[] {0, 1, 2}), 2));
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }
}
