package ru.nexora.network;

import org.junit.jupiter.api.Test;
import ru.nexora.network.bedrock.BedrockSessionManager;
import ru.nexora.world.FlatWorldGenerator;
import ru.nexora.world.World;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class PlayerTransferSourceCoordinatorTest {
    @Test
    void freezeFailureDoesNotUnfreezeWhenAnotherDurableTransferAlreadyExists() throws Exception {
        DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(
                new PlayerTransferCoordinator(new InMemoryPlayerTransferLedger()));
        PlayerHandoff handoff = handoff();
        ledger.prepare(handoff, "source-failure-xuid", 1L, 100L);
        FakeSource source = new FakeSource();
        BedrockSessionManager sessions = sessions();
        try {
            PlayerTransferSourceCoordinator coordinator = new PlayerTransferSourceCoordinator(
                    "source-node", ledger, source, sessions, new FakeTransport(),
                    Duration.ofSeconds(1), () -> 101L);

            assertThrows(IllegalStateException.class,
                    () -> coordinator.transfer(handoff, "source-failure-xuid", "127.0.0.1", 19132));
            assertFalse(source.unfrozen,
                    "A previous PREPARED/CLAIMED transfer must never be undone by a failed duplicate attempt");
        } finally {
            sessions.close();
        }
    }

    @Test
    void freezeFailureCanResumeWhenNoDurableTransferWasPublished() throws Exception {
        DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(
                new PlayerTransferCoordinator(new InMemoryPlayerTransferLedger()));
        FakeSource source = new FakeSource();
        BedrockSessionManager sessions = sessions();
        try {
            PlayerTransferSourceCoordinator coordinator = new PlayerTransferSourceCoordinator(
                    "source-node", ledger, source, sessions, new FakeTransport(),
                    Duration.ofSeconds(1), () -> 101L);

            assertThrows(IOException.class,
                    () -> coordinator.transfer(handoff(), "source-failure-xuid", "127.0.0.1", 19132));
            assertTrue(source.unfrozen,
                    "A source frozen before PREPARED publication should attempt lease-safe recovery");
        } finally {
            sessions.close();
        }
    }

    @Test
    void sourceLeaseReleaseRunsOnlyAfterPreparedIsDurableAndFailureAbortsSafely() throws Exception {
        DurableHandoffCoordinator ledger = new DurableHandoffCoordinator(
                new PlayerTransferCoordinator(new InMemoryPlayerTransferLedger()));
        PlayerHandoff handoff = handoff();
        class PreparedSource implements PlayerTransferLifecycle.Source {
            boolean preparedObserved;
            boolean unfrozen;
            @Override public long freezeAndCheckpoint(PlayerHandoff ignored) {
                return 17L;
            }
            @Override public void unfreeze(PlayerHandoff ignored) {
                unfrozen = true;
            }
            @Override public void onPrepared(PlayerHandoff ignored) throws Exception {
                PlayerTransferRecord record = ledger.get(handoff.transferId()).orElseThrow();
                assertEquals(PlayerTransferRecord.State.PREPARED, record.state());
                assertEquals("prepared-order-xuid", record.playerXuid());
                preparedObserved = true;
                throw new IOException("simulated source lease release failure");
            }
        }
        PreparedSource source = new PreparedSource();
        BedrockSessionManager sessions = sessions();
        try {
            PlayerTransferSourceCoordinator coordinator = new PlayerTransferSourceCoordinator(
                    "source-node", ledger, source, sessions, new FakeTransport(),
                    Duration.ofSeconds(1), () -> 101L);

            assertThrows(IOException.class,
                    () -> coordinator.transfer(handoff, "prepared-order-xuid", "127.0.0.1", 19132));
            assertTrue(source.preparedObserved);
            assertTrue(source.unfrozen, "Source resumes only after the PREPARED record is durably ABORTED");
            assertEquals(PlayerTransferRecord.State.ABORTED,
                    ledger.get(handoff.transferId()).orElseThrow().state());
        } finally {
            sessions.close();
        }
    }

    private static BedrockSessionManager sessions() {
        return new BedrockSessionManager(2225, "1.26.62",
                BedrockSessionManager.DEFAULT_COMPRESSION_THRESHOLD,
                new World("transfer-source-coordinator-test", 0L, new FlatWorldGenerator()),
                (networkId, label, frame) -> {}, event -> {});
    }

    private static PlayerHandoff handoff() {
        return new PlayerHandoff(UUID.randomUUID(), 1L, "source-node", "target-node", new byte[0]);
    }

    private static final class FakeSource implements PlayerTransferLifecycle.Source {
        boolean unfrozen;
        @Override public long freezeAndCheckpoint(PlayerHandoff handoff) throws IOException {
            throw new IOException("simulated source freeze failure");
        }
        @Override public void unfreeze(PlayerHandoff handoff) {
            unfrozen = true;
        }
    }

    private static final class FakeTransport implements ClusterTransport {
        @Override public void start(Consumer<ClusterMessage> receiver) { }
        @Override public java.util.concurrent.CompletionStage<Void> send(ClusterMessage message) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public void close() { }
    }
}
