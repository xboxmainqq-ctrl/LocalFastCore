package ru.nexora.network;

import org.h2.jdbcx.JdbcDataSource;
import ru.nexora.persistence.JdbcPlayerStateStore;
import ru.nexora.persistence.PlayerStateBundle;
import ru.nexora.persistence.PlayerStateBundleCodec;
import ru.nexora.persistence.PlayerStateCheckpointService;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Destination JVM for the end-to-end durable player-transfer integration test. */
public final class PlayerTransferDestinationProcess {
    private PlayerTransferDestinationProcess() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 8) throw new IllegalArgumentException(
                "Expected node, bind port, peer, peer port, key, JDBC URL, transfer ID and XUID");
        String node = args[0];
        int bindPort = Integer.parseInt(args[1]);
        String peer = args[2];
        int peerPort = Integer.parseInt(args[3]);
        byte[] key = Base64.getDecoder().decode(args[4]);
        String jdbcUrl = args[5];
        UUID transferId = UUID.fromString(args[6]);
        String xuid = args[7];

        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(jdbcUrl);
        JdbcPlayerTransferLedger storage = new JdbcPlayerTransferLedger(dataSource);
        storage.initializeSchema();
        DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(
                new PlayerTransferCoordinator(storage));
        PlayerStateCheckpointService checkpoints = new PlayerStateCheckpointService(
                new JdbcPlayerStateStore(dataSource), new PlayerStateBundleCodec());

        AtomicReference<String> activation = new AtomicReference<>();
        CheckpointedPlayerTransferDestination destination = new CheckpointedPlayerTransferDestination(
                ledger, checkpoints, node, new CheckpointedPlayerTransferDestination.Activator() {
                    @Override
                    public boolean restoreAndActivate(PlayerHandoff handoff, PlayerStateBundle bundle,
                                                       PlayerTransferFence fence) throws Exception {
                        fence.assertCurrent();
                        if (!xuid.equals(bundle.profile().xuid())
                                || !transferId.equals(handoff.transferId())
                                || !node.equals(handoff.targetNode())) {
                            throw new SecurityException("Destination received a mismatched player checkpoint");
                        }
                        activation.set("ACTIVATED:" + bundle.profile().xuid()
                                + ":" + fence.playerRevision() + ":" + fence.fencingToken());
                        return true;
                    }

                    @Override
                    public void deactivate(PlayerHandoff handoff, PlayerTransferFence fence) { }
                }, System::currentTimeMillis);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (TcpClusterTransport transport = new TcpClusterTransport(
                node, new InetSocketAddress(InetAddress.getLoopbackAddress(), bindPort),
                Map.of(peer, new InetSocketAddress(InetAddress.getLoopbackAddress(), peerPort)),
                Set.of(peer), new AuthenticatedClusterMessageCodec(key, 30_000L),
                new ClusterReplayGuard(128, 30_000L), Duration.ofSeconds(3),
                Duration.ofSeconds(8), 1, 8)) {
            PlayerTransferClusterEndpoint endpoint = new PlayerTransferClusterEndpoint(
                    node, ledger, destination, 10_000L, System::currentTimeMillis);
            transport.start(message -> {
                try {
                    endpoint.handle(message);
                    PlayerTransferRecord committed = ledger.get(transferId).orElseThrow();
                    if (committed.state() != PlayerTransferRecord.State.COMMITTED) {
                        throw new IllegalStateException("Transfer ACK would precede durable COMMITTED state");
                    }
                    System.out.println(activation.get() + ":COMMITTED:" + committed.fencingToken());
                    System.out.flush();
                } catch (Throwable error) {
                    failure.set(error);
                    System.out.println("FAILED:" + error.getClass().getSimpleName() + ":" + error.getMessage());
                    System.out.flush();
                } finally {
                    completed.countDown();
                }
            });
            System.out.println("READY:" + node);
            System.out.flush();
            if (!completed.await(12, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for end-to-end player transfer");
            }
            if (failure.get() != null) throw new IllegalStateException("Destination rejected transfer", failure.get());
        }
    }
}
