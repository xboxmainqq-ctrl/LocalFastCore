package ru.nexora.network.nethernet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InboundPeerBudgetTest {
    @Test
    void enforcesFrameAndByteCapsWithoutConsumingBudgetOnRejection() {
        InboundPeerBudget budget = new InboundPeerBudget(1_000, 3, 10, 2);

        assertTrue(budget.tryAcquire("peer-a", 4, 10));
        assertTrue(budget.tryAcquire("peer-a", 4, 11));
        assertFalse(budget.tryAcquire("peer-a", 3, 12));
        assertTrue(budget.tryAcquire("peer-a", 2, 13));
        assertFalse(budget.tryAcquire("peer-a", 1, 14));

        InboundPeerBudget.Stats stats = budget.stats();
        assertEquals(3, stats.admittedFrames());
        assertEquals(10, stats.admittedBytes());
        assertEquals(2, stats.rejectedFrames());
        assertEquals(4, stats.rejectedBytes());
    }

    @Test
    void resetsAtWindowBoundaryAndWhenMonotonicClockMovesBackwards() {
        InboundPeerBudget budget = new InboundPeerBudget(100, 1, 100, 2);

        assertTrue(budget.tryAcquire("peer-a", 1, 100));
        assertFalse(budget.tryAcquire("peer-a", 1, 150));
        assertTrue(budget.tryAcquire("peer-a", 1, 200));
        assertTrue(budget.tryAcquire("peer-a", 1, 50));
    }

    @Test
    void boundsPeerTableAndReleasesStateOnDisconnect() {
        InboundPeerBudget budget = new InboundPeerBudget(100, 10, 100, 1);

        assertTrue(budget.tryAcquire("peer-a", 1, 1));
        assertFalse(budget.tryAcquire("peer-b", 1, 2));
        assertEquals(1, budget.stats().trackedPeers());

        budget.onDisconnected("peer-a");

        assertTrue(budget.tryAcquire("peer-b", 1, 3));
        assertEquals(1, budget.stats().trackedPeers());
    }

    @Test
    void rejectsInvalidLimitsAndPeerFrames() {
        assertThrows(IllegalArgumentException.class,
                () -> new InboundPeerBudget(0, 1, 1, 1));
        InboundPeerBudget budget = new InboundPeerBudget();
        assertThrows(IllegalArgumentException.class,
                () -> budget.tryAcquire(" ", 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> budget.tryAcquire("peer", -1, 1));
    }

    @Test
    void pipelineRejectsPeerOverBudgetAndDisconnectResetsPeerState() {
        InboundPeerBudget budget = new InboundPeerBudget(5_000_000_000L, 1, 100, 4);
        NetherNetInboundPipeline pipeline = new NetherNetInboundPipeline(
                message -> { }, new ru.nexora.ai.NexoraAiGuard(), budget, 4, 64);

        pipeline.onFrame("peer-a", "UnreliableDataChannel", new byte[] {0, 1});
        assertThrows(IllegalStateException.class,
                () -> pipeline.onFrame("peer-a", "UnreliableDataChannel", new byte[] {0, 2}));
        assertEquals(1, pipeline.peerBudgetStats().rejectedFrames());

        pipeline.onDisconnected("peer-a");
        assertEquals(0, pipeline.peerBudgetStats().trackedPeers());
        assertDoesNotThrow(
                () -> pipeline.onFrame("peer-a", "UnreliableDataChannel", new byte[] {0, 3}));
    }
    @Test
    void admitsTwoHundredFiftySixPeersConcurrentlyWithoutCrossPeerBudgetLeakage() throws Exception {
        int peers = 256;
        int framesPerPeer = 10;
        InboundPeerBudget budget = new InboundPeerBudget(5_000, 20, 1_000, peers);
        NetherNetInboundPipeline pipeline = new NetherNetInboundPipeline(
                message -> { }, new ru.nexora.ai.NexoraAiGuard(), budget, peers, 64);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(32);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(32);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.List<java.util.concurrent.Future<?>> tasks = new java.util.ArrayList<>();
            for (int peer = 0; peer < peers; peer++) {
                final String peerId = "load-peer-" + peer;
                tasks.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    for (int frame = 0; frame < framesPerPeer; frame++) {
                        pipeline.onFrame(peerId, "UnreliableDataChannel", new byte[] {0, 1, 2});
                    }
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (var task : tasks) task.get();
        } finally {
            executor.shutdownNow();
        }

        assertEquals(peers * framesPerPeer, pipeline.stats().completedMessages());
        assertEquals(peers, pipeline.activeChannelCount());
        assertEquals(peers, pipeline.peerBudgetStats().trackedPeers());
        assertEquals(peers * framesPerPeer, pipeline.peerBudgetStats().admittedFrames());
        assertEquals(0, pipeline.peerBudgetStats().rejectedFrames());
    }

}
