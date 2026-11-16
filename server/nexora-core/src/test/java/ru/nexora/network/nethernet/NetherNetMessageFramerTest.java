package ru.nexora.network.nethernet;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetMessageFramerTest {
    @Test
    void wrapsSmallMessageInZeroHeader() {
        byte[] payload = new byte[] {1, 2, 3};
        List<byte[]> frames = NetherNetMessageFramer.fragment(payload, 1200, true);

        assertEquals(1, frames.size());
        assertEquals(0, Byte.toUnsignedInt(frames.getFirst()[0]));
        assertArrayEquals(new byte[] {0, 1, 2, 3}, frames.getFirst());
    }

    @Test
    void fragmentsAndReassemblesReliableMessageInCountdownOrder() {
        byte[] payload = new byte[2500];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i & 0xff);

        List<byte[]> frames = NetherNetMessageFramer.fragment(payload, 1000, true);
        assertEquals(3, frames.size());
        assertEquals(2, Byte.toUnsignedInt(frames.get(0)[0]));
        assertEquals(1, Byte.toUnsignedInt(frames.get(1)[0]));
        assertEquals(0, Byte.toUnsignedInt(frames.get(2)[0]));

        NetherNetMessageFramer.Reassembler reassembler = new NetherNetMessageFramer.Reassembler();
        List<byte[]> completed = new ArrayList<>();
        for (byte[] frame : frames) {
            Optional<byte[]> result = reassembler.accept(frame, true);
            result.ifPresent(completed::add);
        }

        assertEquals(1, completed.size());
        assertArrayEquals(payload, completed.getFirst());
    }

    @Test
    void refusesFragmentationOnUnreliableChannel() {
        byte[] payload = new byte[2000];
        assertThrows(IllegalArgumentException.class,
                () -> NetherNetMessageFramer.fragment(payload, 500, false));
    }

    @Test
    void rejectsUnexpectedFragmentCountdownAndResets() {
        NetherNetMessageFramer.Reassembler reassembler = new NetherNetMessageFramer.Reassembler();
        assertEquals(Optional.empty(), reassembler.accept(new byte[] {2, 10}, true));

        assertThrows(IllegalArgumentException.class,
                () -> reassembler.accept(new byte[] {0, 20}, true));

        assertEquals(Optional.of(new byte[] {7}),
                reassembler.accept(new byte[] {0, 7}, true));
    }

    @Test
    void rejectsFragmentedFrameOnUnreliableChannel() {
        NetherNetMessageFramer.Reassembler reassembler = new NetherNetMessageFramer.Reassembler();
        assertThrows(IllegalArgumentException.class,
                () -> reassembler.accept(new byte[] {1, 5}, false));
    }

    @Test
    void enforcesReassembledMessageLimit() {
        NetherNetMessageFramer.Reassembler reassembler = new NetherNetMessageFramer.Reassembler(3);
        assertEquals(Optional.empty(), reassembler.accept(new byte[] {1, 1, 2}, true));
        assertThrows(IllegalArgumentException.class,
                () -> reassembler.accept(new byte[] {0, 3, 4}, true));
    }
}
