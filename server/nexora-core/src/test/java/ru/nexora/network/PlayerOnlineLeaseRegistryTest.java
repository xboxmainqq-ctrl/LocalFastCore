package ru.nexora.network;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class PlayerOnlineLeaseRegistryTest {
    @Test
    void inMemoryLeaseRejectsOtherNodeUntilExpiryAndFencesOldSession() throws Exception {
        InMemoryPlayerOnlineLeaseRegistry leases = new InMemoryPlayerOnlineLeaseRegistry();
        assertTrue(leases.acquire("xuid-1", "node-a", "session-a", 100L, 1_000L));
        assertFalse(leases.acquire("xuid-1", "node-b", "session-b", 200L, 1_000L));
        assertFalse(leases.renew("xuid-1", "node-a", "session-a", 1_101L, 1_000L));
        assertTrue(leases.acquire("xuid-1", "node-b", "session-b", 1_101L, 1_000L));
        assertFalse(leases.release("xuid-1", "node-a", "session-a"));
        assertFalse(leases.renew("xuid-1", "node-a", "session-a", 1_102L, 1_000L));
        assertTrue(leases.renew("xuid-1", "node-b", "session-b", 1_102L, 1_000L));
        assertTrue(leases.release("xuid-1", "node-b", "session-b"));
    }

    @Test
    void jdbcLeaseIsExclusiveAcrossIndependentNodeRegistries() throws Exception {
        JdbcDataSource firstSource = dataSource("jdbc:h2:mem:online-lease;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(100L);
        JdbcPlayerOnlineLeaseRegistry first = new JdbcPlayerOnlineLeaseRegistry(firstSource, clock::get);
        first.initializeSchema();
        JdbcPlayerOnlineLeaseRegistry second = new JdbcPlayerOnlineLeaseRegistry(
                dataSource("jdbc:h2:mem:online-lease;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"), clock::get);

        assertTrue(first.acquire("xuid-2", "node-a", "session-a", 100L, 1_000L));
        assertFalse(second.acquire("xuid-2", "node-b", "session-b", 200L, 1_000L));
        clock.set(300L);
        assertTrue(second.renew("xuid-2", "node-a", "session-a", 300L, 1_000L));
        assertFalse(second.release("xuid-2", "node-b", "session-b"));
        clock.set(1_301L);
        assertTrue(second.acquire("xuid-2", "node-b", "session-b", 1_301L, 1_000L));
        clock.set(1_302L);
        assertFalse(first.renew("xuid-2", "node-a", "session-a", 1_302L, 1_000L));
        assertFalse(first.release("xuid-2", "node-a", "session-a"));
        assertTrue(first.release("xuid-2", "node-b", "session-b"));
    }

    @Test
    void jdbcLeaseExpiryUsesSharedDatabaseClockInsteadOfNodeSuppliedTime() throws Exception {
        String url = "jdbc:h2:mem:online-lease-db-clock;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        java.util.concurrent.atomic.AtomicLong databaseClock = new java.util.concurrent.atomic.AtomicLong(1_000L);
        JdbcPlayerOnlineLeaseRegistry nodeA =
                new JdbcPlayerOnlineLeaseRegistry(dataSource(url), databaseClock::get);
        nodeA.initializeSchema();
        JdbcPlayerOnlineLeaseRegistry nodeB =
                new JdbcPlayerOnlineLeaseRegistry(dataSource(url), databaseClock::get);

        assertTrue(nodeA.acquire("xuid-db-clock", "node-a", "session-a", 9_000_000L, 1_000L));
        databaseClock.set(1_500L);
        assertFalse(nodeB.acquire("xuid-db-clock", "node-b", "session-b", 1L, 1_000L),
                "A node with a fast local clock must not steal a lease before the shared DB expiry");
        databaseClock.set(2_001L);
        assertTrue(nodeB.acquire("xuid-db-clock", "node-b", "session-b", 1L, 1_000L),
                "A node with a slow local clock must still reclaim after shared DB expiry");
    }

    @Test
    void concurrentJdbcNodesCannotAcquireSameUnownedXuid() throws Exception {
        String url = "jdbc:h2:mem:online-lease-race;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(500L);
        JdbcPlayerOnlineLeaseRegistry first = new JdbcPlayerOnlineLeaseRegistry(dataSource(url), clock::get);
        first.initializeSchema();
        JdbcPlayerOnlineLeaseRegistry second = new JdbcPlayerOnlineLeaseRegistry(dataSource(url), clock::get);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = workers.submit(() -> {
                start.await();
                return first.acquire("xuid-race", "node-a", "session-a", 500L, 5_000L);
            });
            Future<Boolean> b = workers.submit(() -> {
                start.await();
                return second.acquire("xuid-race", "node-b", "session-b", 500L, 5_000L);
            });
            start.countDown();
            assertNotEquals(a.get(), b.get(), "Exactly one cluster node must acquire an unowned XUID");
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void jdbcLeaseOperationsFailClosedWhenSharedDatabaseIsUnavailable() {
        JdbcPlayerOnlineLeaseRegistry unavailable =
                new JdbcPlayerOnlineLeaseRegistry(dataSource("jdbc:nexora-unavailable:lease-db"));
        assertThrows(java.io.IOException.class,
                () -> unavailable.acquire("xuid-offline", "node-a", "session-a", 100L, 1_000L));
        assertThrows(java.io.IOException.class,
                () -> unavailable.renew("xuid-offline", "node-a", "session-a", 200L, 1_000L));
        assertThrows(java.io.IOException.class,
                () -> unavailable.release("xuid-offline", "node-a", "session-a"));
    }

    private static JdbcDataSource dataSource(String url) {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL(url);
        return source;
    }
}
