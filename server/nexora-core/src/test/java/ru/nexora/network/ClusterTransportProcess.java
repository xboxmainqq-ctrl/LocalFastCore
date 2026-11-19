package ru.nexora.network;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Small child-JVM endpoint used to exercise the real cluster TCP boundary. */
public final class ClusterTransportProcess {
    private ClusterTransportProcess() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 6) throw new IllegalArgumentException("Expected node, bind port, peer, peer port, key and mode");
        String node = args[0];
        int bindPort = Integer.parseInt(args[1]);
        String peer = args[2];
        int peerPort = Integer.parseInt(args[3]);
        byte[] key = Base64.getDecoder().decode(args[4]);
        String mode = args[5];
        CountDownLatch received = new CountDownLatch(1);
        try (TcpClusterTransport transport = new TcpClusterTransport(
                node,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), bindPort),
                Map.of(peer, new InetSocketAddress(InetAddress.getLoopbackAddress(), peerPort)),
                Set.of(peer),
                new AuthenticatedClusterMessageCodec(key, 30_000L),
                new ClusterReplayGuard(128, 30_000L),
                Duration.ofSeconds(3), Duration.ofSeconds(3), 1, 8)) {
            transport.start(message -> {
                System.out.println("RECEIVED:" + message.sourceNode() + ":" + message.type()
                        + ":" + java.util.HexFormat.of().formatHex(message.payload()));
                System.out.flush();
                received.countDown();
            });
            if ("listen".equals(mode)) {
                System.out.println("READY:" + node);
                System.out.flush();
                if (!received.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for peer message");
            } else if ("send".equals(mode)) {
                ClusterMessage message = new ClusterMessage(UUID.randomUUID(), node, peer,
                        "CLUSTER_INTEGRATION_PROBE", System.currentTimeMillis(), new byte[] {1, 2, 3});
                transport.send(message).toCompletableFuture().get(6, TimeUnit.SECONDS);
                System.out.println("SENT:" + peer);
                System.out.flush();
            } else {
                throw new IllegalArgumentException("Unknown process mode");
            }
        }
    }
}
