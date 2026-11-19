package ru.nexora.network.nethernet;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetInboundPipelineTest {
    @Test
    void reassemblesReliableChannelAndKeepsUnreliableMessagesUnfragmented() {
        List<NetherNetInboundPipeline.InboundMessage> received = new ArrayList<>();
        NetherNetInboundPipeline pipeline = new NetherNetInboundPipeline(received::add);

        byte[] reliablePayload = new byte[1800];
        for (int i = 0; i < reliablePayload.length; i++) reliablePayload[i] = (byte) i;
        List<byte[]> fragments = NetherNetMessageFramer.fragment(reliablePayload, 600, true);

        for (byte[] fragment : fragments) {
            pipeline.onFrame("player-a", "ReliableDataChannel", fragment);
        }

        pipeline.onFrame("player-a", "UnreliableDataChannel", new byte[] {0, 9, 8, 7});

        assertEquals(2, received.size());
        assertTrue(received.get(0).reliable());
        assertArrayEquals(reliablePayload, received.get(0).payload());
        assertFalse(received.get(1).reliable());
        assertArrayEquals(new byte[] {9, 8, 7}, received.get(1).payload());
        assertEquals(2, pipeline.activeChannelCount());
        NetherNetInboundPipeline.InboundStats stats = pipeline.stats();
        assertEquals(fragments.size() + 1L, stats.frames());
        assertEquals(2, stats.completedMessages());
        assertEquals(0, stats.failedFrames());
        assertEquals(2, stats.activeChannels());
        assertTrue(stats.bytes() > reliablePayload.length);
        assertTrue(stats.totalProcessingNanos() >= stats.maxProcessingNanos());
        assertTrue(stats.averageProcessingNanos() >= 0);
    }

    @Test
    void processesDifferentPeerChannelsConcurrently() throws Exception {
        AtomicInteger delivered = new AtomicInteger();
        NetherNetInboundPipeline pipeline = new NetherNetInboundPipeline(
                message -> delivered.incrementAndGet(),
                new ru.nexora.ai.NexoraAiGuard(), 16, 64);
        int peers = 8;
        int framesPerPeer = 4;
        CountDownLatch ready = new CountDownLatch(peers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(peers);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int peer = 0; peer < peers; peer++) {
                String networkId = "parallel-peer-" + peer;
                tasks.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    for (int frame = 0; frame < framesPerPeer; frame++) {
                        pipeline.onFrame(networkId, "UnreliableDataChannel", new byte[] {0, 1});
                    }
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> task : tasks) task.get();
        } finally {
            executor.shutdownNow();
        }

        assertEquals(peers * framesPerPeer, delivered.get());
        assertEquals(peers, pipeline.activeChannelCount());
        assertEquals(peers * framesPerPeer, pipeline.stats().completedMessages());
        assertEquals(0, pipeline.stats().failedFrames());
    }

    @Test
    void clearsPerChannelStateWhenPeerDisconnects() {
        NetherNetInboundPipeline pipeline = new NetherNetInboundPipeline(message -> fail("No complete message expected"));
        pipeline.onFrame("player-b", "ReliableDataChannel", new byte[] {1, 5, 6});
        assertEquals(1, pipeline.activeChannelCount());

        pipeline.onDisconnected("player-b");

        assertEquals(0, pipeline.activeChannelCount());
        assertEquals(0, pipeline.stats().activeChannels());
    }

    @Test
    void recordsFailedDispatchWithoutCountingItAsCompleted() {
        NetherNetInboundPipeline pipeline = new NetherNetInboundPipeline(message -> {
            throw new IllegalStateException("test sink failure");
        });

        assertThrows(IllegalStateException.class,
                () -> pipeline.onFrame("player-failure", "UnreliableDataChannel",
                        new byte[] {0, 1, 2}));

        NetherNetInboundPipeline.InboundStats stats = pipeline.stats();
        assertEquals(1, stats.frames());
        assertEquals(1, stats.failedFrames());
        assertEquals(0, stats.completedMessages());
        assertTrue(stats.lastProcessingNanos() >= 0);
    }

    @Test
    void rejectsUnknownChannelLabels() {
        NetherNetInboundPipeline pipeline = new NetherNetInboundPipeline(message -> fail("No message expected"));
        assertThrows(IllegalArgumentException.class,
                () -> pipeline.onFrame("player-c", "UnknownChannel", new byte[] {0, 1}));
    }

    @Test
    void rejectsOversizedFrameBeforeCreatingChannelState() {
        NetherNetInboundPipeline pipeline = new NetherNetInboundPipeline(
                message -> fail("Oversized frame must not be dispatched"),
                new ru.nexora.ai.NexoraAiGuard(), 2, 4);

        assertThrows(IllegalArgumentException.class,
                () -> pipeline.onFrame("player-large", "ReliableDataChannel",
                        new byte[] {0, 1, 2, 3, 4}));
        assertEquals(0, pipeline.activeChannelCount());
        assertEquals(1, pipeline.stats().frames());
        assertEquals(1, pipeline.stats().failedFrames());
    }

    @Test
    void boundsTrackedChannelStateAndReleasesCapacityOnDisconnect() {
        NetherNetInboundPipeline pipeline = new NetherNetInboundPipeline(
                message -> { }, new ru.nexora.ai.NexoraAiGuard(), 1, 64);

        pipeline.onFrame("player-one", "ReliableDataChannel", new byte[] {1, 5});
        assertThrows(IllegalStateException.class,
                () -> pipeline.onFrame("player-two", "ReliableDataChannel", new byte[] {1, 6}));
        assertEquals(1, pipeline.activeChannelCount());

        pipeline.onDisconnected("player-one");
        pipeline.onFrame("player-two", "ReliableDataChannel", new byte[] {1, 6});
        assertEquals(1, pipeline.activeChannelCount());
    }

    @Test
    void rejectsBlankNetworkIdentityAndInvalidPipelineLimits() {
        NetherNetInboundPipeline pipeline = new NetherNetInboundPipeline(message -> { });
        assertThrows(IllegalArgumentException.class,
                () -> pipeline.onFrame(" ", "ReliableDataChannel", new byte[] {0, 1}));
        assertThrows(IllegalArgumentException.class,
                () -> new NetherNetInboundPipeline(message -> { },
                        new ru.nexora.ai.NexoraAiGuard(), 0, 64));
        assertThrows(IllegalArgumentException.class,
                () -> new NetherNetInboundPipeline(message -> { },
                        new ru.nexora.ai.NexoraAiGuard(), 1,
                        NetherNetInboundPipeline.MAX_INBOUND_FRAME_BYTES + 1));
    }

}
