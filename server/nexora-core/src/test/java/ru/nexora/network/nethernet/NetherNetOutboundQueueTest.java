package ru.nexora.network.nethernet;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NetherNetOutboundQueueTest {
    @Test
    void preservesFifoOrderAndOwnsOfferedFrames() {
        NetherNetOutboundQueue queue = new NetherNetOutboundQueue(32, 4);
        byte[] first = new byte[] {1, 2};
        assertTrue(queue.offer(first));
        first[0] = 99;
        assertTrue(queue.offer(new byte[] {3}));
        assertArrayEquals(new byte[] {1, 2}, queue.poll());
        assertArrayEquals(new byte[] {3}, queue.poll());
        assertNull(queue.poll());
        assertEquals(0, queue.queuedBytes());
    }

    @Test
    void rejectsFramesThatWouldExceedByteOrCountLimit() {
        NetherNetOutboundQueue byteLimited = new NetherNetOutboundQueue(3, 10);
        assertTrue(byteLimited.offer(new byte[] {1, 2}));
        assertFalse(byteLimited.offer(new byte[] {3, 4}));
        assertEquals(2, byteLimited.queuedBytes());

        NetherNetOutboundQueue countLimited = new NetherNetOutboundQueue(100, 1);
        assertTrue(countLimited.offer(new byte[] {1}));
        assertFalse(countLimited.offer(new byte[] {2}));
        assertEquals(1, countLimited.frameCount());
    }

    @Test
    void restoresFailedFrameToHeadWithoutChangingOrder() {
        NetherNetOutboundQueue queue = new NetherNetOutboundQueue(32, 4);
        assertTrue(queue.offer(new byte[] {1}));
        assertTrue(queue.offer(new byte[] {2}));
        byte[] inFlight = queue.poll();
        queue.offerFirst(inFlight);
        assertArrayEquals(new byte[] {1}, queue.poll());
        assertArrayEquals(new byte[] {2}, queue.poll());
        assertTrue(queue.isEmpty());
    }

    @Test
    void exposesNextFrameSizeWithoutRemovingFrame() {
        NetherNetOutboundQueue queue = new NetherNetOutboundQueue(32, 4);
        assertEquals(-1, queue.nextFrameSize());
        assertTrue(queue.offer(new byte[] {1, 2, 3}));
        assertEquals(3, queue.nextFrameSize());
        assertEquals(1, queue.frameCount());
        assertEquals(3, queue.queuedBytes());
        assertArrayEquals(new byte[] {1, 2, 3}, queue.poll());
        assertEquals(-1, queue.nextFrameSize());
    }

    @Test
    void clearReleasesAllQueuedAccounting() {
        NetherNetOutboundQueue queue = new NetherNetOutboundQueue(32, 4);
        assertTrue(queue.offer(new byte[] {1, 2, 3}));
        queue.clear();
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.frameCount());
        assertEquals(0, queue.queuedBytes());
    }

    @Test
    void rejectsInvalidLimitsAndEmptyFrames() {
        assertThrows(IllegalArgumentException.class, () -> new NetherNetOutboundQueue(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new NetherNetOutboundQueue(1, 0));
        assertFalse(new NetherNetOutboundQueue().offer(new byte[0]));
    }
}
