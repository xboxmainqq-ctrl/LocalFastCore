package ru.nexora.network.nethernet;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
    }

    @Test
    void clearsPerChannelStateWhenPeerDisconnects() {
        NetherNetInboundPipeline pipeline = new NetherNetInboundPipeline(message -> fail("No complete message expected"));
        pipeline.onFrame("player-b", "ReliableDataChannel", new byte[] {1, 5, 6});
        assertEquals(1, pipeline.activeChannelCount());

        pipeline.onDisconnected("player-b");

        assertEquals(0, pipeline.activeChannelCount());
    }

    @Test
    void rejectsUnknownChannelLabels() {
        NetherNetInboundPipeline pipeline = new NetherNetInboundPipeline(message -> fail("No message expected"));
        assertThrows(IllegalArgumentException.class,
                () -> pipeline.onFrame("player-c", "UnknownChannel", new byte[] {0, 1}));
    }
}
