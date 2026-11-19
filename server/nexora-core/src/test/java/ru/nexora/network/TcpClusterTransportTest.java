package ru.nexora.network;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TcpClusterTransportTest {
    @Test
    void sendsAuthenticatedMessageAndRejectsReplay() throws Exception {
        int[] ports = freePorts();
        int portA = ports[0];
        int portB = ports[1];
        byte[] secret = new byte[32];
        java.util.Arrays.fill(secret, (byte) 7);
        AuthenticatedClusterMessageCodec codecA = new AuthenticatedClusterMessageCodec(secret, 30_000);
        AuthenticatedClusterMessageCodec codecB = new AuthenticatedClusterMessageCodec(secret, 30_000);
        TcpClusterTransport nodeA = new TcpClusterTransport(
                "node-a", loopback(portA), Map.of("node-b", loopback(portB)), Set.of("node-b"),
                codecA, new ClusterReplayGuard(100, 30_000),
                Duration.ofSeconds(2), Duration.ofSeconds(2), 2, 8);
        TcpClusterTransport nodeB = new TcpClusterTransport(
                "node-b", loopback(portB), Map.of("node-a", loopback(portA)), Set.of("node-a"),
                codecB, new ClusterReplayGuard(100, 30_000),
                Duration.ofSeconds(2), Duration.ofSeconds(2), 2, 8);
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<ClusterMessage> observed = new AtomicReference<>();
        try {
            nodeB.start(message -> {
                observed.set(message);
                received.countDown();
            });
            nodeA.start(message -> fail("node-a must not receive a message"));
            ClusterMessage message = new ClusterMessage(UUID.randomUUID(), "node-a", "node-b",
                    "PLAYER_HANDOFF_PREPARE", System.currentTimeMillis(), new byte[] {1, 2, 3});

            nodeA.send(message).toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertTrue(received.await(1, TimeUnit.SECONDS));
            assertEquals(message.messageId(), observed.get().messageId());
            assertArrayEquals(message.payload(), observed.get().payload());

            assertThrows(ExecutionException.class,
                    () -> nodeA.send(message).toCompletableFuture().get(3, TimeUnit.SECONDS));
        } finally {
            nodeA.close();
            nodeB.close();
        }
    }

    @Test
    void rejectsMessagesWithWrongLocalSourceIdentity() throws Exception {
        int port = freePort();
        byte[] secret = new byte[32];
        TcpClusterTransport transport = new TcpClusterTransport(
                "node-a", loopback(port), Map.of("node-b", loopback(freePort())), Set.of("node-b"),
                new AuthenticatedClusterMessageCodec(secret, 30_000),
                new ClusterReplayGuard(10, 30_000),
                Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 2);
        try {
            transport.start(message -> { });
            ClusterMessage forged = new ClusterMessage(UUID.randomUUID(), "node-c", "node-b",
                    "PING", System.currentTimeMillis(), new byte[0]);
            assertThrows(java.util.concurrent.CompletionException.class,
                    () -> transport.send(forged).toCompletableFuture().join());
        } finally {
            transport.close();
        }
    }

    private static InetSocketAddress loopback(int port) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }

    private static int[] freePorts() throws Exception {
        try (ServerSocket first = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             ServerSocket second = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return new int[] {first.getLocalPort(), second.getLocalPort()};
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
