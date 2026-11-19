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

        assertArrayEquals(new byte[] {7},
                reassembler.accept(new byte[] {0, 7}, true).orElseThrow());
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

    @Test
    void rejectsAndResetsAFragmentAssemblyThatExceedsItsDeadline() {
        NetherNetMessageFramer.Reassembler reassembler =
                new NetherNetMessageFramer.Reassembler(1024, 100L);

        assertEquals(Optional.empty(), reassembler.accept(new byte[] {2, 10}, true, 1_000L));
        assertEquals(Optional.empty(), reassembler.accept(new byte[] {1, 11}, true, 1_050L));
        assertThrows(IllegalArgumentException.class,
                () -> reassembler.accept(new byte[] {0, 12}, true, 1_101L));

        // A timed-out partial message is discarded; a fresh unfragmented message works.
        assertArrayEquals(new byte[] {7},
                reassembler.accept(new byte[] {0, 7}, true, 1_102L).orElseThrow());
    }

    @Test
    void acceptsAssemblyAtTheExactDeadlineBoundary() {
        NetherNetMessageFramer.Reassembler reassembler =
                new NetherNetMessageFramer.Reassembler(1024, 100L);

        assertEquals(Optional.empty(), reassembler.accept(new byte[] {1, 10}, true, 2_000L));
        assertArrayEquals(new byte[] {10, 11},
                reassembler.accept(new byte[] {0, 11}, true, 2_100L).orElseThrow());
    }

    @Test
    void rejectsNonPositiveAssemblyTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> new NetherNetMessageFramer.Reassembler(1024, 0L));
    }

    @Test
    void accountsAllocatedCapacityAndReleasesItWhenAssemblyCompletes() {
        ReassemblyMemoryBudget budget = new ReassemblyMemoryBudget(512);
        NetherNetMessageFramer.Reassembler reassembler =
                new NetherNetMessageFramer.Reassembler(1024, 1_000L, budget);

        assertEquals(Optional.empty(), reassembler.accept(new byte[] {1, 10}, true, 1_000L));
        assertEquals(256, budget.stats().reservedBytes());
        assertArrayEquals(new byte[] {10, 11},
                reassembler.accept(new byte[] {0, 11}, true, 1_001L).orElseThrow());
        assertEquals(0, budget.stats().reservedBytes());
    }

    @Test
    void rejectsGrowthWhenAggregateBudgetIsExhaustedAndReleasesReservation() {
        ReassemblyMemoryBudget budget = new ReassemblyMemoryBudget(300);
        NetherNetMessageFramer.Reassembler first =
                new NetherNetMessageFramer.Reassembler(1024, 1_000L, budget);
        NetherNetMessageFramer.Reassembler second =
                new NetherNetMessageFramer.Reassembler(1024, 1_000L, budget);

        assertEquals(Optional.empty(), first.accept(new byte[] {2, 1}, true, 2_000L));
        assertEquals(256, budget.stats().reservedBytes());
        assertThrows(IllegalStateException.class,
                () -> second.accept(new byte[] {2, 2}, true, 2_001L));
        assertEquals(1, budget.stats().rejectedReservations());

        first.reset();
        assertEquals(0, budget.stats().reservedBytes());
        assertEquals(Optional.empty(), second.accept(new byte[] {2, 2}, true, 2_002L));
        assertEquals(256, budget.stats().reservedBytes());
        second.reset();
        assertEquals(0, budget.stats().reservedBytes());
    }

    @Test
    void releasesAggregateReservationAfterAssemblyTimeout() {
        ReassemblyMemoryBudget budget = new ReassemblyMemoryBudget(512);
        NetherNetMessageFramer.Reassembler reassembler =
                new NetherNetMessageFramer.Reassembler(1024, 10L, budget);

        assertEquals(Optional.empty(), reassembler.accept(new byte[] {1, 10}, true, 3_000L));
        assertEquals(256, budget.stats().reservedBytes());
        assertThrows(IllegalArgumentException.class,
                () -> reassembler.accept(new byte[] {0, 11}, true, 3_011L));
        assertEquals(0, budget.stats().reservedBytes());
    }

    @Test
    void closedReassemblerCannotRetainLateFragmentsAfterDisconnect() {
        ReassemblyMemoryBudget budget = new ReassemblyMemoryBudget(512);
        NetherNetMessageFramer.Reassembler reassembler =
                new NetherNetMessageFramer.Reassembler(1024, 1_000L, budget);
        assertEquals(Optional.empty(), reassembler.accept(new byte[] {1, 10}, true, 4_000L));
        assertEquals(256, budget.stats().reservedBytes());

        reassembler.close();

        assertEquals(0, budget.stats().reservedBytes());
        assertThrows(IllegalStateException.class,
                () -> reassembler.accept(new byte[] {0, 11}, true, 4_001L));
    }

}
